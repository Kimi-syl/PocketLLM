// JNI bridge for on-device Stable Diffusion (stable-diffusion.cpp).
//
// Built as its own shared library (libsdcpp.so) with -fvisibility=hidden so the
// ggml symbols it links statically stay local: the main libpocketllm.so already
// ships its own ggml through llama.cpp, and two globally-visible ggml copies
// would interpose against each other at dlopen time. Only the JNI entry points
// below are exported (JNIEXPORT forces default visibility).
//
// Progress is published to atomics and polled from Kotlin rather than invoking
// a Java callback from the sampler thread: that avoids AttachCurrentThread and
// any chance of a JNI global-ref leak on cancel.

#include <jni.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "stable-diffusion.h"

namespace {

std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};

void progress_cb(int step, int steps, float /*time*/, void* /*data*/) {
    g_step.store(step, std::memory_order_relaxed);
    g_steps.store(steps, std::memory_order_relaxed);
}

// Keeps UTF-8 copies of the Java strings alive across the native call, so the
// C API's const char* fields never dangle when the JNI ref is released.
struct UtfHolder {
    JNIEnv* env;
    std::vector<std::string> storage;

    const char* get(jstring js) {
        if (js == nullptr) return nullptr;
        const char* raw = env->GetStringUTFChars(js, nullptr);
        if (raw == nullptr) return nullptr;
        storage.emplace_back(raw);
        env->ReleaseStringUTFChars(js, raw);
        return storage.back().empty() ? nullptr : storage.back().c_str();
    }
};

const char* opt(const char* s) {
    return (s != nullptr && s[0] != '\0') ? s : nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pocketllm_img_SdBridge_nativeInit(JNIEnv*, jclass) {
    sd_set_progress_callback(progress_cb, nullptr);
}

JNIEXPORT jintArray JNICALL
Java_com_pocketllm_img_SdBridge_nativeProgress(JNIEnv* env, jclass) {
    jint out[2] = {g_step.load(std::memory_order_relaxed),
                   g_steps.load(std::memory_order_relaxed)};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, out);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_pocketllm_img_SdBridge_nativeSampleMethods(JNIEnv* env, jclass) {
    const int n = SAMPLE_METHOD_COUNT;
    jobjectArray arr = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; i++) {
        jstring s = env->NewStringUTF(sample_method_to_str[i]);
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_pocketllm_img_SdBridge_nativeSchedulers(JNIEnv* env, jclass) {
    const int n = SCHEDULER_COUNT;
    jobjectArray arr = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; i++) {
        jstring s = env->NewStringUTF(scheduler_to_str[i]);
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_pocketllm_img_SdBridge_nativeCreate(
    JNIEnv* env, jclass,
    jstring jModel, jstring jClipL, jstring jClipG, jstring jClipV,
    jstring jT5, jstring jDiffusion, jstring jVae, jstring jTaesd,
    jstring jBackend, jint nThreads, jint wtype) {
    UtfHolder h{env, {}};
    const char* model     = opt(h.get(jModel));
    const char* clip_l    = opt(h.get(jClipL));
    const char* clip_g    = opt(h.get(jClipG));
    const char* clip_v    = opt(h.get(jClipV));
    const char* t5        = opt(h.get(jT5));
    const char* diffusion = opt(h.get(jDiffusion));
    const char* vae       = opt(h.get(jVae));
    const char* taesd     = opt(h.get(jTaesd));
    const char* backend   = opt(h.get(jBackend));

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path               = model;
    p.clip_l_path              = clip_l;
    p.clip_g_path              = clip_g;
    p.clip_vision_path         = clip_v;
    p.t5xxl_path               = t5;
    p.diffusion_model_path     = diffusion;
    p.vae_path                 = vae;
    p.taesd_path               = taesd;
    p.n_threads                = nThreads > 0 ? nThreads : 4;
    p.wtype                    = static_cast<sd_type_t>(wtype);
    p.enable_mmap              = true;
    p.backend                  = backend;

    return reinterpret_cast<jlong>(new_sd_ctx(&p));
}

JNIEXPORT void JNICALL
Java_com_pocketllm_img_SdBridge_nativeFree(JNIEnv*, jclass, jlong jctx) {
    auto* ctx = reinterpret_cast<sd_ctx_t*>(jctx);
    if (ctx != nullptr) free_sd_ctx(ctx);
}

JNIEXPORT void JNICALL
Java_com_pocketllm_img_SdBridge_nativeCancel(JNIEnv*, jclass, jlong jctx) {
    auto* ctx = reinterpret_cast<sd_ctx_t*>(jctx);
    if (ctx != nullptr) sd_cancel_generation(ctx, SD_CANCEL_ALL);
}

/**
 * Returns a packed result: [w, h, channel, count] as 4 little-endian ints,
 * then `count` contiguous w*h*channel pixel blobs (RGBA order). Null when the
 * generation failed or was cancelled before producing anything.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_pocketllm_img_SdBridge_nativeGenerate(
    JNIEnv* env, jclass, jlong jctx,
    jstring jPrompt, jstring jNegative,
    jint width, jint height, jint steps, jfloat cfg, jfloat distilled,
    jlong seed, jint batch, jint sampleMethod, jint scheduler, jint clipSkip) {
    auto* ctx = reinterpret_cast<sd_ctx_t*>(jctx);
    if (ctx == nullptr) return nullptr;

    UtfHolder h{env, {}};
    const char* prompt    = h.get(jPrompt);
    const char* negative  = opt(h.get(jNegative));

    if (prompt == nullptr) return nullptr;

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt          = prompt;
    gp.negative_prompt = negative;
    gp.width           = width > 0 ? width : 512;
    gp.height          = height > 0 ? height : 512;
    gp.seed            = seed;
    gp.batch_count     = batch > 0 ? batch : 1;
    gp.clip_skip       = clipSkip;

    sd_sample_params_init(&gp.sample_params);
    gp.sample_params.sample_method      = static_cast<sample_method_t>(sampleMethod);
    gp.sample_params.scheduler          = static_cast<scheduler_t>(scheduler);
    gp.sample_params.sample_steps       = steps > 0 ? steps : 20;
    gp.sample_params.guidance.txt_cfg   = cfg;
    gp.sample_params.guidance.distilled_guidance = distilled;

    g_step.store(0, std::memory_order_relaxed);
    g_steps.store(0, std::memory_order_relaxed);

    sd_image_t* images = nullptr;
    int num            = 0;
    const bool ok      = generate_image(ctx, &gp, &images, &num);
    if (!ok || images == nullptr || num <= 0) {
        if (images != nullptr) {
            for (int i = 0; i < num; i++) free(images[i].data);
            free(images);
        }
        return nullptr;
    }

    const uint32_t w = images[0].width;
    const uint32_t c = images[0].channel;
    const uint32_t hgt = images[0].height;
    const size_t bytes_per_image = static_cast<size_t>(w) * hgt * c;

    std::vector<uint8_t> out;
    out.reserve(16 + bytes_per_image * num);
    auto put_int = [&out](uint32_t v) {
        out.push_back(static_cast<uint8_t>(v & 0xff));
        out.push_back(static_cast<uint8_t>((v >> 8) & 0xff));
        out.push_back(static_cast<uint8_t>((v >> 16) & 0xff));
        out.push_back(static_cast<uint8_t>((v >> 24) & 0xff));
    };
    put_int(w);
    put_int(hgt);
    put_int(c);
    put_int(static_cast<uint32_t>(num));

    for (int i = 0; i < num; i++) {
        if (images[i].data != nullptr &&
            images[i].width == w && images[i].height == hgt && images[i].channel == c) {
            out.insert(out.end(), images[i].data, images[i].data + bytes_per_image);
        } else {
            out.insert(out.end(), bytes_per_image, 0);
        }
        free(images[i].data);
    }
    free(images);

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

}  // extern "C"
