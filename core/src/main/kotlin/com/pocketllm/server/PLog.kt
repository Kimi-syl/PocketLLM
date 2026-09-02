package com.pocketllm.server

/**
 * Platform-neutral logging facade for core code.
 *
 * Android's [com.pocketllm.server.ServerLog] (app module) registers itself as
 * the sink at startup, so all core log lines land in the UI ring buffer and
 * on-disk log. On JVM (CLI) the default sink prints to stderr.
 */
object PLog {
    @Volatile
    var sink: (line: String) -> Unit = { line -> println(line) }

    @Volatile
    var enabled: Boolean = true

    fun log(message: String) {
        if (enabled) runCatching { sink(message) }
    }

    fun error(where: String, e: Throwable) {
        log("ERROR in $where: ${e.message ?: e.javaClass.simpleName}")
    }
}
