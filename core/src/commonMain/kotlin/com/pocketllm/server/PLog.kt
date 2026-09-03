package com.pocketllm.server

import kotlin.concurrent.AtomicBoolean
import kotlin.concurrent.AtomicReference

/**
 * Platform-neutral logging facade for core code.
 *
 * Android's [ServerLog] (app module) registers itself as the sink at startup,
 * so all core log lines land in the UI ring buffer and on-disk log. On JVM
 * (CLI) and iOS the default sink prints to stdout.
 */
object PLog {
    private val sinkRef = AtomicReference<(String) -> Unit>({ line -> println(line) })

    var sink: (String) -> Unit
        get() = sinkRef.value
        set(value) { sinkRef.value = value }

    private val enabledFlag = AtomicBoolean(true)

    var enabled: Boolean
        get() = enabledFlag.value
        set(value) { enabledFlag.value = value }

    fun log(message: String) {
        if (enabled) runCatching { sink(message) }
    }

    fun error(where: String, e: Throwable) {
        log("ERROR in $where: ${e.message ?: e::class.simpleName}")
    }
}
