#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// One generated image, raw interleaved pixels.
@interface SdImage : NSObject
@property (nonatomic, readonly) int32_t width;
@property (nonatomic, readonly) int32_t height;
@property (nonatomic, readonly) int32_t channel;
@property (nonatomic, readonly, copy) NSData *pixels;

/// Builds an image from raw interleaved pixels; imports into Swift as
/// `SdImage(width:height:channel:pixels:)`. The upscaler needs an input image to
/// hand to the engine, and the read-only properties cannot be filled otherwise.
+ (instancetype)imageWithWidth:(int32_t)width
                        height:(int32_t)height
                       channel:(int32_t)channel
                        pixels:(NSData *)pixels;
@end

/// ObjC++ wrapper over stable-diffusion.cpp for on-device image generation.
///
/// Mirrors the Android JNI bridge (sd_jni.cpp) so both platforms take the same
/// model layout: text encoder + main model + VAE + optional LoRAs, all GGUF.
/// Progress is published as counters and polled from Swift rather than pushed
/// through a callback, which keeps the sampler thread from re-entering Swift.
@interface SdContext : NSObject

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
                                 error:(NSError * _Nullable * _Nullable)error;

/// loras: "path|multiplier;path|multiplier", or nil. Each LoRA is applied to the
/// generation, matching the Android side's sd_lora_t[] handling.
///
/// hires: after the base pass the latents are upscaled to
/// hiresTargetW x hiresTargetH and re-denoised for hiresSteps at
/// hiresStrength, all inside the same call. hiresTargetW/H of 0 fall back to the
/// engine's own scale-based sizing.
///
/// img2img: `initImage` becomes the starting point and `strength` (0-1) is how
/// much of it to replace — 1 ignores it, low values keep it. Pass nil for
/// text-to-image. The engine resizes the image to width/height itself.
///
/// vaeTiling decodes the VAE in overlapping tiles rather than in one pass, which
/// lowers the VAE's peak compute buffer; vaeTileSize is the tile edge in image
/// pixels (the engine rounds it down to a VAE-scale multiple). Ignored when
/// vaeTiling is NO.
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
                                          error:(NSError * _Nullable * _Nullable)error;

/// Decodes latents the engine dumped (`SD_LATENTS_DUMP`) without sampling again.
///
/// The public generate path always samples first, so this is what makes an
/// external decode failure (the MLX VAE) cheap: the same picture comes out of
/// the engine's own decoder instead of a second sampling pass. `vaeSpace`
/// selects the ".vae" sibling, whose values are already scaled/shifted for the
/// VAE; NO reads the raw sampler latents. Returns one image, or nil on failure.
- (nullable NSArray<SdImage *> *)decodeLatents:(NSString *)path
                                      vaeSpace:(BOOL)vaeSpace
                                        tiling:(BOOL)tiling
                                      tileSize:(int32_t)tileSize
                                         error:(NSError * _Nullable * _Nullable)error;

- (void)cancel;

/// Routes the engine's own log (including ggml's abort reason) into `path`,
/// appending. A class method, not an instance one, because `new_sd_ctx` is what
/// aborts: the file must be open *before* the context is created, or the reason
/// the load or the first Metal graph dies is written to nowhere.
+ (void)setLogPath:(NSString *)path;

/// The engine's own sampler and scheduler tables, in enum order, so the UI can
/// offer exactly what the build supports instead of a hard-coded subset. The
/// index into each array is the value `sampleMethod`/`scheduler` take. Mirrors
/// the Android bridge's `nativeSampleMethods`/`nativeSchedulers`.
+ (NSArray<NSString *> *)sampleMethodNames;
+ (NSArray<NSString *> *)schedulerNames;

/// Where a `mlxVae:YES` run writes its final latents for the Swift MLX decoder.
/// Separate files (`…`, `….1`, `…`) are written when batch > 1.
+ (NSString *)latentsDumpPath;

/// Sets the environment knobs the vendored ggml-Metal backend reads once, at
/// backend creation, so this must be called *before* the context is created.
/// nCB <= 0 keeps the backend default; fusion/concurrency only ever turn a
/// feature off (the defaults are on). Exposed because a full-resolution
/// diffusion graph can outrun what those defaults were tuned for.
+ (void)configureMetalCommandBuffers:(int32_t)nCB
                              fusion:(BOOL)fusion
                         concurrency:(BOOL)concurrency;

/// Polled from Swift on a timer while generation runs.
@property (nonatomic, readonly) int32_t progressStep;
@property (nonatomic, readonly) int32_t progressSteps;

@end

/// Standalone ESRGAN upscaler.
///
/// Deliberately separate from `SdContext`: enlarging a finished image must not
/// require reloading the diffusion model, and the engine's upscaler owns only
/// the ESRGAN weights. `tileSize` bounds the graph the backend sees, which is
/// what keeps a 4x pass on a large canvas inside the GPU watchdog.
@interface SdUpscaler : NSObject

- (nullable instancetype)initWithModel:(NSString *)model
                              tileSize:(int32_t)tileSize
                               threads:(int32_t)threads
                                useGPU:(BOOL)useGPU
                                 error:(NSError * _Nullable * _Nullable)error;

/// The model's own scale (usually 4). `factor` is accepted for the C API but
/// this engine ignores it: the ratio is baked into the ESRGAN weights.
- (nullable NSArray<SdImage *> *)upscaleImage:(SdImage *)image
                                       factor:(int32_t)factor
                                        error:(NSError * _Nullable * _Nullable)error;

/// 0 when the file is not a recognised RGB ESRGAN model, otherwise its scale.
+ (int32_t)modelScale:(NSString *)model;

@end

NS_ASSUME_NONNULL_END
