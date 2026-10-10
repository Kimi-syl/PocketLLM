package com.pocketllm.util

import android.content.Context
import android.system.Os
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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
        rootfsDir.mkdirs()
        val staged = File(context.cacheDir, "alpine-rootfs.stage")
        try {
            context.assets.open(asset).use { input ->
                staged.outputStream().use { out -> input.copyTo(out) }
            }
            val magic = staged.inputStream().use { s -> val b = ByteArray(2); s.read(b); b }
            val gzipped = magic.size == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
            extractTar(staged, rootfsDir, gzipped)
            staged.delete()
            if (File(rootfsDir, "bin/sh").exists()) {
                writeResolvConf()
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("解壓後找不到 bin/sh"))
            }
        } catch (t: Throwable) {
            staged.delete()
            Result.failure(t)
        }
    }

    /**
     * Minimal tar extractor that deliberately never restores ownership.
     *
     * The platform tar (toybox) tries to chown extracted entries to their
     * archived uid/gid, which Android's SELinux policy denies for an unprivileged
     * app: `chown ".sys": Operation not permitted`, exit 1, partial rootfs. We
     * own the location and never need root ownership - PRoot fabricates it - so
     * skipping chown entirely is both correct and the reason this works.
     *
     * Handles regular files, directories, symlinks, hardlinks, GNU long names and
     * PAX extended headers. Device/fifo entries are skipped (PRoot binds /dev).
     */
    private fun extractTar(archive: File, dest: File, gzipped: Boolean) {
        val raw = archive.inputStream().buffered()
        val input = if (gzipped) GZIPInputStream(raw, 1 shl 16) else raw
        input.use { stream ->
            val hdr = ByteArray(512)
            var longName: String? = null
            var longLink: String? = null
            var paxPath: String? = null
            var paxSize: Long? = null
            var paxLink: String? = null

            while (true) {
                if (!readFully(stream, hdr)) break
                if (hdr.all { it == 0.toByte() }) break

                var name = cstr(hdr, 0, 100)
                val prefix = cstr(hdr, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                var size = parseOctal(hdr, 124, 12)
                val mode = parseOctal(hdr, 100, 8)
                val type = hdr[156].toInt().toChar()
                var link = cstr(hdr, 157, 100)

                when (type) {
                    'x', 'g' -> {                       // PAX extended header
                        val body = readPayload(stream, size)
                        parsePax(body) { k, v ->
                            when (k) {
                                "path" -> paxPath = v
                                "size" -> paxSize = v.toLongOrNull()
                                "linkpath" -> paxLink = v
                            }
                        }
                        continue
                    }
                    'L' -> {                            // GNU long name
                        longName = readPayload(stream, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                        continue
                    }
                    'K' -> {                            // GNU long link name
                        longLink = readPayload(stream, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                        continue
                    }
                }

                name = longName ?: paxPath ?: name
                longName = null
                paxPath = null
                link = longLink ?: paxLink ?: link
                longLink = null
                paxLink = null
                size = paxSize ?: size
                paxSize = null

                val target = safeResolve(dest, name)
                if (target == null) {
                    skipPayload(stream, size)
                    continue
                }

                when (type) {
                    '5' -> target.mkdirs()
                    '2' -> {                            // symlink
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching { Os.symlink(link, target.absolutePath) }
                            .onFailure {
                                // Symlinks can be refused on scoped storage, so fall
                                // back to a regular copy. Relative targets - Alpine's
                                // bin/sh -> busybox is one - are relative to the LINK's
                                // own directory, not the archive root; resolving them
                                // against dest looked for <rootfs>/busybox and left
                                // bin/sh missing entirely.
                                val src = if (link.startsWith("/")) {
                                    safeResolve(dest, link)
                                } else {
                                    val joined = File(target.parentFile ?: dest, link)
                                        .canonicalFile
                                    if (joined.path.startsWith(dest.canonicalPath + File.separator)) {
                                        joined
                                    } else {
                                        null
                                    }
                                }
                                if (src != null && src.exists()) {
                                    src.copyTo(target, overwrite = true)
                                } else {
                                    // Last resort: recreate the symlink target's
                                    // absence explicitly so the caller reports a real
                                    // missing file rather than a silent no-op.
                                    target.delete()
                                }
                            }
                    }
                    '1' -> {                            // hardlink
                        val src = safeResolve(dest, link)
                        target.parentFile?.mkdirs()
                        if (src != null && src.exists()) src.copyTo(target, overwrite = true)
                    }
                    '0', '\u0000' -> {                  // regular file
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> transfer(stream, out, size) }
                        skipPadding(stream, size)
                        applyMode(target, mode)
                    }
                    else -> skipPayload(stream, size)   // devices, fifos, ...
                }
            }
        }
    }

    private fun safeResolve(dest: File, name: String): File? {
        val cleaned = name.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('/')
        if (parts.any { it == ".." }) return null          // no traversal
        return File(dest, cleaned)
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun transfer(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipPadding(input: java.io.InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) input.skip(pad.toLong())
    }

    private fun skipPayload(input: java.io.InputStream, size: Long) {
        var left = size + ((512 - (size % 512)) % 512)
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) break
            left -= n
        }
    }

    private fun readPayload(input: java.io.InputStream, size: Long): ByteArray {
        val body = ByteArray(size.toInt())
        readFully(input, body)
        skipPadding(input, size)
        return body
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }

    /** PAX records are "LEN key=value\n" with LEN counting the whole record. */
    private inline fun parsePax(body: ByteArray, onEntry: (String, String) -> Unit) {
        var i = 0
        while (i < body.size) {
            var j = i
            while (j < body.size && body[j] != ' '.code.toByte()) j++
            val len = String(body, i, j - i, Charsets.UTF_8).trim().toIntOrNull() ?: break
            if (len <= 0 || i + len > body.size) break
            val record = String(body, i, len, Charsets.UTF_8).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(record.indexOf(' ') + 1, eq)
                onEntry(key, record.substring(eq + 1))
            }
            i += len
        }
    }

    private fun applyMode(f: File, mode: Long) {
        // 0o100 = owner execute. Everything else is left at the default umask.
        if (mode and 0x40L != 0L) f.setExecutable(true, true)
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
        quant: String = "Q4_K_M",
        onLine: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        // Transparency: conversion already done stays done. Re-running it on
        // every load would be the one visible cost the user notices, and the
        // result only depends on the source files, so compare timestamps and
        // short-circuit before the sandbox is even started.
        val cached = File(modelsDir, outName)
        val newestSource = File(modelsDir, modelDirName).walkTopDown()
            .filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
        if (cached.length() > 0 && cached.lastModified() >= newestSource) {
            onLine("使用既有轉換結果：$outName（略過轉換）")
            return@withContext Result.success(cached.absolutePath)
        }

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
            quantizeIfRequested(outFile, quant, onLine)
            Result.success(outFile.absolutePath)
        } else {
            Result.failure(
                IllegalStateException(r.exceptionOrNull()?.message ?: "轉換未產生輸出檔")
            )
        }
    }


    /** llama_ftype values. F16 means "leave the converter's output as-is". */
    private val ftypeFor = mapOf(
        "F16" to -1, "Q8_0" to 7, "Q5_K_M" to 17, "Q4_K_M" to 15,
    )

    /**
     * Re-quantizes the converted GGUF with llama.cpp's quantizer. Doing it here
     * rather than in the Python converter keeps K-quant block formats on the
     * reference implementation.
     */
    private suspend fun quantizeIfRequested(outFile: File, quant: String, onLine: (String) -> Unit) {
        val ftype = ftypeFor[quant] ?: return
        if (ftype < 0) {
            onLine("保持 F16，未量化")
            return
        }
        onLine("量化為 $quant…")
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        val rc = runCatching {
            com.pocketllm.llm.LlamaBridge.quantizeFile(outFile.absolutePath, tmp.absolutePath, ftype)
        }.getOrDefault(-1)
        if (rc == 0 && tmp.length() > 0) {
            outFile.delete()
            tmp.renameTo(outFile)
            onLine("量化完成：$quant（${tmp.length() / 1_048_576} MB）")
        } else {
            tmp.delete()
            onLine("量化失敗（rc=$rc），保留 F16 版本")
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Extracts a user-supplied rootfs tarball into [rootfsDir]. */
    suspend fun installFromTarball(tarGz: File): Result<Unit> = withContext(Dispatchers.IO) {
        if (!tarGz.exists()) return@withContext Result.failure(
            IllegalStateException("找不到檔案：${tarGz.absolutePath}")
        )
        rootfsDir.mkdirs()
        try {
            val magic = tarGz.inputStream().use { s -> val b = ByteArray(2); s.read(b); b }
            val gzipped = magic.size == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
            extractTar(tarGz, rootfsDir, gzipped)
            if (File(rootfsDir, "bin/sh").exists()) {
                writeResolvConf()
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("解壓後找不到 bin/sh"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
