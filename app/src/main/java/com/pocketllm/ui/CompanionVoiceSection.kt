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
import androidx.compose.material3.OutlinedTextField
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
 * Speech-to-text settings for the companion.
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Speech to text", style = MaterialTheme.typography.titleMedium)

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

            // Everything below only matters once the microphone is switched on.
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

                var language by remember(s.companionSpeechLanguage) {
                    mutableStateOf(s.companionSpeechLanguage)
                }
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Recognition language") },
                    placeholder = { Text("e.g. en-US, zh-CN") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { vm.updateCompanionSpeechLanguage(language.trim()) },
                        enabled = language.trim() != s.companionSpeechLanguage,
                    ) { Text("Save language") }
                }
                Text(
                    "A BCP-47 tag such as en-US or zh-CN. Leave blank to use whatever " +
                        "the device is set to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show words as you speak", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Displays recognised text while you are still talking, " +
                                "instead of only at the end.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = s.companionSpeechPartialResults,
                        onCheckedChange = { vm.updateCompanionSpeechPartialResults(it) },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Prefer on-device recognition", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Asks the recognizer to work offline where it can. " +
                                "Some engines ignore this.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = s.companionSpeechPreferOffline,
                        onCheckedChange = { vm.updateCompanionSpeechPreferOffline(it) },
                    )
                }
            }
        }
    }
}
