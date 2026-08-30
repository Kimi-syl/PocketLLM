package com.pocketllm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.server.ServerLog
import com.pocketllm.ui.AppRoot
import com.pocketllm.ui.theme.PocketLLMTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install crash handler FIRST so any crash before AppViewModel is alive
        // is still captured. ServerLog.init must happen before the first log().
        ServerLog.init(applicationContext)
        installCrashHandler()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.currentSettings.collectAsState()

            // File picker launcher — observes the ViewModel's pickFileRequest
            // and launches the system document picker when it ticks.
            val pickFile = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) vm.onFilePicked(uri)
            }
            val requestTick by vm.pickFileRequest.collectAsState()
            LaunchedEffect(requestTick) {
                if (requestTick > 0L) {
                    runCatching { pickFile.launch(arrayOf("*/*")) }
                }
            }

            PocketLLMTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppRoot(vm)
            }
        }
    }

    /**
     * Installs an uncaught exception handler that:
     *  1. Logs the full stack trace to filesDir/pocketllm.log (synchronous)
     *  2. Copies the log to a timestamped crash report in filesDir/crash_reports/
     *  3. Tries to flush a copy to public Downloads via MediaStore
     *  4. Shows a Toast naming the exception class
     *  5. Re-throws to the system (so Android shows the "app crashed" dialog)
     *
     * Even if the process dies before (3) completes, the filesDir copy survives
     * and can be pulled via adb.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ServerLog.error("UNCAUGHT on ${thread.name}", throwable)
                writeCrashReport(throwable)
            } catch (_: Throwable) {}
            try {
                val ctx = applicationContext
                // Try MediaStore export (Android 10+) for user-visible crash report.
                val exported = ServerLog.exportToDownloads(ctx)
                val msg = if (exported != null) {
                    "Crashed: ${throwable.javaClass.simpleName}\nLog: $exported"
                } else {
                    "Crashed: ${throwable.javaClass.simpleName}\nLog in app's filesDir/pocketllm.log"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(throwable: Throwable) {
        val crashDir = File(filesDir, "crash_reports").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(crashDir, "crash_$ts.txt")
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        runCatching {
            out.writeText(buildString {
                appendLine("PocketLLM crash report")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("Thread: ${Thread.currentThread().name}")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine("Stack trace:")
                appendLine(sw.toString())
                appendLine()
                appendLine("--- Last 200 log lines ---")
                for (line in ServerLog.lines.value.takeLast(200)) appendLine(line)
            })
        }
    }
}
