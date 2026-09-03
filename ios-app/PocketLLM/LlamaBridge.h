#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// ObjC++ wrapper over llama.cpp for on-device inference on iOS.
/// Mirrors the Android JNI bridge (llama_jni.cpp): one context per model,
/// serialized generation, cooperative stop.

BOOL LlamaSupportsGpuOffload(void);
void LlamaGlobalInit(void);

@interface LlamaContext : NSObject

- (nullable instancetype)initWithModelPath:(NSString *)path
                               contextSize:(int32_t)contextSize
                                 batchSize:(int32_t)batchSize
                                   threads:(int)threads
                                     error:(NSError **)error;

/// messages: array of @[role, content] pairs; returns the rendered prompt.
- (nullable NSString *)applyTemplate:(NSArray<NSArray<NSString *> *> *)messages;

- (void)generate:(NSString *)prompt
       maxTokens:(int)maxTokens
      temperature:(float)temperature
             topP:(float)topP
             topK:(int)topK
          onToken:(void (^)(NSString *piece))onToken
       completion:(void (^)(int promptTokens, int generatedTokens, BOOL stopped))completion;

- (void)stop;
- (int32_t)contextLength;

@end

NS_ASSUME_NONNULL_END
