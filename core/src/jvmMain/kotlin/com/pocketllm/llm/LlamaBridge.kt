package com.pocketllm.llm

fun interface TokenSink {
    fun onToken(chunk: ByteArray): Boolean
}

object LlamaBridge {
    @Volatile
    var loadError: Throwable? = null
        private set

    init {
        try {
            val explicit = System.getenv("POCKETLLM_NATIVE_LIB")
            if (explicit != null) {
                System.load(explicit)
            } else {
                runCatching { System.loadLibrary("c++_shared") }
                runCatching { System.loadLibrary("OpenCLshim") }
                runCatching { System.loadLibrary("vkshim") }
                runCatching { System.loadLibrary("vulkan_freedreno") }
                loadPocketLlm()
            }
        } catch (t: Throwable) {
            loadError = t
        }
    }

    /** True when the DotProd/FP16 kernel build is the one that got loaded. */
    @Volatile
    var optimizedKernels: Boolean = false
        private set

    /**
     * Loads the CPU-optimised kernel build only when the device really has both
     * DotProd and FP16, otherwise the ARMv8-A baseline. Both ship in the APK:
     *
     *   libpocketllm.so      baseline, runs on every arm64 device
     *   libpocketllm_v82.so  -march=armv8.2-a+dotprod+fp16 (SDOT/UDOT, fp16)
     *
     * The optimised build emits SDOT/UDOT unconditionally, so loading it on a
     * Cortex-A53-class core (no DotProd) would raise SIGILL. Two guards prevent
     * that: the /proc/cpuinfo feature gate, and the try/catch around the actual
     * dlopen (covers a missing or ABI-mismatched library too). Only ever one of
     * the two is loaded per process.
     */
    private fun loadPocketLlm() {
        val f = CpuInfo.features()
        if (f.dotProd && f.fp16) {
            if (runCatching { System.loadLibrary("pocketllm_v82") }.isSuccess) {
                optimizedKernels = true
                return
            }
            // fall through to the baseline on any load failure
        }
        System.loadLibrary("pocketllm")
    }

    val isAvailable: Boolean
        get() = loadError == null

    fun safeSupportsGpuOffload(): Boolean {
        if (loadError != null) return false
        return try {
            supportsGpuOffload()
        } catch (_: Throwable) {
            false
        }
    }

    fun safeBackendInfo(): String {
        if (loadError != null) return "Native backend not available (${loadError?.message ?: "library load error"})"
        return try {
            backendInfo()
        } catch (t: Throwable) {
            "Backend error: ${t.message}"
        }
    }

    external fun backendInit()

    external fun backendInfo(): String
    external fun supportsGpuOffload(): Boolean
    external fun loadModel(path: String, contextSize: Int, batchSize: Int, threads: Int, gpuLayers: Int): Long
    external fun freeModel(handle: Long)
    external fun contextLength(handle: Long): Int
    external fun applyChatTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String
    external fun generate(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Long,
        grammar: String?,
        sink: TokenSink,
    ): IntArray?

    external fun stopGeneration(handle: Long)
}
