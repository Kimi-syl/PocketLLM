#import "SdBridge.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <string>
#include <sys/time.h>
#include <vector>

#include "stable-diffusion.h"

/// Atomics rather than a callback: the sampler runs off the main thread, so
/// polling from Swift avoids re-entering it from C++.
static std::atomic<int32_t> g_step{0};
static std::atomic<int32_t> g_steps{0};

static void progress_cb(int step, int steps, float /*time*/, void * /*data*/) {
    g_step.store(step);
    g_steps.store(steps);
}

/// ggml reports why it is aborting through the log callback, and then the
/// process dies. Writing straight to the persistent log with an immediate flush
/// is the only way that reason survives the crash - a buffered write, or one
/// that only lands in an in-memory ring, is lost with the process.
///
/// The line is timestamped to match LlamaBridge's bridgeLog. Without that the
/// Logs tab parses it away on reload and the export skips it, which is exactly
/// how the Metal failure stayed invisible: the engine's own reason was in the
/// file but not in the fragment anyone could see.
static FILE * g_log_file = nullptr;

// Implemented in Swift (LogStore.swift); extern "C" because this is a C++ TU.
extern "C" void PocketLLMNativeLog(const char *msg);

static const char * sd_level_name(enum sd_log_level_t level) {
    switch (level) {
        case SD_LOG_DEBUG:   return "DEBUG";
        case SD_LOG_VERBOSE: return "VERBOSE";
        case SD_LOG_INFO:    return "INFO";
        case SD_LOG_WARN:    return "WARN";
        case SD_LOG_ERROR:   return "ERROR";
    }
    return "?";
}

static void sd_log_cb(enum sd_log_level_t level, const char * text, void * /*data*/) {
    if (text == nullptr) return;
    std::string msg(text);
    while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) msg.pop_back();
    if (msg.empty()) return;

    char body[4096];
    snprintf(body, sizeof(body), "[sd/%s] %s", sd_level_name(level), msg.c_str());

    // A hand-rolled timestamp, not NSDateFormatter: the sampler can emit a burst
    // of these, and allocating a formatter per line is measurable next to work
    // this app is already fighting to keep under the GPU watchdog.
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    struct tm tmv;
    localtime_r(&tv.tv_sec, &tmv);
    char stamp[32];
    strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &tmv);

    if (g_log_file != nullptr) {
        // Exact shape of LogStore's lines (timestamp  [source]  message) so the
        // Logs tab parses it back on relaunch; a bare line is dropped on reload.
        fprintf(g_log_file, "%s.%03d  [native]  %s\n", stamp, (int)(tv.tv_usec / 1000), body);
        fflush(g_log_file);
    }
    // Live mirror. logNative adds the timestamp and the [native] tag itself, so
    // this carries only the source-qualified message, never a second timestamp.
    // DEBUG/VERBOSE stay file-only: the sampler can emit them in bursts, and a
    // main-actor hop per line is too much to add to a run already racing the GPU
    // watchdog. The file still has every line.
    if (level >= SD_LOG_INFO) {
        PocketLLMNativeLog(body);
    }
}

// Read-write here, read-only in the header: the public surface cannot mutate an
// image, but the bridge must be able to fill one in.
@interface SdImage ()
@property (nonatomic, readwrite) int32_t width;
@property (nonatomic, readwrite) int32_t height;
@property (nonatomic, readwrite) int32_t channel;
@property (nonatomic, readwrite, copy) NSData *pixels;
@end

@implementation SdImage
@end

@implementation SdContext {
    sd_ctx_t *_ctx;
}

+ (void)setLogPath:(NSString *)path {
    if (g_log_file != nullptr) {
        fclose(g_log_file);
        g_log_file = nullptr;
    }
    if (path.length == 0) return;
    g_log_file = fopen(path.UTF8String, "a");
}

// The string tables are indexed by their enum, so the array order *is* the
// value the picker's index carries into generatePrompt. Reading the count from
// the enum (not sizeof) keeps the two in step if the engine adds a method.
+ (NSArray<NSString *> *)sampleMethodNames {
    NSMutableArray<NSString *> *names = [NSMutableArray arrayWithCapacity:(NSUInteger)SAMPLE_METHOD_COUNT];
    for (int i = 0; i < (int)SAMPLE_METHOD_COUNT; i++) {
        [names addObject:[NSString stringWithUTF8String:sample_method_to_str[i]]];
    }
    return names;
}

+ (NSArray<NSString *> *)schedulerNames {
    NSMutableArray<NSString *> *names = [NSMutableArray arrayWithCapacity:(NSUInteger)SCHEDULER_COUNT];
    for (int i = 0; i < (int)SCHEDULER_COUNT; i++) {
        [names addObject:[NSString stringWithUTF8String:scheduler_to_str[i]]];
    }
    return names;
}

+ (void)configureMetalCommandBuffers:(int32_t)nCB
                              fusion:(BOOL)fusion
                         concurrency:(BOOL)concurrency {
    // setenv/unsetenv, not just setenv: unsetting restores the backend default
    // when the user turns a feature back on, rather than leaving a stale "1".
    if (nCB > 0) {
        setenv("GGML_METAL_N_CB", std::to_string(nCB).c_str(), 1);
    } else {
        unsetenv("GGML_METAL_N_CB");
    }
    if (fusion) {
        unsetenv("GGML_METAL_FUSION_DISABLE");
    } else {
        setenv("GGML_METAL_FUSION_DISABLE", "1", 1);
    }
    if (concurrency) {
        unsetenv("GGML_METAL_CONCURRENCY_DISABLE");
    } else {
        setenv("GGML_METAL_CONCURRENCY_DISABLE", "1", 1);
    }
}

- (nullable instancetype)initWithModel:(nullable NSString *)model
                                 clipL:(nullable NSString *)clipL
                                 clipG:(nullable NSString *)clipG
                            clipVision:(nullable NSString *)clipVision
                                    t5:(nullable NSString *)t5
                                   llm:(nullable NSString *)llm
                             diffusion:(nullable NSString *)diffusion
                                   vae:(nullable NSString *)vae
                                 taesd:(nullable NSString *)taesd
                               threads:(int32_t)threads
                                 error:(NSError * _Nullable * _Nullable)error {
    self = [super init];
    if (!self) return nil;

    // Input paths are copied into local strings so the UTF-8 pointers stay
    // valid for the whole new_sd_ctx call.
    std::string sModel = model ? model.UTF8String : "";
    std::string sClipL = clipL ? clipL.UTF8String : "";
    std::string sClipG = clipG ? clipG.UTF8String : "";
    std::string sClipV = clipVision ? clipVision.UTF8String : "";
    std::string sT5 = t5 ? t5.UTF8String : "";
    std::string sLlm = llm ? llm.UTF8String : "";
    std::string sDiff = diffusion ? diffusion.UTF8String : "";
    std::string sVae = vae ? vae.UTF8String : "";
    std::string sTaesd = taesd ? taesd.UTF8String : "";

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    // The text encoder (Anima's 0.6B LLM, or Qwen2.5-VL for Qwen-Image) stays on
    // the CPU: reserving its graph on the GPU aborted inside
    // ggml_backend_sched_reserve_size. The diffusion model and VAE, which dominate
    // the runtime, go to Metal -- which is why the app now frees the LLM and its
    // Metal context before this call (ComputeCoordinator), so the two ggml-Metal
    // copies never coexist. params_backend is left at its default so auto-fit
    // places those weights; the engine logs that plan (see sd_log_cb), which is
    // what identifies a step that is slow because its weights are being staged
    // instead of staying resident.
    p.backend = "te=cpu";

    if (!sModel.empty())     p.model_path = sModel.c_str();
    if (!sClipL.empty())     p.clip_l_path = sClipL.c_str();
    if (!sClipG.empty())     p.clip_g_path = sClipG.c_str();
    if (!sClipV.empty())     p.clip_vision_path = sClipV.c_str();
    if (!sT5.empty())        p.t5xxl_path = sT5.c_str();
    // Qwen-Image's encoder is a Qwen2.5-VL LLM, not T5, so it goes in llm_path.
    if (!sLlm.empty())       p.llm_path = sLlm.c_str();
    if (!sDiff.empty())      p.diffusion_model_path = sDiff.c_str();
    if (!sVae.empty())       p.vae_path = sVae.c_str();
    if (!sTaesd.empty())     p.taesd_path = sTaesd.c_str();
    if (threads > 0)         p.n_threads = threads;
    // wtype is left at sd_ctx_params_init's default on purpose: forcing F16
    // breaks quantized checkpoints, which is how these models usually ship.

    sd_set_progress_callback(progress_cb, nullptr);

    // Before load, so any abort during new_sd_ctx is captured too.
    sd_set_log_callback(sd_log_cb, nullptr);

    _ctx = new_sd_ctx(&p);
    if (_ctx == nullptr) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdContext"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"new_sd_ctx failed — check that the text encoder, main model and VAE slots match the checkpoint"}];
        }
        return nil;
    }
    return self;
}

- (nullable NSArray<SdImage *> *)generatePrompt:(NSString *)prompt
                                       negative:(nullable NSString *)negative
                                          width:(int32_t)width
                                         height:(int32_t)height
                                          steps:(int32_t)steps
                                            cfg:(float)cfg
                              distilledGuidance:(float)distilled
                                           seed:(int64_t)seed
                                          batch:(int32_t)batch
                                   sampleMethod:(int32_t)sampleMethod
                                      scheduler:(int32_t)scheduler
                                       clipSkip:(int32_t)clipSkip
                                      vaeTiling:(BOOL)vaeTiling
                                    vaeTileSize:(int32_t)vaeTileSize
                                          loras:(nullable NSString *)loras
                                          error:(NSError * _Nullable * _Nullable)error {
    if (_ctx == nullptr) return nil;

    std::string sPrompt = prompt.UTF8String;
    std::string sNeg = negative ? negative.UTF8String : "";

    // LoRA strings must outlive generate_image, so they are owned here.
    std::vector<std::string> loraPaths;
    std::vector<sd_lora_t> loraSpecs;
    if (loras != nil && loras.length > 0) {
        for (NSString *part in [loras componentsSeparatedByString:@";"]) {
            if (part.length == 0) continue;
            NSArray<NSString *> *kv = [part componentsSeparatedByString:@"|"];
            loraPaths.emplace_back(kv[0].UTF8String);
            sd_lora_t l{};
            l.is_high_noise = false;
            l.multiplier = kv.count > 1 ? kv[1].floatValue : 1.0f;
            loraSpecs.push_back(l);
        }
    }
    // Point each spec at its owned string now that the vector has settled.
    for (size_t i = 0; i < loraSpecs.size(); ++i) {
        loraSpecs[i].path = loraPaths[i].c_str();
    }

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt = sPrompt.c_str();
    gp.negative_prompt = sNeg.c_str();
    gp.width = width;
    gp.height = height;
    gp.seed = seed;
    gp.batch_count = batch > 0 ? batch : 1;
    gp.clip_skip = clipSkip;
    if (!loraSpecs.empty()) {
        gp.loras = loraSpecs.data();
        gp.lora_count = (uint32_t)loraSpecs.size();
    }

    // VAE tiling: decode in overlapping tiles to cap the VAE's peak compute
    // buffer. target_overlap of 0.5 is the engine's own default (see
    // sd_img_gen_params_init) and the value its docs recommend; the tile size is
    // in image pixels and the engine rounds it down to a VAE-scale multiple.
    if (vaeTiling) {
        gp.vae_tiling_params.enabled = true;
        gp.vae_tiling_params.tile_size_w = vaeTileSize > 0 ? vaeTileSize : 256;
        gp.vae_tiling_params.tile_size_h = vaeTileSize > 0 ? vaeTileSize : 256;
        gp.vae_tiling_params.target_overlap = 0.5f;
    }

    sd_sample_params_init(&gp.sample_params);
    gp.sample_params.sample_method = (sample_method_t)sampleMethod;
    gp.sample_params.scheduler = (scheduler_t)scheduler;
    gp.sample_params.sample_steps = steps;
    gp.sample_params.guidance.txt_cfg = cfg;
    gp.sample_params.guidance.distilled_guidance = distilled;

    g_step.store(0);
    g_steps.store(0);

    sd_image_t *images = nullptr;
    int num = 0;
    if (!generate_image(_ctx, &gp, &images, &num) || images == nullptr || num <= 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdContext"
                                         code:2
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"generate_image produced no output"}];
        }
        return nil;
    }

    NSMutableArray<SdImage *> *out = [NSMutableArray arrayWithCapacity:(NSUInteger)num];
    for (int i = 0; i < num; ++i) {
        sd_image_t img = images[i];
        if (img.data == nullptr) continue;
        size_t bytes = (size_t)img.width * (size_t)img.height * (size_t)img.channel;
        SdImage *wrapped = [[SdImage alloc] init];
        wrapped.width = img.width;
        wrapped.height = img.height;
        wrapped.channel = img.channel;
        wrapped.pixels = [NSData dataWithBytes:img.data length:bytes];
        [out addObject:wrapped];
    }
    // The header is explicit that C API callers must use free_sd_images rather
    // than free(): the struct owns allocations beyond the array itself.
    free_sd_images(images, num);
    return out;
}

- (void)cancel {
    if (_ctx != nullptr) {
        sd_cancel_generation(_ctx, SD_CANCEL_ALL);
    }
}

- (int32_t)progressStep  { return g_step.load(); }
- (int32_t)progressSteps { return g_steps.load(); }

- (void)dealloc {
    if (_ctx != nullptr) {
        free_sd_ctx(_ctx);
        _ctx = nullptr;
    }
}

@end
