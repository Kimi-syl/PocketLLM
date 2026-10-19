#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// One generated image, raw interleaved pixels.
@interface SdImage : NSObject
@property (nonatomic, readonly) int32_t width;
@property (nonatomic, readonly) int32_t height;
@property (nonatomic, readonly) int32_t channel;
@property (nonatomic, readonly, copy) NSData *pixels;
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
                                          loras:(nullable NSString *)loras
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

NS_ASSUME_NONNULL_END
