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
                                 error:(NSError * _Nullable * _Nullable)error;

/// loras: "path|multiplier;path|multiplier", or nil. Each LoRA is applied to the
/// generation, matching the Android side's sd_lora_t[] handling.
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
                                          error:(NSError * _Nullable * _Nullable)error;

- (void)cancel;

/// Routes the engine's own log (including ggml's abort reason) into `path`,
/// appending. Called before load so the message survives a crash.
- (void)setLogFile:(NSString *)path;

/// Polled from Swift on a timer while generation runs.
@property (nonatomic, readonly) int32_t progressStep;
@property (nonatomic, readonly) int32_t progressSteps;

@end

NS_ASSUME_NONNULL_END
