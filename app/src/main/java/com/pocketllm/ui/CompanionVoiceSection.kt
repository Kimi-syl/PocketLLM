package com.pocketllm.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pocketllm.AppViewModel

/**
 * Voice input for the companion.
 *
 * Recording happens in the system recognition service, not in this app, so the
 * permission is only what lets the recognizer accept our requests. It is
 * requested here, from the Activity, because an overlay service cannot show a
 * runtime permission dialog.
 */
@Composable
fun CompanionVoiceSection(vm: AppViewModel) {
    val s by vm.currentSettings.collectAsState()
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Talk instead of typing", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Shows a microphone button in the panel. Speech is handled " +
                            "by your device's recognition service.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = s.companionVoiceInput,
                    enabled = s.companionEnabled,
                    onCheckedChange = { vm.updateCompanionVoiceInput(it) },
                )
            }

            if (s.companionVoiceInput && s.companionEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (granted) "Microphone permission granted"
                        else "Microphone permission needed",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!granted) {
                        TextButton(onClick = {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text("Grant") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speak replies out loud", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Uses the TTS engine configured in the app's voice settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = s.companionTts,
                    enabled = s.companionEnabled,
                    onCheckedChange = { vm.updateCompanionTts(it) },
                )
            }
        }
    }
}
