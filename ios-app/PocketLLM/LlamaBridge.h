#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// ObjC++ wrapper over llama.cpp for on-device inference on iOS.
/// Mirrors the Android JNI bridge (llama_jni.cpp): one context per model,
/// serialized generation, cooperative stop.

#if defined(__cplusplus)
extern "C" {
#endif
BOOL LlamaSupportsGpuOffload(void);
void LlamaGlobalInit(void);
#if defined(__cplusplus)
}
#endif

@interface LlamaContext : NSObject

/// Lets Swift hand the bridge the exact path of the app log, so the native
/// writer cannot diverge from the one the Logs tab reads.
+ (void)setLogPath:(NSString *)path;

- (nullable instancetype)initWithModelPath:(NSString *)path
                               contextSize:(int32_t)contextSize
                                 batchSize:(int32_t)batchSize
                                   threads:(int)threads
                                nGpuLayers:(int32_t)nGpuLayers
                    error:(NSError * _Nullable * _Nullable)error;

/// messages: array of @[role, content] pairs; returns the rendered prompt.
- (nullable NSString *)applyTemplate:(NSArray<NSArray<NSString *> *> *)messages;

- (void)generate:(NSString *)prompt
       maxTokens:(int)maxTokens
      temperature:(float)temperature
             topP:(float)topP
             topK:(int)topK
          onToken:(void (^)(NSString *piece))onToken
       completion:(void (^)(int promptTokens, int generatedTokens, BOOL stopped))completion;

/// Loads the mmproj (projection) file that gives a text model vision, the
/// llama.cpp equivalent of sd's text encoder. Mirrors loadVision in llama_jni.
/// The placeholder mtmd substitutes for the image tokens (e.g. <__media__>).
/// Needed on the Swift side so the marker can be placed inside the user turn
/// before the chat template is applied.
+ (NSString *)visionMarker;

- (BOOL)loadVisionWithMmprojPath:(NSString *)path error:(NSError * _Nullable * _Nullable)error;

/// YES when an mmproj was loaded and reports image support.
@property (nonatomic, readonly) BOOL supportsVision;

/// Runs one image+text turn and streams the answer. The prompt is prefixed with
/// the engine's image marker so the bitmap lands where the model expects it.
/// Tokens evaluated for the last image prompt, including the image tokens.
/// A value barely larger than the text suggests the bitmap was never spliced in.
@property (nonatomic, readonly) NSInteger lastVisionTokens;

- (void)describeImage:(NSString *)imagePath
               prompt:(NSString *)prompt
            maxTokens:(int)maxTokens
              onToken:(void (^)(NSString *piece))onToken
           completion:(void (^)(BOOL ok, NSString * _Nullable error))completion;

- (void)stop;
- (int32_t)contextLength;

/// YES when the model was loaded with its layers offloaded to the GPU.
@property (nonatomic, readonly) BOOL usingGpu;

@end

NS_ASSUME_NONNULL_END
