package com.pocketllm.llm

import java.io.File

object CpuInfo {

    /**
     * SIMD capabilities that decide which quantised matmul kernels the CPU
     * backend can run. llama.cpp picks kernels at compile time, so these flags
     * describe what the device could execute; build-native.sh honours
     * POCKETLLM_ARM_ARCH to compile the matching -march variant.
     */
    data class Features(
        val neon: Boolean,
        val fp16: Boolean,
        val dotProd: Boolean,
        val i8mm: Boolean,
        val sve: Boolean,
    ) {
        fun label(): String = buildList {
            if (neon) add("NEON")
            if (fp16) add("FP16")
            if (dotProd) add("DotProd")
            if (i8mm) add("i8mm")
            if (sve) add("SVE")
        }.joinToString(" · ").ifEmpty { "baseline" }
    }

    /** Reads /proc/cpuinfo once; empty flags yield an all-false record. */
    fun features(): Features {
        val flags: Set<String> = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") || it.startsWith("flags") }
                    ?.substringAfter(':', "")
                    ?.lowercase()
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    .orEmpty()
            }
        }.getOrDefault(emptySet())
        return Features(
            neon = "asimd" in flags || "neon" in flags,
            fp16 = "fphp" in flags || "asimdhp" in flags || "f16c" in flags,
            dotProd = "asimddp" in flags || "avx512_vnni" in flags || "avxvnni" in flags,
            i8mm = "i8mm" in flags,
            sve = "sve" in flags || "sve2" in flags,
        )
    }

    fun bigCoreCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until cores).mapNotNull { i ->
            readMaxFreq("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
        }
        if (freqs.size < cores || freqs.isEmpty()) return cores.coerceAtMost(4)
        val maxFreq = freqs.max()
        return freqs.count { it >= maxFreq * 0.8 }.coerceIn(1, 8)
    }

    fun recommendedThreads(): Int = bigCoreCount()

    private fun readMaxFreq(path: String): Long? = runCatching {
        File(path).readText().trim().toLongOrNull()
    }.getOrNull()
}
