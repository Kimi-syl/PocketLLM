package com.pocketllm.server

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory + on-disk log for the server, agent loop, and crash reports.
 *
 * Lines are stored in a [StateFlow] for UI display and also appended to
 * [logFile] under the app's `filesDir` (not visible to file managers). A copy
 * is also written to the public Downloads directory so the user can pull it
 * via a file manager or `adb pull`.
 */
object ServerLog {
    private const val MAX_LINES = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile
    private var appContext: Context? = null

    /** File under filesDir - not user-visible, but survives app restarts. */
    private val logFile: File?
        get() = appContext?.let { File(it.filesDir, "pocketllm.log") }

    /** File under Downloads via MediaStore (Android 10+) or direct write (legacy). */
    private fun downloadsFile(context: Context): java.io.File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+ - no permission needed
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "pocketllm.log")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                val out = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pocketllm.log")
                out.parentFile?.mkdirs()
                out  // We'll use the MediaStore uri to write
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir?.let { File(it, "pocketllm.log") }
        }
    }

    /**
     * Exports the current log to the public Downloads folder. Returns a
     * user-visible path or null on failure.
     */
    fun exportToDownloads(context: Context): String? {
        val contents = buildString {
            appendLine("PocketLLM log (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            appendLine("---")
            for (line in _lines.value) appendLine(line)
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "pocketllm.log")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return@runCatching null
                context.contentResolver.openOutputStream(uri)?.use { it.write(contents.toByteArray()) }
                    ?: return@runCatching null
                "Download: pocketllm.log"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val out = File(dir, "pocketllm.log")
                out.parentFile?.mkdirs()
                out.writeText(contents)
                out.absolutePath
            }
        }.getOrNull()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $message"
        synchronized(this) {
            _lines.value = (_lines.value + line).takeLast(MAX_LINES)
            runCatching {
                logFile?.appendText("$line\n")
            }
        }
    }

    fun error(tag: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        log("[$tag] ${throwable.message}\n$sw")
    }

    /** Returns the full log file path, or null if not initialized. */
    fun logFilePath(): String? = logFile?.absolutePath

    private fun writeToFiles(line: String) {
        runCatching {
            logFile?.appendText("$line\n")
        }
    }
}
