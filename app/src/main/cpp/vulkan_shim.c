/* Vulkan loader shim for Android.
 *
 * libpocketllm.so links this instead of the system libvulkan. On startup it
 * opens the bundled Mesa Turnip driver (HAL "HMI" pattern) and forwards the
 * global Vulkan entry points into it; ggml-vulkan's dynamic dispatcher is
 * initialized from our vkGetInstanceProcAddr, so everything flows to Turnip.
 * If Turnip is unavailable or fails to init, we fall back to the system
 * libvulkan so capable devices keep working.
 *
 * Diagnostics are collected for backendInfo().
 */
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
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

    /* Locate our own lib dir (the turnip driver ships next to us). */
    Dl_info info;
    void *self = (void *)&vulkan_shim_init;
    if (dladdr(self, &info) && info.dli_fname) {
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

/* ---- Exported entry points (what ggml-vulkan links against) ---- */

/* ggml-vulkan also references a few Vulkan functions DIRECTLY (link-time
 * relocations), not through its dynamic dispatcher. The linker requires all
 * of them at System.loadLibrary time, so the shim exports them too and
 * resolves each against the instance(s) it created. */

static VkInstance g_instances[8] = {0};
static int g_instance_count = 0;

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
    VkResult r = g_create_instance(pCreateInfo, pAllocator, pInstance);
    if (r == VK_SUCCESS && pInstance && g_instance_count < 8) {
        g_instances[g_instance_count++] = *pInstance;
        diagf("instance stored (%d)\n", g_instance_count);
    }
    return r;
}

VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures2 *pFeatures) {
    static void (*fn)(VkPhysicalDevice, VkPhysicalDeviceFeatures2 *) = NULL;
    if (!fn) fn = (void (*)(VkPhysicalDevice, VkPhysicalDeviceFeatures2 *))resolve_for("vkGetPhysicalDeviceFeatures2");
    if (fn) fn(physicalDevice, pFeatures);
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
