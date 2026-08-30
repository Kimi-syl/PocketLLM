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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install crash handler FIRST so any crash before AppViewModel is alive
        // is still captured. ServerLog.init must happen before the first log().
        ServerLog.init(applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ServerLog.error("UNCAUGHT on ${thread.name}", throwable)
            } catch (_: Throwable) {}
            // Show a toast so the user sees something even if the log is hidden
            try {
                Toast.makeText(
                    applicationContext,
                    "PocketLLM crashed: ${throwable.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }

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
}
