package com.pocketllm.img

/**
 * Thin JNI wrapper over stable-diffusion.cpp (libsdcpp.so).
 *
 * The engine lives in its own shared library so its bundled ggml never
 * interposes against llama.cpp's. Generation runs on a caller-supplied
 * background thread; progress is polled via [nativeProgress] rather than
 * pushed through a JNI callback.
 */
object SdBridge {

    @Volatile
    var loadError: Throwable? = null
        private set

    init {
        try {
            System.loadLibrary("sdcpp")
            nativeInit()
        } catch (t: Throwable) {
            loadError = t
        }
    }

    val isAvailable: Boolean
        get() = loadError == null

    private external fun nativeInit()
    external fun nativeProgress(): IntArray
    external fun nativeSampleMethods(): Array<String>
    external fun nativeSchedulers(): Array<String>

    external fun nativeCreate(
        model: String?,
        clipL: String?,
        clipG: String?,
        clipV: String?,
        t5: String?,
        diffusion: String?,
        vae: String?,
        taesd: String?,
        backend: String?,
        nThreads: Int,
        wtype: Int,
    ): Long

    external fun nativeFree(ctx: Long)
    external fun nativeCancel(ctx: Long)

    /** Returns packed `[w, h, channel, count]` + pixel blobs, or null on failure. */
    external fun nativeGenerate(
        ctx: Long,
        prompt: String,
        negative: String?,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        distilled: Float,
        seed: Long,
        batch: Int,
        sampleMethod: Int,
        scheduler: Int,
        clipSkip: Int,
    ): ByteArray?
}
