package com.pocketllm.util

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real command execution for the in-app Linux sandbox.
 *
 * Everything needed is bundled with the APK, so the sandbox works on first run
 * without the user supplying anything:
 *
 *   jniLibs/arm64-v8a/libproot.so         PRoot binary (Termux build, bionic, loader-less)
 *   jniLibs/arm64-v8a/libtalloc.so        PRoot dependency
 *   jniLibs/arm64-v8a/libandroid-shmem.so PRoot dependency
 *   assets/alpine-minirootfs.tar.gz       Alpine minirootfs, extracted on first use
 *
 * `jniLibs` is the only location Android lets an app execute a binary from
 * (app data is noexec), hence the lib*.so naming. The rootfs is extracted to
 * filesDir/alpine once, on first command.
 *
 * Nothing here is simulated: if a piece is genuinely missing the call returns
 * an error naming it.
 */
class SandboxManager(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "alpine")
    private val workspace: File = File(context.filesDir, "sandbox")
    private val tmpDir: File = File(context.filesDir, "tmp")

    /**
     * AGP may transparently gunzip a `.tar.gz` asset, landing it in the APK as
     * `.tar`; accept either name and sniff the gzip magic before extracting.
     */
    private val assetNames = listOf("alpine-minirootfs.tar.gz", "alpine-minirootfs.tar")

    data class Status(
        val rootfsInstalled: Boolean,
        val prootAvailable: Boolean,
        val rootfsPath: String,
        val canAutoInstall: Boolean,
    ) {
        val ready: Boolean get() = rootfsInstalled && prootAvailable
    }

    fun status(): Status = Status(
        rootfsInstalled = File(rootfsDir, "bin/sh").exists(),
        prootAvailable = prootBinary() != null,
        rootfsPath = rootfsDir.absolutePath,
        canAutoInstall = firstAsset() != null,
    )

    private fun assetExists(name: String): Boolean = runCatching {
        context.assets.open(name).use { }
        true
    }.getOrDefault(false)

    private fun firstAsset(): String? = assetNames.firstOrNull { assetExists(it) }

    /** True when the bundled APK carries a rootfs we can extract. */
    fun canAutoInstall(): Boolean = firstAsset() != null

    private fun prootBinary(): File? =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").takeIf { it.exists() }

    /**
     * Writes /etc/resolv.conf inside the rootfs from Android's own DNS servers
     * (net.dns1/2), falling back to public resolvers. Without this, `apk add`
     * cannot resolve any host and package installation fails.
     */
    private fun writeResolvConf() {
        val servers = linkedSetOf<String>()
        runCatching {
            val proc = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Regex("net\\.dns\\d?\\s*$").findAll(out)
            for (line in out.lineSequence()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2 && parts[0].startsWith("net.dns") &&
                    parts[1].matches(Regex("[0-9a-fA-F:.]+"))
                ) servers += parts[1]
            }
        }
        if (servers.isEmpty()) {
            servers += "1.1.1.1"
            servers += "8.8.8.8"
        }
        val etc = File(rootfsDir, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    /**
     * Extracts the bundled Alpine rootfs on first use. Idempotent and safe to
     * call before every command.
     */
    suspend fun ensureInstalled(): Result<Unit> = withContext(Dispatchers.IO) {
        if (File(rootfsDir, "bin/sh").exists()) return@withContext Result.success(Unit)
        val asset = firstAsset() ?: return@withContext Result.failure(
            IllegalStateException("APK 內缺少 rootfs 資產（${assetNames.joinToString()}）")
        )
        val tar = File("/system/bin/tar")
        if (!tar.exists()) return@withContext Result.failure(
            IllegalStateException("系統缺少 tar，無法解壓 rootfs")
        )
        rootfsDir.mkdirs()
        val staged = File(context.cacheDir, "alpine-rootfs.stage")
        try {
            context.assets.open(asset).use { input ->
                staged.outputStream().use { out -> input.copyTo(out) }
            }
            // The asset may be gzipped or a plain tar depending on how AGP
            // handled it; sniff the magic bytes rather than trusting the name.
            val magic = staged.inputStream().use { s ->
                val b = ByteArray(2); s.read(b); b
            }
            val gzipped = magic.size == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
            val args = if (gzipped) {
                listOf(tar.absolutePath, "-xzf", staged.absolutePath, "-C", rootfsDir.absolutePath)
            } else {
                listOf(tar.absolutePath, "-xf", staged.absolutePath, "-C", rootfsDir.absolutePath)
            }
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            staged.delete()
            if (code == 0 && File(rootfsDir, "bin/sh").exists()) {
                writeResolvConf()
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("解壓 rootfs 失敗（exit $code）：${out.take(300)}"))
            }
        } catch (t: Throwable) {
            staged.delete()
            Result.failure(t)
        }
    }

    /** Runs [command] inside the rootfs. Returns combined stdout/stderr. */
    suspend fun run(
        command: String,
        timeoutSec: Int = 30,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): Result<String> =
        withContext(Dispatchers.IO) {
            if (command.isBlank()) return@withContext Result.failure(IllegalArgumentException("empty command"))
            val proot = prootBinary() ?: return@withContext Result.failure(
                IllegalStateException("APK 內缺少 libproot.so")
            )
            ensureInstalled().onFailure { return@withContext Result.failure(it) }
            workspace.mkdirs()
            tmpDir.mkdirs()

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val argv = buildList {
                add(proot.absolutePath)
                add("-r"); add(rootfsDir.absolutePath)
                add("-0")                       // pretend to be root inside the rootfs
                add("-w"); add("/root")
                add("-b"); add("/dev")
                add("-b"); add("/proc")
                add("-b"); add("/sys")
                add("-b"); add("${workspace.absolutePath}:/workspace")
                for ((host, guest) in extraBinds) {
                    add("-b"); add("$host:$guest")
                }
                add("/bin/sh"); add("-lc"); add(command)
            }

            try {
                val pb = ProcessBuilder(argv)
                    .directory(workspace)
                    .redirectErrorStream(true)
                val env = pb.environment()
                env["PROOT_TMP_DIR"] = tmpDir.absolutePath
                env["PROOT_NO_SECCOMP"] = "1"
                // Bundled libtalloc / libandroid-shmem live in the native lib dir.
                env["LD_LIBRARY_PATH"] = nativeDir
                env["HOME"] = "/root"
                env["TERM"] = "xterm-256color"
                env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

                val proc = pb.start()
                val sb = StringBuilder()
                val reader = Thread {
                    runCatching {
                        proc.inputStream.bufferedReader().forEachLine { l ->
                            if (sb.length < 20_000) sb.appendLine(l)
                        }
                    }
                }.also { it.isDaemon = true; it.start() }

                val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext Result.failure(IllegalStateException("命令逾時（${timeoutSec}s）"))
                }
                reader.join(1_500)
                Result.success(sb.toString().ifBlank { "(no output, exit ${proc.exitValue()})" })
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    /**
     * Converts a HuggingFace checkpoint directory (config.json + tokenizer.json
     * + *.safetensors) into a GGUF file, on device, using the bundled torch-free
     * converter. The models directory is bind-mounted at /models.
     *
     * @return the absolute path of the produced GGUF on success.
     */
    suspend fun convertToGguf(
        modelsDir: File,
        modelDirName: String,
        outName: String,
        onLine: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        workspace.mkdirs()
        val script = File(workspace, "convert_llama.py")
        runCatching {
            context.assets.open("convert/convert_llama.py").use { input ->
                script.outputStream().use { out -> input.copyTo(out) }
            }
        }.onFailure { return@withContext Result.failure(it) }

        val cmd = buildString {
            append("apk add --no-cache py3-numpy >/dev/null 2>&1 || true; ")
            append("python3 /workspace/convert_llama.py ")
            append("/models/").append(shellQuote(modelDirName)).append(' ')
            append("/models/").append(shellQuote(outName))
        }
        val r = run(
            command = cmd,
            timeoutSec = 1800,
            extraBinds = listOf(modelsDir.absolutePath to "/models"),
        )
        r.onSuccess { onLine(it) }
        val outFile = File(modelsDir, outName)
        if (r.isSuccess && outFile.length() > 0) {
            Result.success(outFile.absolutePath)
        } else {
            Result.failure(
                IllegalStateException(r.exceptionOrNull()?.message ?: "轉換未產生輸出檔")
            )
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Extracts a user-supplied rootfs tarball into [rootfsDir]. */
    suspend fun installFromTarball(tarGz: File): Result<Unit> = withContext(Dispatchers.IO) {
        if (!tarGz.exists()) return@withContext Result.failure(
            IllegalStateException("找不到檔案：${tarGz.absolutePath}")
        )
        val tar = File("/system/bin/tar")
        if (!tar.exists()) return@withContext Result.failure(
            IllegalStateException("系統缺少 tar，無法解壓 rootfs。")
        )
        rootfsDir.mkdirs()
        try {
            val pb = ProcessBuilder(
                tar.absolutePath, "-xzf", tarGz.absolutePath, "-C", rootfsDir.absolutePath,
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && File(rootfsDir, "bin/sh").exists()) Result.success(Unit)
            else Result.failure(IllegalStateException("tar 失敗（exit $code）：${out.take(300)}"))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
