package com.pocketllm.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketllm.server.ServerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks how hard to compress a picked .vrm, then does it.
 *
 * Shown in two cases: an import that the renderer cannot draw as-is (its largest
 * skin exceeds the joint budget), or the "Compress this .vrm" button, which always
 * offers the choice for a picked file.
 *
 * The preset slider and the bone slider are two views of one decision: picking a
 * preset sets the bone count, and touching the bone slider flips the preset to
 * Custom, because it no longer matches any of them.
 */
@Composable
fun VrmCompressDialog(
    context: android.content.Context,
    bytes: ByteArray,
    onDismiss: () -> Unit,
    onCompressed: (name: String) -> Unit,
    onError: (message: String) -> Unit,
) {
    val stats = remember(bytes) { VrmCompressor.stats(bytes) }
    if (stats == null) {
        // Not a readable GLB; let the plain import path report it properly.
        onError("That file could not be read as a .vrm.")
        return
    }

    var preset by remember { mutableIntStateOf(-1) }          // -1 = Custom
    var shrinkTextures by remember { mutableStateOf(false) }
    var bones by remember {
        mutableFloatStateOf((stats.humanoidBones.coerceAtMost(160)).toFloat())
    }
    var working by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val presetIndexFor = { count: Int ->
        VrmCompressor.PRESETS.indexOfFirst { it.keepBones == count.toInt() }
    }

    suspend fun runCompress() {
        working = true
        phase = "Pruning bones and rebuilding the file…"
        try {
            val keep = bones.toInt().coerceIn(16, stats.humanoidBones)
            val processed = withContext(Dispatchers.Default) {
                VrmCompressor.compress(bytes, keep, shrinkTextures)
            }
            val after = VrmCompressor.stats(processed)
            phase = "Saving…"
            val name = withContext(Dispatchers.IO) {
                VrmLibrary.importProcessed(
                    context,
                    processed,
                    base = "compressed",
                    ext = "vrm",
                    note = "compressed to $keep bones " +
                        "(was ${stats.largestSkin} in the largest skin)",
                )
            }
            if (name != null) onCompressed(name) else onError("The compressed file could not be saved.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onError("Compression failed: ${failure.message}")
        } finally {
            working = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Compress this .vrm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This avatar has ${stats.humanoidBones} humanoid bones; its " +
                        "largest skin has ${stats.largestSkin}. This device can only " +
                        "draw skins up to ${VrmCompressor.THRESHOLD} bones, so a model " +
                        "over that comes out black without compressing.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    "Effort",
                    style = MaterialTheme.typography.labelSmall,
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VrmCompressor.PRESETS.forEachIndexed { index, candidate ->
                        androidx.compose.material3.FilterChip(
                            selected = preset == index,
                            onClick = {
                                preset = index
                                bones = candidate.keepBones.toFloat()
                            },
                            label = { Text(candidate.label) },
                        )
                    }
                    androidx.compose.material3.FilterChip(
                        selected = preset == -1,
                        onClick = { preset = -1 },
                        label = { Text("Custom") },
                    )
                }

                Text(
                    "Bones after compress: ${bones.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = bones,
                    onValueChange = {
                        bones = it
                        if (preset != -1 && presetIndexFor(it.toInt()) != preset) preset = -1
                    },
                    valueRange = 16f..256f,
                    steps = 240,
                )

                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            "Shrink textures to 1024px",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    supportingContent = {
                        Text(
                            "Off keeps images at full size. On halves each edge; " +
                                "on this device large textures can come out black, " +
                                "so turn this on if the avatar does.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    trailingContent = {
                        androidx.compose.material3.Switch(
                            checked = shrinkTextures,
                            onCheckedChange = { shrinkTextures = it },
                        )
                    },
                )

                if (working) {
                    Text(phase, style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { scope.launch { runCompress() } },
                enabled = !working,
            ) { Text("Compress") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { if (!working) onDismiss() },
                enabled = !working,
            ) { Text("Cancel") }
        },
    )
}
