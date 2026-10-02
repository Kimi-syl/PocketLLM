/* Vulkan loader shim for Android.
 *
 * libpocketllm.so links this instead of the system libvulkan. On startup it
 * opens the bundled Mesa Turnip driver (HAL "HMI" pattern) and forwards the
 * global Vulkan entry points into it; ggml-vulkan's dynamic dispatcher is
 * initialized from our vkGetInstanceProcAddr, so everything flows to Turnip.
 * If Turnip is unavailable or fails to init, we fall back to the system
 * libvulkan so capable devices keep working.
 *
 * Physical-device feature queries get a layered fallback for drivers whose
 * loader does not hand out core-1.1 vkGetPhysicalDeviceFeatures2 (seen on
 * MediaTek Mali):
 *   1. core vkGetPhysicalDeviceFeatures2
 *   2. KHR alias vkGetPhysicalDeviceFeatures2KHR (spec-promoted: same
 *      command, same struct type, same sType value - nothing to convert)
 *   3. v1 vkGetPhysicalDeviceFeatures (no KHR alias of the v1 command
 *      exists - the extension only added *2 commands) fills the base
 *      struct, plus sType-bounded zeroing of the pNext chain so ggml never
 *      reads garbage
 *   4. nothing resolvable -> vulkan_shim_driver_broken() reports the driver
 *      broken and llama_jni keeps the Vulkan backend unregistered
 *
 * Instances are tracked no matter how they are created: ggml's dispatcher
 * resolves vkCreateInstance through OUR vkGetInstanceProcAddr, so that name
 * routes to the storing wrapper (which also injects
 * VK_KHR_get_physical_device_properties2 when the driver advertises it -
 * loaders predating core 1.1 wire the *2KHR entry points only for instances
 * that enable the extension).
 *
 * Diagnostics are collected for backendInfo().
 */
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <stdarg.h>

/* Exact AOSP hardware HAL layouts (hardware/libhardware hardware.h, LP64).
 * Not in the NDK, so reproduced here - offsets must match the driver. */
typedef struct hw_device_t hw_device_t;
typedef struct hw_module_t hw_module_t;
typedef struct {
    int (*open)(const hw_module_t *module, const char *id, hw_device_t **device);
} hw_module_methods_t;
struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    hw_module_methods_t *methods;
    void *dso;
    uint64_t reserved[32 - 7]; /* padding to 128 bytes on LP64 */
};
struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t *module;
    uint64_t reserved[12];
    int (*close)(struct hw_device_t *device);
};
/* hwvulkan_device_t per AOSP hardware/hwvulkan.h */
typedef struct {
    hw_device_t common;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties;
    PFN_vkCreateInstance CreateInstance;
} hwvulkan_device_t;

static PFN_vkGetInstanceProcAddr g_gipa = NULL;
static PFN_vkCreateInstance g_create_instance = NULL;
static PFN_vkEnumerateInstanceExtensionProperties g_eiep = NULL;
static void *g_driver = NULL;
static int g_using_turnip = 0;
static char g_diag[4096];
static size_t g_diag_len = 0;

static void diagf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int left = (int)(sizeof(g_diag) - g_diag_len - 1);
    if (left > 0) {
        int n = vsnprintf(g_diag + g_diag_len, left, fmt, ap);
        if (n > 0) g_diag_len += (size_t)(n > left ? left : n);
    }
    va_end(ap);
}

/* Return value for detect_gpu_vendor: which GPU family this device has.
 * 0 = adreno, 1 = mali, 2 = powervr, 3 = tegra, 4 = unknown */
static int detect_gpu_vendor(void) {
    /* (1) ro.hardware.vulkan (Android 10+) is the most authoritative:
     *     "qcom" -> Adreno, "arm" -> Mali, "imgtec" -> PowerVR, "nvidia" -> Tegra. */
    FILE *fp = popen("getprop ro.hardware.vulkan 2>/dev/null", "r");
    char buf[64] = {0};
    if (fp) {
        if (fgets(buf, sizeof(buf), fp)) {
            char *nl = strchr(buf, '\n'); if (nl) *nl = 0;
            pclose(fp);
            if (strstr(buf, "qcom"))    return 0;
            if (strstr(buf, "arm"))     return 1;
            if (strstr(buf, "imgtec"))  return 2;
            if (strstr(buf, "nvidia"))  return 3;
        } else { pclose(fp); }
    }
    /* (2) Fall back to /proc/cpuinfo "Hardware" line. */
    FILE *cpu = fopen("/proc/cpuinfo", "r");
    if (!cpu) return 4;
    while (fgets(buf, sizeof(buf), cpu)) {
        if (strncmp(buf, "Hardware", 8) == 0) {
            char *colon = strchr(buf, ':');
            if (!colon) continue;
            char *hw = colon + 1;
            while (*hw == ' ' || *hw == '\t') hw++;
            fclose(cpu);
            if (strstr(hw, "Qualcomm") || strstr(hw, "MSM") || strstr(hw, "APQ") || strstr(hw, "SDM"))
                return 0; /* adreno */
            if (strstr(hw, "Rockchip") || strstr(hw, "Exynos")  || strstr(hw, "MediaTek") ||
                strstr(hw, "Unisoc")   || strstr(hw, "Spreadtrum") || strstr(hw, "HiSilicon") ||
                strstr(hw, "Kirin")    || strstr(hw, "MT") || strstr(hw, "rk3399") || strstr(hw, "rk3288"))
                return 1; /* mali */
            if (strstr(hw, "Tegra"))     return 3;
            return 4;
        }
    }
    fclose(cpu);
    return 4;
}

static const char *gpu_vendor_name(int v) {
    switch (v) {
        case 0:  return "Adreno";
        case 1:  return "Mali";
        case 2:  return "PowerVR";
        case 3:  return "Tegra";
        default: return "unknown";
    }
}

static void load_system_fallback(void) {
    void *h = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        diagf("system libvulkan dlopen failed\n");
        return;
    }
    void *p = dlsym(h, "vkGetInstanceProcAddr");
    if (!p) {
        diagf("system vkGetInstanceProcAddr missing\n");
        return;
    }
    g_gipa = (PFN_vkGetInstanceProcAddr)p;
    g_driver = h;
    g_using_turnip = 0;
    diagf("using system vulkan driver\n");
}

__attribute__((constructor)) static void vulkan_shim_init(void) {
    diagf("vulkan shim init\n");

    int vendor = detect_gpu_vendor();
    diagf("gpu vendor: %s\n", gpu_vendor_name(vendor));

    /* Locate our own lib dir (the turnip driver ships next to us). */
    Dl_info info;
    void *self = (void *)&vulkan_shim_init;
    /* Turnip is opt-in: it segfaults on some GPUs (e.g. Adreno 610), and a
     * native crash cannot be caught in-process. Only load it when the app
     * settings created the flag file. */
    int turnip_enabled = access("/data/data/com.pocketllm/files/turnip.on", F_OK) == 0;
    diagf("turnip opt-in flag: %s\n", turnip_enabled ? "on" : "off (default)");
    if (turnip_enabled && vendor != 0) {
        /* Turnip is the Mesa Adreno (freedreno) driver. It is not a generic
         * Vulkan ICD — it talks directly to the freedreno kernel driver,
         * which only exists on Qualcomm SoCs. On Mali/PowerVR/Tegra devices
         * the .so will load (it's a self-contained Mesa build) but every
         * vkCreateInstance / vkAllocateMemory will fail or crash inside the
         * driver. Refuse to load it here so the user gets a clear message
         * instead of a native crash they can't recover from. */
        diagf("turnip NOT loaded: bundled driver is for Adreno (Qualcomm), but this device has %s. Falling back to system Vulkan.\n",
               gpu_vendor_name(vendor));
        turnip_enabled = 0;
    }
    if (dladdr(self, &info) && info.dli_fname && turnip_enabled) {
        char path[512];
        snprintf(path, sizeof(path), "%s", info.dli_fname);
        char *slash = strrchr(path, '/');
        if (slash) {
            snprintf(slash + 1, sizeof(path) - (slash + 1 - path), "libvulkan_freedreno.so");
            diagf("turnip path: %s\n", path);
            void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
            diagf("dlopen turnip: %s\n", h ? "ok" : "failed");
            if (h) {
                hw_module_t *hmi = (hw_module_t *)dlsym(h, "HMI");
                if (!hmi) {
                    diagf("HMI dlsym failed\n");
                } else if (strcmp(hmi->id, "vulkan") != 0) {
                    diagf("HMI id != vulkan (%s)\n", hmi->id ? hmi->id : "?");
                } else {
                    hwvulkan_device_t *dev = NULL;
                    int rc = hmi->methods->open(hmi, "vk0", (hw_device_t **)&dev);
                    diagf("HMI open: rc=%d\n", rc);
                    if (rc == 0 && dev) {
                        g_gipa = dev->GetInstanceProcAddr;
                        g_create_instance = dev->CreateInstance;
                        g_eiep = dev->EnumerateInstanceExtensionProperties;
                        g_driver = h;
                        g_using_turnip = 1;
                        diagf("turnip driver ready (drm render node)\n");
                    }
                }
            } else {
                diagf("turnip dlopen error: %s\n", dlerror() ? dlerror() : "");
            }
        } else {
            diagf("dladdr path has no slash\n");
        }
    } else if (!turnip_enabled) {
        diagf("turnip skipped: disabled\n");
    } else {
        diagf("dladdr failed\n");
    }

    if (!g_gipa) {
        load_system_fallback();
    }
    if (g_gipa && !g_create_instance) {
        g_create_instance = (PFN_vkCreateInstance)g_gipa(NULL, "vkCreateInstance");
        g_eiep = (PFN_vkEnumerateInstanceExtensionProperties)g_gipa(NULL, "vkEnumerateInstanceExtensionProperties");
    }
    diagf("shim ready: turnip=%d gipa=%s\n", g_using_turnip, g_gipa ? "yes" : "no");
}

const char *vulkan_shim_debug(void) { return g_diag; }

/* ---- Instance tracking ----
 * ggml's DispatchLoaderDynamic resolves vkCreateInstance through our
 * vkGetInstanceProcAddr, so routing that one name to our wrapper here is
 * what makes g_instances[] complete. Physical-device commands may only be
 * resolvable instance-level (a NULL instance legally returns NULL), so an
 * empty g_instances[] silently disables every fallback below - the bug that
 * logged "unresolved" on a working driver. */

static VkInstance g_instances[8] = {0};
static int g_instance_count = 0;

static const char kGPDP2[] = "VK_KHR_get_physical_device_properties2";

static int ext_list_contains(const VkInstanceCreateInfo *ci, const char *name) {
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        const char *const e = ci->ppEnabledExtensionNames ? ci->ppEnabledExtensionNames[i] : NULL;
        if (e && strcmp(e, name) == 0) return 1;
    }
    return 0;
}

static int instance_ext_advertised(const char *name) {
    if (!g_eiep) return 0;
    uint32_t n = 0;
    if (g_eiep(NULL, &n, NULL) != VK_SUCCESS || n == 0) return 0;
    VkExtensionProperties *props = (VkExtensionProperties *)malloc(n * sizeof(*props));
    if (!props) return 0;
    int found = 0;
    if (g_eiep(NULL, &n, props) == VK_SUCCESS) {
        for (uint32_t i = 0; i < n; i++) {
            if (strcmp(props[i].extensionName, name) == 0) { found = 1; break; }
        }
    }
    free(props);
    return found;
}

/* ---- Physical-device feature query resolution ----
 * The KHR command is the spec-promoted alias of the core one: same
 * signature, same struct type, same sType enum value. One pointer type
 * serves both spellings. */

static PFN_vkGetPhysicalDeviceFeatures2 g_pd2  = NULL;  /* core spelling */
static PFN_vkGetPhysicalDeviceFeatures2 g_pd2k = NULL;  /* KHR spelling  */
static PFN_vkGetPhysicalDeviceFeatures  g_pd1  = NULL;  /* v1, base only */

static void ensure_feature_fns(void) {
    if (g_pd2 || g_pd2k) return;
    if (!g_gipa) return;
    for (int i = 0; i < g_instance_count && !(g_pd2 || g_pd2k); i++) {
        if (!g_pd2) {
            g_pd2 = (PFN_vkGetPhysicalDeviceFeatures2)g_gipa(g_instances[i], "vkGetPhysicalDeviceFeatures2");
        }
        if (!g_pd2k) {
            g_pd2k = (PFN_vkGetPhysicalDeviceFeatures2)g_gipa(g_instances[i], "vkGetPhysicalDeviceFeatures2KHR");
        }
        if (!g_pd1) {
            g_pd1 = (PFN_vkGetPhysicalDeviceFeatures)g_gipa(g_instances[i], "vkGetPhysicalDeviceFeatures");
        }
    }
    if (!g_pd2 && !g_pd2k && !g_pd1 && g_instance_count == 0) {
        /* Spec: NULL instance resolves only global commands, but some
         * Android loaders hand out trampolines anyway; try once. */
        g_pd1 = (PFN_vkGetPhysicalDeviceFeatures)g_gipa(NULL, "vkGetPhysicalDeviceFeatures");
    }
    if (g_pd2)       diagf("features2 via core\n");
    else if (g_pd2k) diagf("features2 via KHR alias\n");
    else if (g_pd1)  diagf("features2 unavailable, v1 only (chain zeroed)\n");
    else             diagf("no feature query resolvable\n");
}

/* True when the driver cannot answer 2-level feature queries at all: ggml's
 * chained feature structs (Vulkan11/12Features, shaderFloat16, ...) could
 * never hold real values and the pNext chain would be conservatively zeroed
 * (all-false features), which forfeits fp16. llama_jni consults this before
 * llama_backend_init() and sets GGML_DISABLE_VULKAN=1 so the backend never
 * registers - this also keeps CPU-only loads from routing tensor buffers
 * through the vk host allocator on a driver that cannot create buffers. */
int vulkan_shim_driver_broken(void) {
    if (!g_gipa || !g_create_instance) return 1;
    ensure_feature_fns();
    return !(g_pd2 || g_pd2k);
}

/* Fork-isolated GPU-usable probe: the full ggml load path in miniature —
 * instance (KHR injected) -> features2 chain -> vkCreateDevice ->
 * createBuffer + allocateMemory + map -> free. A driver that survives this
 * can serve llama.cpp; one that segfaults only kills the child, and
 * llama_jni keeps gpuLayers=99 attempts off the table (CPU loads stay safe
 * via no_host). Runs on demand from supportsGpuOffload(), never at load. */
static char g_gpu_probe[512];

static void gpu_probe_child(int fd) {
    uint32_t api = 0;
    PFN_vkEnumerateInstanceVersion ev =
        (PFN_vkEnumerateInstanceVersion)g_gipa(NULL, "vkEnumerateInstanceVersion");
    if (ev) ev(&api);
    if (VK_API_VERSION_MAJOR(api) < 1 || VK_API_VERSION_MINOR(api) < 1) {
        write(fd, "api<1.1\n", 8);
        _exit(0);
    }
    const char *exts[1] = { kGPDP2 };
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "pocketllm-gpuprobe", .apiVersion = api};
    VkInstanceCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    if (instance_ext_advertised(kGPDP2)) {
        ci.enabledExtensionCount = 1;
        ci.ppEnabledExtensionNames = exts;
    }
    VkInstance inst = VK_NULL_HANDLE;
    if (g_create_instance(&ci, NULL, &inst) != VK_SUCCESS) {
        write(fd, "instance failed\n", 16);
        _exit(0);
    }
    PFN_vkEnumeratePhysicalDevices eps =
        (PFN_vkEnumeratePhysicalDevices)g_gipa(inst, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceFeatures2 pdf2 =
        (PFN_vkGetPhysicalDeviceFeatures2)g_gipa(inst, "vkGetPhysicalDeviceFeatures2");
    if (!pdf2) pdf2 = (PFN_vkGetPhysicalDeviceFeatures2)g_gipa(inst, "vkGetPhysicalDeviceFeatures2KHR");
    PFN_vkGetPhysicalDeviceProperties gp =
        (PFN_vkGetPhysicalDeviceProperties)g_gipa(inst, "vkGetPhysicalDeviceProperties");
    if (!eps || !pdf2 || !gp) {
        write(fd, "enum fns missing\n", 17);
        _exit(0);
    }
    uint32_t count = 0;
    if (eps(inst, &count, NULL) != VK_SUCCESS || count == 0) {
        write(fd, "no devices\n", 11);
        _exit(0);
    }
    VkPhysicalDevice *devs = (VkPhysicalDevice *)calloc(count, sizeof(VkPhysicalDevice));
    if (!devs) _exit(0);
    eps(inst, &count, devs);
    const char *verdict = "no usable device\n";
    for (uint32_t i = 0; i < count; i++) {
        /* Mirrors ggml: chained 11/12 features must come back populated and
         * storageBuffer16BitAccess is a hard requirement. */
        VkPhysicalDeviceVulkan12Features vk12 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        VkPhysicalDeviceVulkan11Features vk11 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, .pNext = &vk12};
        VkPhysicalDeviceFeatures2 f2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &vk11};
        pdf2(devs[i], &f2);
        if (!vk11.storageBuffer16BitAccess) {
            continue; /* ggml throws "does not support 16-bit storage" */
        }
        VkPhysicalDeviceProperties props;
        gp(devs[i], &props);
        (void)props;
        /* queue family 0 with compute+graphics is what ggml picks first */
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .queueFamilyIndex = 0,
            .queueCount = 1,
            .pQueuePriorities = &prio,
        };
        const char *dev_exts[2] = {"VK_KHR_16bit_storage", "VK_KHR_shader_float16_int8"};
        VkDeviceCreateInfo dci = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
            .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &qci,
            .enabledExtensionCount = 2,
            .ppEnabledExtensionNames = dev_exts,
        };
        VkPhysicalDeviceFeatures2 dfeatures = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &vk11};
        dci.pNext = &dfeatures;
        PFN_vkCreateDevice cd = (PFN_vkCreateDevice)g_gipa(inst, "vkCreateDevice");
        if (!cd) { verdict = "no vkCreateDevice\n"; continue; }
        VkDevice logidev = VK_NULL_HANDLE;
        VkResult r = cd(devs[i], &dci, NULL, &logidev);
        if (r != VK_SUCCESS) { verdict = "createDevice failed\n"; continue; }
        PFN_vkCreateBuffer cb = (PFN_vkCreateBuffer)g_gipa(inst, "vkCreateBuffer");
        PFN_vkAllocateMemory am = (PFN_vkAllocateMemory)g_gipa(inst, "vkAllocateMemory");
        PFN_vkMapMemory mm = (PFN_vkMapMemory)g_gipa(inst, "vkMapMemory");
        PFN_vkFreeMemory fm = (PFN_vkFreeMemory)g_gipa(inst, "vkFreeMemory");
        PFN_vkDestroyBuffer db = (PFN_vkDestroyBuffer)g_gipa(inst, "vkDestroyBuffer");
        PFN_vkDestroyDevice dd = (PFN_vkDestroyDevice)g_gipa(inst, "vkDestroyDevice");
        if (cb && am && mm && fm && db && dd) {
            VkBufferCreateInfo bci = {
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .size = 1 << 20,
                .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            };
            VkBuffer buf = VK_NULL_HANDLE;
            r = cb(logidev, &bci, NULL, &buf);
            if (r != VK_SUCCESS) {
                verdict = "createBuffer failed\n";
            } else {
                VkMemoryRequirements mr;
                PFN_vkGetBufferMemoryRequirements gb =
                    (PFN_vkGetBufferMemoryRequirements)g_gipa(inst, "vkGetBufferMemoryRequirements");
                if (!gb) { verdict = "no mem reqs fn\n"; }
                else {
                    gb(logidev, buf, &mr);
                    PFN_vkGetPhysicalDeviceMemoryProperties gmp =
                        (PFN_vkGetPhysicalDeviceMemoryProperties)g_gipa(inst, "vkGetPhysicalDeviceMemoryProperties");
                    VkPhysicalDeviceMemoryProperties mp;
                    gmp(devs[i], &mp);
                    uint32_t type = VK_MAX_MEMORY_TYPES;
                    for (uint32_t t = 0; t < mp.memoryTypeCount; t++) {
                        if ((mr.memoryTypeBits & (1u << t)) &&
                            (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
                            (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                            type = t; break;
                        }
                    }
                    if (type == VK_MAX_MEMORY_TYPES) {
                        verdict = "no host-visible mem type\n";
                    } else {
                        VkMemoryAllocateInfo mai = {
                            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                            .allocationSize = mr.size,
                            .memoryTypeIndex = type,
                        };
                        VkDeviceMemory mem = VK_NULL_HANDLE;
                        r = am(logidev, &mai, NULL, &mem);
                        if (r != VK_SUCCESS) {
                            verdict = "allocateMemory failed\n";
                        } else {
                            void *mapped = NULL;
                            r = mm(logidev, mem, 0, mr.size, 0, &mapped);
                            if (r != VK_SUCCESS || !mapped) {
                                verdict = "map failed\n";
                            } else {
                                verdict = "GPU USABLE\n";
                            }
                            fm(logidev, mem, NULL);
                        }
                    }
                }
                db(logidev, buf, NULL);
            }
            dd(logidev, NULL);
        } else {
            verdict = "dev fns missing\n";
        }
        break;
    }
    free(devs);
    write(fd, verdict, strlen(verdict));
    _exit(0);
}

const char *vulkan_shim_gpu_usable(void) {
    if (!g_driver || !g_gipa || !g_create_instance) return NULL;
    int fds[2];
    if (pipe(fds) != 0) return NULL;
    pid_t pid = fork();
    if (pid < 0) { close(fds[0]); close(fds[1]); return NULL; }
    if (pid == 0) {
        close(fds[0]);
        gpu_probe_child(fds[1]);
        _exit(0);
    }
    close(fds[1]);
    char out[64] = {0};
    size_t got = 0;
    ssize_t r;
    while (got < sizeof(out) - 1 &&
           (r = read(fds[0], out + got, sizeof(out) - 1 - got)) > 0) got += (size_t)r;
    close(fds[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFSIGNALED(status)) {
        snprintf(g_gpu_probe, sizeof(g_gpu_probe), "CRASHED (signal %d)", WTERMSIG(status));
        diagf("gpu probe: %s\n", g_gpu_probe);
        return NULL;
    }
    snprintf(g_gpu_probe, sizeof(g_gpu_probe), "%s", out);
    diagf("gpu probe: %s", g_gpu_probe);
    if (strcmp(out, "GPU USABLE\n") == 0) return g_gpu_probe;
    return NULL;
}

/* Fork-isolated driver probe: exercises instance creation + physical device
 * enumeration in a child process so a driver segfault (Turnip on some GPUs)
 * kills only the child. The parent reports what happened. Runs on demand
 * (GPU info button), never at load. */
static char g_probe[1024];

const char *vulkan_shim_probe(void) {
    if (!g_driver) {
        snprintf(g_probe, sizeof(g_probe), "no driver loaded\n");
        return g_probe;
    }
    int fds[2];
    if (pipe(fds) != 0) {
        snprintf(g_probe, sizeof(g_probe), "pipe failed\n");
        return g_probe;
    }
    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]); close(fds[1]);
        snprintf(g_probe, sizeof(g_probe), "fork failed\n");
        return g_probe;
    }
    if (pid == 0) {
        /* child */
        close(fds[0]);
        char out[768];
        size_t n = 0;
        do {
            uint32_t api = 0;
            PFN_vkEnumerateInstanceVersion ev =
                (PFN_vkEnumerateInstanceVersion)g_gipa(NULL, "vkEnumerateInstanceVersion");
            if (ev) ev(&api);
            n += (size_t)snprintf(out + n, sizeof(out) - n, "api %u.%u\n",
                                  VK_API_VERSION_MAJOR(api), VK_API_VERSION_MINOR(api));

            VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "pocketllm-probe", .apiVersion = 0};
            VkInstanceCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
            VkInstance inst = VK_NULL_HANDLE;
            VkResult r = g_create_instance(&ci, NULL, &inst);
            if (r != VK_SUCCESS) {
                n += (size_t)snprintf(out + n, sizeof(out) - n, "vkCreateInstance failed: %d\n", (int)r);
                break;
            }
            n += (size_t)snprintf(out + n, sizeof(out) - n, "instance ok\n");

            /* Feature-query resolution report (the whole point of the shim). */
            {
                void *f2  = (void *)g_gipa(inst, "vkGetPhysicalDeviceFeatures2");
                void *f2k = (void *)g_gipa(inst, "vkGetPhysicalDeviceFeatures2KHR");
                void *f1  = (void *)g_gipa(inst, "vkGetPhysicalDeviceFeatures");
                n += (size_t)snprintf(out + n, sizeof(out) - n, "feat fns: core2=%d khr2=%d v1=%d\n",
                                      f2 != NULL, f2k != NULL, f1 != NULL);
                n += (size_t)snprintf(out + n, sizeof(out) - n, "ext gpdp2 advertised: %s\n",
                                      instance_ext_advertised(kGPDP2) ? "yes" : "no");
            }

            PFN_vkEnumeratePhysicalDevices eps =
                (PFN_vkEnumeratePhysicalDevices)g_gipa(inst, "vkEnumeratePhysicalDevices");
            if (!eps) { n += (size_t)snprintf(out + n, sizeof(out) - n, "no EnumeratePhysicalDevices\n"); break; }
            uint32_t count = 0;
            r = eps(inst, &count, NULL);
            if (r != VK_SUCCESS) { n += (size_t)snprintf(out + n, sizeof(out) - n, "enum failed: %d\n", (int)r); break; }
            n += (size_t)snprintf(out + n, sizeof(out) - n, "devices: %u\n", count);
            if (count == 0) break;
            VkPhysicalDevice devs[4];
            uint32_t c2 = count < 4 ? count : 4;
            eps(inst, &c2, devs);
            PFN_vkGetPhysicalDeviceProperties gp =
                (PFN_vkGetPhysicalDeviceProperties)g_gipa(inst, "vkGetPhysicalDeviceProperties");
            for (uint32_t i = 0; i < c2; i++) {
                VkPhysicalDeviceProperties p;
                gp(devs[i], &p);
                n += (size_t)snprintf(out + n, sizeof(out) - n, "dev%u: %s api %u.%u\n", i, p.deviceName,
                                      VK_API_VERSION_MAJOR(p.apiVersion), VK_API_VERSION_MINOR(p.apiVersion));
            }
        } while (0);
        write(fds[1], out, n);
        _exit(0);
    }
    /* parent */
    close(fds[1]);
    char out[768] = {0};
    size_t got = 0;
    ssize_t r;
    while (got < sizeof(out) - 1 &&
           (r = read(fds[0], out + got, sizeof(out) - 1 - got)) > 0) got += (size_t)r;
    close(fds[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFSIGNALED(status)) {
        snprintf(g_probe, sizeof(g_probe), "driver probe CRASHED (signal %d)%s%s\n",
                 WTERMSIG(status), got ? ":\n" : "", out);
    } else {
        snprintf(g_probe, sizeof(g_probe), "driver probe ok:\n%s", out);
    }
    return g_probe;
}

/* ---- Exported entry points (what ggml-vulkan links against) ---- */

/* ggml-vulkan also references a few Vulkan functions DIRECTLY (link-time
 * relocations), not through its dynamic dispatcher. The linker requires all
 * of them at System.loadLibrary time, so the shim exports them too and
 * resolves each against the instance(s) it created. */

static void *resolve_for(const char *name) {
    if (!g_gipa) return NULL;
    void *f = NULL;
    for (int i = 0; i < g_instance_count && !f; i++) {
        f = (void *)g_gipa(g_instances[i], name);
    }
    if (!f) f = (void *)g_gipa(NULL, name);
    if (!f) diagf("unresolved: %s\n", name);
    return f;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!g_gipa) return NULL;
    /* Route instance creation through our wrapper so instances created via
     * ggml's dispatcher are tracked and get the KHR extension injected. */
    if (strcmp(name, "vkCreateInstance") == 0) {
        return (PFN_vkVoidFunction)vkCreateInstance;
    }
    return g_gipa(instance, name);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name) {
    static PFN_vkGetDeviceProcAddr fn = NULL;
    if (!fn) fn = (PFN_vkGetDeviceProcAddr)resolve_for("vkGetDeviceProcAddr");
    if (!fn) return NULL;
    return fn(device, name);
}

VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo *pCreateInfo, const VkAllocationCallbacks *pAllocator, VkInstance *pInstance) {
    if (!g_create_instance) return VK_ERROR_INITIALIZATION_FAILED;
    VkInstanceCreateInfo ci = *pCreateInfo;
    const char *ext_ptrs[32];
    int injected = 0;
    /* Enable VK_KHR_get_physical_device_properties2 when the driver
     * advertises it: loaders predating core 1.1 wire the *2KHR entry points
     * only for instances that enable the extension. Core-1.1+ drivers
     * accept it as an implicitly-available instance extension. */
    if (ci.enabledExtensionCount < 32 &&
        !ext_list_contains(&ci, kGPDP2) &&
        instance_ext_advertised(kGPDP2)) {
        for (uint32_t i = 0; i < ci.enabledExtensionCount; i++) {
            ext_ptrs[i] = ci.ppEnabledExtensionNames[i];
        }
        ext_ptrs[ci.enabledExtensionCount] = kGPDP2;
        ci.ppEnabledExtensionNames = ext_ptrs;
        ci.enabledExtensionCount += 1;
        injected = 1;
    }
    VkResult r = g_create_instance(&ci, pAllocator, pInstance);
    if (r == VK_SUCCESS && pInstance && g_instance_count < 8) {
        g_instances[g_instance_count++] = *pInstance;
        diagf("instance stored (%d)%s\n", g_instance_count, injected ? " +gpdp2" : "");
    }
    return r;
}

VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures2 *pFeatures) {
    ensure_feature_fns();
    if (g_pd2) {
        g_pd2(physicalDevice, pFeatures);
        return;
    }
    if (g_pd2k) {
        /* VkPhysicalDeviceFeatures2KHR is the same struct type and
         * VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2_KHR the same enum
         * value as the core spellings (spec-promoted alias), so the struct
         * and its pNext chain pass through unmodified - no conversion
         * needed, not even of sType. */
        g_pd2k(physicalDevice, pFeatures);
        return;
    }
    /* Nothing 2-level resolvable. v1 fills the base struct (a KHR alias of
     * the v1 command does not exist - the extension only added *2 commands);
     * the pNext chain is zeroed bounded by each struct's own sizeof so ggml
     * never reads garbage. All-false chain features are safe at device
     * creation (they request nothing); garbage was what crashed. */
    {
        if (!g_pd1) {
            for (int i = 0; i < g_instance_count && !g_pd1; i++) {
                g_pd1 = (PFN_vkGetPhysicalDeviceFeatures)g_gipa(g_instances[i], "vkGetPhysicalDeviceFeatures");
            }
        }
        if (g_pd1) g_pd1(physicalDevice, &pFeatures->features);
        else memset(&pFeatures->features, 0, sizeof(pFeatures->features));
    }
    VkBaseOutStructure *s = (VkBaseOutStructure *)pFeatures->pNext;
    while (s != NULL) {
        /* payload = the struct's own full size keyed by sType; zeroing from
         * the 16-byte header to its own sizeof never crosses into a
         * neighbouring stack local. Unknown structs keep the 16-byte floor
         * (header only). */
        size_t payload = 16;
        switch ((unsigned int)s->sType) {
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES: payload = sizeof(VkPhysicalDeviceVulkan11Features); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES: payload = sizeof(VkPhysicalDeviceVulkan12Features); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES: payload = sizeof(VkPhysicalDeviceVulkan13Features); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2: payload = sizeof(VkPhysicalDeviceFeatures2); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COOPERATIVE_MATRIX_FEATURES_KHR: payload = sizeof(VkPhysicalDeviceCooperativeMatrixFeaturesKHR); break;
            case 1000411001u /* PHYSICAL_DEVICE_SHADER_BFLOAT16_FEATURES_KHR (older headers) */: payload = 16; break;
            case 1000361000u /* PHYSICAL_DEVICE_INTERNALLY_SYNCHRONIZED_QUEUES_FEATURES_KHR (older headers) */: payload = 16; break;
            default: break;
        }
        if (payload > 16) memset((unsigned char *)s + 16, 0, payload - 16);
        s = s->pNext;
    }
}

VKAPI_ATTR void VKAPI_CALL vkCmdCopyBuffer(VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkBuffer dstBuffer, uint32_t regionCount, const VkBufferCopy *pRegions) {
    static void (*fn)(VkCommandBuffer, VkBuffer, VkBuffer, uint32_t, const VkBufferCopy *) = NULL;
    if (!fn) fn = (void (*)(VkCommandBuffer, VkBuffer, VkBuffer, uint32_t, const VkBufferCopy *))resolve_for("vkCmdCopyBuffer");
    if (fn) fn(commandBuffer, srcBuffer, dstBuffer, regionCount, pRegions);
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char *pLayerName, uint32_t *pPropertyCount, VkExtensionProperties *pProperties) {
    if (!g_eiep) return VK_ERROR_INITIALIZATION_FAILED;
    return g_eiep(pLayerName, pPropertyCount, pProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(uint32_t *pPropertyCount, VkLayerProperties *pProperties) {
    if (!g_gipa) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkEnumerateInstanceLayerProperties fn =
        (PFN_vkEnumerateInstanceLayerProperties)g_gipa(NULL, "vkEnumerateInstanceLayerProperties");
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    return fn(pPropertyCount, pProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceVersion(uint32_t *pApiVersion) {
    if (!g_gipa) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkEnumerateInstanceVersion fn =
        (PFN_vkEnumerateInstanceVersion)g_gipa(NULL, "vkEnumerateInstanceVersion");
    if (!fn) {
        /* Vulkan 1.0 driver */
        if (pApiVersion) *pApiVersion = VK_API_VERSION_1_0;
        return VK_SUCCESS;
    }
    return fn(pApiVersion);
}
