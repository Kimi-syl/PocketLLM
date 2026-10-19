#import "SdBridge.h"

#include <atomic>
#include <cstdio>
#include <string>
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
static FILE * g_log_file = nullptr;

static void sd_log_cb(enum sd_log_level_t /*level*/, const char * text, void * /*data*/) {
    if (text == nullptr || g_log_file == nullptr) return;
    fputs(text, g_log_file);
    fflush(g_log_file);
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

- (void)setLogFile:(NSString *)path {
    if (g_log_file != nullptr) {
        fclose(g_log_file);
        g_log_file = nullptr;
    }
    if (path.length == 0) return;
    g_log_file = fopen(path.UTF8String, "a");
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
    // copies never coexist. params_backend is left at its default so those
    // weights are placed on the compute (GPU) backend.
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
