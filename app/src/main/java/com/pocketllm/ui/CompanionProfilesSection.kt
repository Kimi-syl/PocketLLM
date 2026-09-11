package com.pocketllm.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import kotlinx.coroutines.launch

/**
 * Bubble colours offered in Settings. Stored as packed ARGB in a Long because
 * that is what [AppViewModel.updateCompanionBubbleColor] persists; 0 is
 * reserved for "follow the theme".
 */
private val BUBBLE_COLORS: List<Pair<String, Long>> = listOf(
    "Theme" to 0L,
    "Rose" to 0xFFE91E63,
    "Violet" to 0xFF7C4DFF,
    "Ocean" to 0xFF0288D1,
    "Mint" to 0xFF00897B,
    "Amber" to 0xFFFFA000,
    "Slate" to 0xFF546E7A,
)

/** A saved companion: apply, delete, and share the library. */
@Composable
fun CompanionProfilesSection(vm: AppViewModel) {
    val profiles by vm.profiles.collectAsState()
    val context = LocalContext.current

    var pendingName by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Companions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save the companion you have tuned, switch between favourites, or " +
                    "share one with someone else.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { pendingName = it },
                    label = { Text("Save current as…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        vm.saveCurrentAsProfile(pendingName)
                        pendingName = ""
                        message = "Saved."
                    }
                ) { Text("Save") }
            }

            profiles.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.glyph, style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            profile.persona.lineSequence().firstOrNull()?.take(60)
                                ?.takeIf { it.isNotBlank() }
                                ?: "warmth ${profile.warmth} · directness ${profile.directness}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = {
                        vm.applyProfile(profile.id)
                        message = "${profile.name} is active."
                    }) { Text("Use") }
                    TextButton(onClick = { vm.deleteProfile(profile.id) }) { Text("Delete") }
                }
            }

            if (profiles.isEmpty()) {
                Text(
                    "Nothing saved yet. Tune her below, then save a copy here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = profiles.isNotEmpty(),
                    onClick = {
                        // Share as text; the receiver can paste it into Import.
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "My PocketLLM companions")
                            putExtra(Intent.EXTRA_TEXT, vm.exportProfiles())
                        }
                        context.startActivity(Intent.createChooser(intent, "Share companions"))
                    },
                ) { Text("Share all") }
            }

            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("Paste shared companions here") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = importText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            message = try {
                                val added = vm.importProfiles(importText)
                                importText = ""
                                if (added == 0) "Those are already saved."
                                else "Imported $added."
                            } catch (e: IllegalArgumentException) {
                                e.message ?: "Could not import that."
                            }
                        }
                    },
                ) { Text("Import") }
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Bubble tint picker. */
@Composable
fun CompanionAppearanceSection(vm: AppViewModel) {
    val s by vm.currentSettings.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bubble colour", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BUBBLE_COLORS.forEach { (label, argb) ->
                    val selected = s.companionBubbleColor == argb
                    val shown = if (argb == 0L) MaterialTheme.colorScheme.primary
                    else Color(argb)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(shown, CircleShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { vm.updateCompanionBubbleColor(argb) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (argb == 0L) {
                            Text("A", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text(
                "“Theme” follows the app colours. Shown as a preview only — the " +
                    "bubble itself updates straight away.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
