package com.pocketllm.server

/**
 * Platform-neutral logging facade for core code.
 *
 * Android's [ServerLog] (app module) registers itself as the sink at startup,
 * so all core log lines land in the UI ring buffer and on-disk log. On JVM
 * (CLI) and iOS the default sink prints to stdout.
 *
 * The sink is registered once at startup before any core code logs, so plain
 * fields are used instead of atomics (which differ per platform).
 */
object PLog {
    var sink: (String) -> Unit = { line -> println(line) }
    var enabled: Boolean = true

    fun log(message: String) {
        if (enabled) runCatching { sink(message) }
    }

    fun error(where: String, e: Throwable) {
        log("ERROR in $where: ${e.message ?: e::class.simpleName}")
    }
}
