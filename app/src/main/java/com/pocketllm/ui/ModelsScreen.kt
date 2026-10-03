package com.pocketllm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Surface
import com.pocketllm.AppViewModel
import com.pocketllm.llm.EngineState
import com.pocketllm.models.GgufModel

@Composable
fun ModelsScreen(vm: AppViewModel, onMenu: () -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }
    val settings by vm.currentSettings.collectAsState()
    var showEnvDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Models", onMenu)

        // Sandbox Environment banner - makes sandbox prominently visible and accessible
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { showEnvDialog = true },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "沙盒環境: Alpine 3.21",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (settings.sandboxInstalled) "• 已就緒" else "• 未安裝",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.sandboxInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "管理環境 >",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("本機模型") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Hugging Face") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("預設模型") })
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> LocalModelsList(vm)
                1 -> HfSearchSection(vm)
                2 -> DefaultModelsContent(vm)
            }
        }
    }

    if (showEnvDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showEnvDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.background,
            ) {
                WorkspaceAndEnvironmentScreen(vm, onBack = { showEnvDialog = false })
            }
        }
    }
}

@Composable
private fun LocalModelsList(vm: AppViewModel) {
    val models by vm.models.collectAsState()
    val engineState by vm.engine.state.collectAsState()
    val loadedFile = vm.loadedFileName()

    LaunchedEffect(engineState) {
        if (engineState is EngineState.Ready || engineState is EngineState.Empty) vm.refreshModels()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            vm.importModel(uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val stateLabel = when (val s = engineState) {
                    is EngineState.Loading -> "Loading ${s.fileName}…"
                    is EngineState.Ready -> "Loaded: ${s.modelName} (${s.contextSize} ctx)"
                    is EngineState.Error -> s.message
                    EngineState.Empty -> "No model loaded"
                }
                Text(
                    stateLabel,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (engineState is EngineState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    enabled = engineState !is EngineState.Loading,
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import")
                }
            }
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
    val downloadingUrl by vm.downloadingUrl.collectAsState()

    var query by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var expandedRepo by remember { mutableStateOf<String?>(null) }
    // True while a download started from the Direct URL field is running, so
    // its progress can render under that field (it has no file row).
    var directDownloadActive by remember { mutableStateOf(false) }

    LaunchedEffect(downloadProgress) {
        if (downloadProgress == null) directDownloadActive = false
    }

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
                                val fileUrl = "https://huggingface.co/${result.repoId}/resolve/main/${entry.path}"
                                FileDownloadRow(
                                    fileName = entry.path.substringAfterLast('/'),
                                    sizeBytes = entry.realSize,
                                    alreadyOnDevice = models.any { it.name == entry.path.substringAfterLast('/') },
                                    downloadEnabled = downloadProgress == null,
                                    // Progress only on the row that owns the running download.
                                    progress = if (downloadingUrl == fileUrl) downloadProgress else null,
                                    onDownload = { vm.downloadModel(fileUrl) },
                                    onCancel = { vm.cancelDownload() },
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
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        vm.downloadModel(directUrl)
                        directDownloadActive = true
                        directUrl = ""
                    },
                    enabled = directUrl.isNotBlank() && downloadProgress == null,
                ) { Text("Download URL") }
                if (directDownloadActive) {
                    downloadProgress?.let { progress ->
                        InlineDownloadProgress(progress = progress, onCancel = { vm.cancelDownload() })
                    }
                }
            }
        }
    }
}

/** Progress bar + cancel, rendered directly under the entry being downloaded. */
@Composable
private fun InlineDownloadProgress(progress: Float, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Downloading… ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun FileDownloadRow(
    fileName: String,
    sizeBytes: Long,
    alreadyOnDevice: Boolean,
    downloadEnabled: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
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
        if (progress != null) {
            InlineDownloadProgress(progress = progress, onCancel = onCancel)
        }
    }
}

internal fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.2f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.1f MB".format(mb)
}
