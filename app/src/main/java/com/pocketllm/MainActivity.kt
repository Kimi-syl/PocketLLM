package com.pocketllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.ui.AppRoot
import com.pocketllm.ui.theme.PocketLLMTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.currentSettings.collectAsState()
            PocketLLMTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppRoot(vm)
            }
        }
    }
}
