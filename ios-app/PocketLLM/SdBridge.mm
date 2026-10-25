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

+ (instancetype)imageWithWidth:(int32_t)width
                        height:(int32_t)height
                       channel:(int32_t)channel
                        pixels:(NSData *)pixels {
    SdImage *image = [[SdImage alloc] init];
    image.width = width;
    image.height = height;
    image.channel = channel;
    image.pixels = pixels;
    return image;
}

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

+ (NSString *)latentsDumpPath {
    return [NSTemporaryDirectory() stringByAppendingPathComponent:@"pocketllm_latents.bin"];
}

- (void)removeLatentsDump {
    NSString *base = [SdContext latentsDumpPath];
    NSFileManager *fm = [NSFileManager defaultManager];
    [fm removeItemAtPath:base error:nil];
    // The 2D VAE reads the ".vae" sibling the engine writes next to each blob.
    [fm removeItemAtPath:[base stringByAppendingString:@".vae"] error:nil];
    for (int i = 1; i < 64; i++) {
        NSString *path = [base stringByAppendingFormat:@".%d", i];
        [fm removeItemAtPath:path error:nil];
        [fm removeItemAtPath:[path stringByAppendingString:@".vae"] error:nil];
    }
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
                              vaeOnCPU:(BOOL)vaeOnCPU
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
    //
    // The VAE decode is the one component whose graph can outrun the iOS GPU
    // watchdog: it is a single long chain whose cost grows with the canvas, and
    // tiling only helps by multiplying that chain (120 tiles at 1024x576/128px).
    // Moving just the decode to the CPU is the watchdog-proof escape hatch, at
    // the cost of CPU time; the diffusion model stays on Metal either way.
    std::string backendSpec = "te=cpu";
    if (vaeOnCPU) backendSpec += ",vae=cpu";
    p.backend = backendSpec.c_str();

    // Flash attention, forced ON for the diffusion model.
    //
    // Otherwise this is decided by an auto probe *per backend*, and the rule is
    // "every backend of the module must pass, or the module loses it": the
    // diffusion graph is routinely split across Metal and CPU (graph-cut layer
    // splitting), and when the two disagree the model runs without flash
    // attention. That is the worst possible trade here, because without it every
    // attention layer materialises the full [tokens, tokens] score matrix - the
    // single biggest op in a step, and the one that does not fit the ~10 s
    // command-buffer budget the iOS GPU watchdog enforces. The legacy flag takes
    // the same path as `flash_attn_mode = "on"` but only for this module, so the
    // text encoder and the VAE keep whatever the probe says about them.
    p.diffusion_flash_attn = true;

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
                                         mlxVae:(BOOL)mlxVae
                                      initImage:(nullable SdImage *)initImage
                                       strength:(float)strength
                                          hires:(BOOL)hires
                                   hiresTargetW:(int32_t)hiresTargetW
                                   hiresTargetH:(int32_t)hiresTargetH
                                     hiresSteps:(int32_t)hiresSteps
                                   hiresStrength:(float)hiresStrength
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

    // img2img: the engine takes the pixels as-is and resizes them to width x
    // height. `pixels` is kept alive by this method's own strong reference for
    // the duration of the call, which is all generate_image needs.
    if (initImage != nil && initImage.pixels.length > 0) {
        gp.init_image.width = initImage.width;
        gp.init_image.height = initImage.height;
        gp.init_image.channel = initImage.channel;
        gp.init_image.data = (uint8_t *)initImage.pixels.bytes;
        gp.strength = strength;
    }

    // Hires: a second denoise pass on upscaled latents, in the same call. The
    // upscaler is a latent one (no extra model), chosen for resize quality; the
    // engine clamps the strength and ignores the pass entirely if it is NONE.
    if (hires) {
        gp.hires.enabled = true;
        gp.hires.upscaler = SD_HIRES_UPSCALER_LATENT_BICUBIC_ANTIALIASED;
        gp.hires.scale = 0.f;
        gp.hires.target_width = hiresTargetW;
        gp.hires.target_height = hiresTargetH;
        gp.hires.steps = hiresSteps;
        gp.hires.denoising_strength = hiresStrength;
    }

    sd_sample_params_init(&gp.sample_params);
    gp.sample_params.sample_method = (sample_method_t)sampleMethod;
    gp.sample_params.scheduler = (scheduler_t)scheduler;
    gp.sample_params.sample_steps = steps;
    gp.sample_params.guidance.txt_cfg = cfg;
    gp.sample_params.guidance.distilled_guidance = distilled;

    g_step.store(0);
    g_steps.store(0);

    // MLX VAE: the engine writes the final latents and skips its own decode
    // (see the SD_LATENTS_DUMP hunk in pipeline/image.cpp). The dump path is
    // removed first so a stale file from an earlier run cannot be mistaken for
    // this run's output.
    std::string sLatentsPath;
    if (mlxVae) {
        [self removeLatentsDump];
        sLatentsPath = [SdContext latentsDumpPath].UTF8String;
        setenv("SD_LATENTS_DUMP", sLatentsPath.c_str(), 1);
    }

    sd_image_t *images = nullptr;
    int num = 0;
    bool generated = generate_image(_ctx, &gp, &images, &num);
    if (mlxVae) {
        unsetenv("SD_LATENTS_DUMP");
    }
    if (!generated || images == nullptr || (num <= 0 && !mlxVae)) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdContext"
                                         code:2
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"generate_image produced no output"}];
        }
        return nil;
    }
    // With mlxVae there are no images by design; an empty array tells the caller
    // the latents file is the result.
    if (num <= 0) {
        free_sd_images(images, 0);
        return @[];
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

- (NSArray<SdImage *> *)decodeLatents:(NSString *)path
                              vaeSpace:(BOOL)vaeSpace
                                tiling:(BOOL)tiling
                              tileSize:(int32_t)tileSize
                                 error:(NSError * _Nullable * _Nullable)error {
    if (_ctx == nullptr) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdContext"
                                         code:3
                                     userInfo:@{NSLocalizedDescriptionKey: @"no context"}];
        }
        return nil;
    }
    sd_image_t *images = nullptr;
    int num = 0;
    images = sd_decode_latents(_ctx, path.UTF8String, vaeSpace ? true : false,
                              tiling ? true : false, tileSize, &num);
    if (images == nullptr || num <= 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdContext"
                                         code:4
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"sd_decode_latents produced no output"}];
        }
        if (images != nullptr) {
            free_sd_images(images, num);
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
    free_sd_images(images, num);
    return out;
}

- (NSArray<SdImage *> *)generateVideo:(NSString *)prompt
                              negative:(nullable NSString *)negative
                                 width:(int32_t)width
                                height:(int32_t)height
                                frames:(int32_t)frames
                                   fps:(int32_t)fps
                                 steps:(int32_t)steps
                                   cfg:(float)cfg
                             flowShift:(float)flowShift
                                  seed:(int64_t)seed
                             initImage:(nullable SdImage *)initImage
                              strength:(float)strength
                                 error:(NSError * _Nullable * _Nullable)error {
    if (_ctx == nullptr) return nil;

    std::string sPrompt = prompt.UTF8String;
    std::string sNeg = negative ? negative.UTF8String : "";

    sd_vid_gen_params_t vp;
    sd_vid_gen_params_init(&vp);
    vp.prompt = sPrompt.c_str();
    vp.negative_prompt = sNeg.c_str();
    vp.width = width;
    vp.height = height;
    vp.video_frames = frames;
    vp.fps = fps;
    vp.seed = seed;
    if (initImage != nil && initImage.pixels.length > 0) {
        vp.init_image.width = initImage.width;
        vp.init_image.height = initImage.height;
        vp.init_image.channel = initImage.channel;
        vp.init_image.data = (uint8_t *)initImage.pixels.bytes;
        vp.strength = strength;
    }
    // Euler + simple is what the Wan distilled checkpoints are documented
    // against (and what Mirage's bundle uses); both indices are the engine's own
    // tables, resolved by name here rather than hard-coded.
    enum sample_method_t euler = str_to_sample_method("euler");
    vp.sample_params.sample_method = euler < SAMPLE_METHOD_COUNT ? euler : EULER_SAMPLE_METHOD;
    vp.sample_params.scheduler = (scheduler_t)SIMPLE_SCHEDULER;
    vp.sample_params.sample_steps = steps;
    vp.sample_params.guidance.txt_cfg = cfg;
    vp.sample_params.flow_shift = flowShift;

    g_step.store(0);
    g_steps.store(0);

    sd_image_t *outFrames = nullptr;
    int num = 0;
    int fpsOut = 0;
    bool ok = generate_video(_ctx, &vp, &outFrames, &num, nullptr, &fpsOut);
    if (!ok || outFrames == nullptr || num <= 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdContext"
                                         code:5
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"generate_video produced no frames"}];
        }
        if (outFrames != nullptr) {
            free_sd_images(outFrames, num);
        }
        return nil;
    }
    NSMutableArray<SdImage *> *out = [NSMutableArray arrayWithCapacity:(NSUInteger)num];
    for (int i = 0; i < num; ++i) {
        sd_image_t img = outFrames[i];
        if (img.data == nullptr) continue;
        size_t bytes = (size_t)img.width * (size_t)img.height * (size_t)img.channel;
        SdImage *wrapped = [[SdImage alloc] init];
        wrapped.width = img.width;
        wrapped.height = img.height;
        wrapped.channel = img.channel;
        wrapped.pixels = [NSData dataWithBytes:img.data length:bytes];
        [out addObject:wrapped];
    }
    free_sd_images(outFrames, num);
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

@implementation SdUpscaler {
    upscaler_ctx_t *_upscaler;
}

- (nullable instancetype)initWithModel:(NSString *)model
                              tileSize:(int32_t)tileSize
                               threads:(int32_t)threads
                                useGPU:(BOOL)useGPU
                                 error:(NSError * _Nullable * _Nullable)error {
    self = [super init];
    if (!self) return nil;

    std::string sModel = model ? model.UTF8String : "";
    if (sModel.empty()) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdUpscaler"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"no upscaler model selected"}];
        }
        return nil;
    }

    // The upscaler has its own backend manager, so the module spec is enough;
    // params_backend is left null to keep the engine's default placement.
    _upscaler = new_upscaler_ctx(sModel.c_str(),
                                 /*direct=*/false,
                                 threads > 0 ? threads : 4,
                                 tileSize,
                                 useGPU ? "upscaler=metal" : "upscaler=cpu",
                                 nullptr);
    if (_upscaler == nullptr) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdUpscaler"
                                         code:2
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"new_upscaler_ctx failed — is this an ESRGAN .pth/.safetensors model?"}];
        }
        return nil;
    }
    return self;
}

- (nullable NSArray<SdImage *> *)upscaleImage:(SdImage *)image
                                       factor:(int32_t)factor
                                        error:(NSError * _Nullable * _Nullable)error {
    if (_upscaler == nullptr) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdUpscaler" code:3
                                    userInfo:@{NSLocalizedDescriptionKey:
                                                   @"upscaler context is not loaded"}];
        }
        return nil;
    }
    // The engine's ESRGAN path takes RGB only; an RGBA image would be read with
    // the wrong stride.
    NSUInteger expected = (NSUInteger)image.width * (NSUInteger)image.height * 3;
    if (image.width <= 0 || image.height <= 0 || image.channel != 3
        || image.pixels.length < expected) {
        if (error) {
            *error = [NSError errorWithDomain:@"SdUpscaler" code:4
                                    userInfo:@{NSLocalizedDescriptionKey:
                                                   @"upscaler input must be 3-channel RGB"}];
        }
        return nil;
    }

    sd_image_t input;
    input.width = (uint32_t)image.width;
    input.height = (uint32_t)image.height;
    input.channel = 3;
    input.data = (uint8_t *)image.pixels.bytes;

    sd_image_t *images = nullptr;
    int count = 0;
    if (!upscale(_upscaler, input, (uint32_t)MAX(factor, 1), &images, &count) || count <= 0) {
        if (images != nullptr) free_sd_images(images, count);
        if (error) {
            *error = [NSError errorWithDomain:@"SdUpscaler" code:5
                                    userInfo:@{NSLocalizedDescriptionKey:
                                                   @"upscale failed (out of memory, or the model is not ESRGAN)"}];
        }
        return nil;
    }

    NSMutableArray<SdImage *> *out = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
    for (int i = 0; i < count; ++i) {
        sd_image_t img = images[i];
        if (img.data == nullptr) continue;
        size_t bytes = (size_t)img.width * (size_t)img.height * (size_t)img.channel;
        [out addObject:[SdImage imageWithWidth:(int32_t)img.width
                                        height:(int32_t)img.height
                                       channel:(int32_t)img.channel
                                        pixels:[NSData dataWithBytes:img.data length:bytes]]];
    }
    free_sd_images(images, count);
    return out;
}

+ (int32_t)modelScale:(NSString *)model {
    std::string path = model ? model.UTF8String : "";
    return get_upscaler_model_scale(path.c_str());
}

- (void)dealloc {
    if (_upscaler != nullptr) {
        free_upscaler_ctx(_upscaler);
        _upscaler = nullptr;
    }
}

@end
