package com.pocketllm.util

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real command execution for the in-app Linux sandbox.
 *
 * The sandbox is an Alpine rootfs executed under PRoot (userspace chroot +
 * syscall translation), which is what lets an unrooted Android app run a real
 * `python3`, `pip`, `node` and friends inside its own data directory.
 *
 * Nothing here is simulated: if the rootfs or the proot binary is missing the
 * call returns an error naming exactly what is absent, instead of printing
 * canned output.
 *
 * Layout:
 *   files/alpine           the extracted rootfs (bin/sh must exist)
 *   files/bin/proot        the proot binary, or libproot.so in the native dir
 *   files/sandbox          the shared workspace, mounted at /workspace
 */
class SandboxManager(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "alpine")
    private val workspace: File = File(context.filesDir, "sandbox")

    data class Status(
        val rootfsInstalled: Boolean,
        val prootAvailable: Boolean,
        val rootfsPath: String,
    ) {
        val ready: Boolean get() = rootfsInstalled && prootAvailable
    }

    fun status(): Status = Status(
        rootfsInstalled = File(rootfsDir, "bin/sh").exists(),
        prootAvailable = prootBinary() != null,
        rootfsPath = rootfsDir.absolutePath,
    )

    /**
     * PRoot is looked for as a JNI library first (Android only executes files
     * under the app's native lib dir reliably) and then as a plain binary.
     */
    private fun prootBinary(): File? = listOf(
        File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
        File(context.filesDir, "bin/proot"),
    ).firstOrNull { it.exists() }

    /** Runs [command] inside the rootfs. Returns combined stdout/stderr. */
    suspend fun run(command: String, timeoutSec: Int = 30): Result<String> =
        withContext(Dispatchers.IO) {
            if (command.isBlank()) return@withContext Result.failure(IllegalArgumentException("empty command"))
            val proot = prootBinary() ?: return@withContext Result.failure(
                IllegalStateException(
                    "proot 未安裝。請將 proot 二進位放到 files/bin/proot，" +
                        "或作為 libproot.so 打包進 jniLibs。"
                )
            )
            if (!File(rootfsDir, "bin/sh").exists()) return@withContext Result.failure(
                IllegalStateException("Alpine rootfs 未安裝（${rootfsDir.absolutePath} 缺少 bin/sh）。")
            )
            workspace.mkdirs()

            val argv = buildList {
                add(proot.absolutePath)
                add("-r"); add(rootfsDir.absolutePath)
                add("-0")                       // pretend to be root inside the rootfs
                add("-w"); add("/root")
                add("-b"); add("/dev")
                add("-b"); add("/proc")
                add("-b"); add("/sys")
                add("-b"); add("${workspace.absolutePath}:/workspace")
                add("/bin/sh"); add("-lc"); add(command)
            }

            try {
                val pb = ProcessBuilder(argv)
                    .directory(workspace)
                    .redirectErrorStream(true)
                // Some kernels need seccomp disabled for PRoot to start.
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
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
     * Extracts an Alpine minirootfs tarball (tar.gz) into [rootfsDir] using the
     * platform tar. The user supplies the tarball; we never fetch one silently.
     */
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
