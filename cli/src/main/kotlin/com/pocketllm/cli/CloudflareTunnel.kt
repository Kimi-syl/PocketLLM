package com.pocketllm.cli

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Puts the local OpenAI-compatible server behind a Cloudflare Tunnel.
 *
 * Why a tunnel rather than the self-signed certificate the Android app ships:
 * a self-signed CA can only ever work after the client is told to trust it, and
 * every browser and HTTP client will keep reporting it as unsafe (curl needs
 * `-k`, the OpenAI SDKs need a custom trust store, iOS ATS needs an exception).
 * A tunnel terminates TLS on Cloudflare's edge with a publicly trusted
 * certificate, so `https://<name>.trycloudflare.com` works in curl, in the
 * official SDKs and in a browser with no flags.
 *
 * The trade is the opposite of the certificate's: a quick tunnel is a *public*
 * URL, not a LAN-only one. Whoever learns the hostname can reach the server, so
 * [serve] refuses to open one without an API key.
 */
internal object CloudflareTunnel {

    /** Quick tunnels are `https://<three-random-words>.trycloudflare.com`. */
    private val QUICK_URL = Regex("""https://[a-z0-9][a-z0-9-]*\.trycloudflare\.com""")

    private const val STARTUP_TIMEOUT_SECONDS = 45L

    /** A running tunnel; [url] is the public base URL, e.g. `https://x.trycloudflare.com`. */
    class Handle(private val process: Process, val url: String) : AutoCloseable {
        override fun close() {
            // cloudflared handles SIGTERM by closing its connections politely.
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /**
     * Finds `cloudflared`: `POCKETLLM_CLOUDFLARED` first, then `PATH`.
     * Returns null when it is not installed, so callers can print [installHint].
     */
    fun binary(): String? {
        System.getenv("POCKETLLM_CLOUDFLARED")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val exeName = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "cloudflared.exe" else "cloudflared"
        return System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it, exeName) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }

    fun installHint(): String = """
        cloudflared was not found in PATH.

        Install it from
          https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/

          Linux x86_64 : https://github.com/cloudflare/cloudflared/releases/latest
                         (cloudflared-linux-amd64, then: chmod +x cloudflared)
          Linux arm64  : same release, cloudflared-linux-arm64
          Termux       : pkg install cloudflared
          macOS        : brew install cloudflared

        Or set POCKETLLM_CLOUDFLARED=/path/to/cloudflared
    """.trimIndent()

    /**
     * Starts a tunnel in front of [localUrl] and blocks until the public
     * hostname is known.
     *
     * @param namedTunnel when set, runs an already-configured named tunnel
     *        (`cloudflared tunnel run <name>`); otherwise a quick tunnel is used,
     *        which needs no Cloudflare account but gets a random hostname that
     *        changes on every start.
     * @param log receives cloudflared's own output, so its errors stay visible.
     */
    fun start(localUrl: String, namedTunnel: String?, log: (String) -> Unit): Handle {
        val exe = binary() ?: error(installHint())
        val command = buildList {
            add(exe)
            add("tunnel")
            if (namedTunnel != null) {
                add("run")
                add(namedTunnel)
            } else {
                add("--url")
                add(localUrl)
                // The CLI may be run from a throwaway container; do not let
                // cloudflared try to replace itself underneath us.
                add("--no-autoupdate")
            }
        }
        log("Starting tunnel: ${command.joinToString(" ")}")

        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            error("could not start cloudflared: ${e.message}")
        }

        val url = AtomicReference<String?>(null)
        val discovered = CountDownLatch(1)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    log(line)
                    if (url.get() == null) {
                        QUICK_URL.find(line)?.let {
                            url.set(it.value)
                            discovered.countDown()
                        }
                    }
                }
            } catch (_: Exception) {
                // Stream closed: either the tunnel died or we closed it.
            } finally {
                discovered.countDown()
            }
        }.apply {
            isDaemon = true
            name = "cloudflared-log"
            start()
        }

        if (!discovered.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) ||
            url.get() == null ||
            !process.isAlive
        ) {
            runCatching { process.destroyForcibly() }
            reader.interrupt()
            error(
                if (namedTunnel != null) {
                    "named tunnel '$namedTunnel' did not come up; check 'cloudflared tunnel list' and " +
                        "that the tunnel's config routes to $localUrl"
                } else {
                    "quick tunnel did not produce a hostname within ${STARTUP_TIMEOUT_SECONDS}s"
                }
            )
        }
        return Handle(process, url.get()!!)
    }
}
