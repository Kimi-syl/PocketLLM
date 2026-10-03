package com.pocketllm.voice

/**
 * Process-wide holder for the latest screen context captured through the
 * assistant role. Written by [PocketAssistSession] on the main thread and read
 * by the agent, so both fields are volatile.
 */
object AssistContextHolder {

    @Volatile
    var serviceReady: Boolean = false

    @Volatile
    var packageName: String? = null

    @Volatile
    var lastContext: String = ""

    @Volatile
    var lastCapturedAt: Long = 0L

    fun update(pkg: String?, context: String) {
        packageName = pkg
        lastContext = context
        lastCapturedAt = System.currentTimeMillis()
    }

    val hasContext: Boolean get() = lastContext.isNotBlank()
}
