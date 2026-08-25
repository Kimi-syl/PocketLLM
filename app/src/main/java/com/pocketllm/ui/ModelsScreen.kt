package com.pocketllm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.llm.EngineState
import com.pocketllm.models.GgufModel

@Composable
fun ModelsScreen(vm: AppViewModel, onMenu: () -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("On device") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Hugging Face") })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) LocalModelsList(vm, onMenu) else HfSearchSection(vm)
        }
    }
}

@Composable
private fun LocalModelsList(vm: AppViewModel, onMenu: () -> Unit = {}) {
    val models by vm.models.collectAsState()
    val engineState by vm.engine.state.collectAsState()
    val loadedFile = vm.loadedFileName()

    LaunchedEffect(engineState) {
        if (engineState is EngineState.Ready || engineState is EngineState.Empty) vm.refreshModels()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Models", onMenu) }
        item {
            val stateLabel = when (val s = engineState) {
                is EngineState.Loading -> "Loading ${s.fileName}…"
                is EngineState.Ready -> "Loaded: ${s.modelName} (${s.contextSize} ctx)"
                is EngineState.Error -> s.message
                EngineState.Empty -> "No model loaded"
            }
            Text(stateLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(models, key = { it.name }) { model ->
            ModelRow(
                model = model,
                isLoaded = loadedFile == model.name,
                onLoad = { vm.loadModel(model.name) },
                onUnload = { vm.unloadModel() },
                onDelete = { vm.deleteModel(model.name) },
                busy = engineState is EngineState.Loading,
            )
        }
        if (models.isEmpty()) {
            item {
                Text(
                    "No .gguf models found.\nUse the Hugging Face tab to search and download one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: GgufModel,
    isLoaded: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatSize(model.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLoaded) {
                TextButton(onClick = onUnload) { Text("Unload") }
            } else {
                Button(onClick = onLoad, enabled = !busy) { Text("Load") }
            }
            IconButton(onClick = onDelete, enabled = !isLoaded && !busy) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun HfSearchSection(vm: AppViewModel) {
    val searchResults by vm.searchResults.collectAsState()
    val searchLoading by vm.searchLoading.collectAsState()
    val fileListing by vm.fileListing.collectAsState()
    val filesLoading by vm.filesLoading.collectAsState()
    val models by vm.models.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()

    var query by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var expandedRepo by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search GGUF models") },
                placeholder = { Text("e.g. Qwen2.5 3B instruct") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { vm.searchHuggingFace(query) }, enabled = query.isNotBlank() && !searchLoading) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searchLoading) CircularProgressIndicator(Modifier.padding(4.dp))
            }
        }

        items(searchResults, key = { it.repoId }) { result ->
            val expanded = expandedRepo == result.repoId
            Card(Modifier.fillMaxWidth().clickable {
                if (expanded) {
                    expandedRepo = null
                    vm.clearRepoFiles()
                } else {
                    expandedRepo = result.repoId
                    vm.loadRepoFiles(result.repoId)
                }
            }) {
                Column(Modifier.padding(12.dp)) {
                    Text(result.repoId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${result.downloads} downloads · ${result.likes} likes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        val listing = fileListing?.takeIf { it.first == result.repoId }
                        when {
                            filesLoading && listing == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(4.dp))
                                Text("Loading files…", style = MaterialTheme.typography.bodySmall)
                            }
                            listing != null && listing.second.isEmpty() ->
                                Text("No GGUF files in this repo", style = MaterialTheme.typography.bodySmall)
                            listing != null -> listing.second.forEach { entry ->
                                FileDownloadRow(
                                    fileName = entry.path.substringAfterLast('/'),
                                    sizeBytes = entry.realSize,
                                    alreadyOnDevice = models.any { it.name == entry.path.substringAfterLast('/') },
                                    downloadEnabled = downloadProgress == null,
                                    onDownload = {
                                        vm.downloadModel(
                                            "https://huggingface.co/${result.repoId}/resolve/main/${entry.path}"
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (searchResults.isEmpty() && !searchLoading) {
            item {
                Text(
                    "Search results appear here. You can also paste a direct link:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            OutlinedTextField(
                value = directUrl,
                onValueChange = { directUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Direct GGUF URL") },
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = {
                    vm.downloadModel(directUrl)
                    directUrl = ""
                },
                enabled = directUrl.isNotBlank() && downloadProgress == null,
            ) { Text("Download URL") }
        }

        downloadProgress?.let { progress ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { vm.cancelDownload() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDownloadRow(
    fileName: String,
    sizeBytes: Long,
    alreadyOnDevice: Boolean,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(fileName, style = MaterialTheme.typography.bodySmall)
            Text(
                formatSize(sizeBytes) + if (alreadyOnDevice) " · on device" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!alreadyOnDevice) {
            IconButton(onClick = onDownload, enabled = downloadEnabled) {
                Icon(Icons.Outlined.Download, contentDescription = "Download")
            }
        }
    }
}

internal fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.2f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.1f MB".format(mb)
}
