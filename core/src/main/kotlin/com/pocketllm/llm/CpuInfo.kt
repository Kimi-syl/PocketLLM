package com.pocketllm.llm

import java.io.File

object CpuInfo {

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
