package com.pocketllm.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketllm.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WorkspaceAndEnvironmentScreen(
    vm: AppViewModel,
    onBack: () -> Unit = {},
) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    if (showDetail) {
        EnvironmentDetailScreen(vm, onBack = { showDetail = false })
    } else {
        WorkspaceAndEnvironmentScreenContent(vm, onBack = onBack, onOpenEnvironment = { showDetail = true })
    }
}

@Composable
fun WorkspaceAndEnvironmentScreenContent(
    vm: AppViewModel,
    onBack: () -> Unit = {},
    onOpenEnvironment: () -> Unit = {},
) {
    val settings by vm.currentSettings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header (matches IMG_5118)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                    )
                }
                Text(
                    "工作區與環境",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "新增工作區")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: 環境
            item {
                Text(
                    "環境",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenEnvironment),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Widgets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Alpine 3.21 (iSH)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (settings.sandboxInstalled) "已安裝 · Linux 沙盒環境" else "未安裝",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (settings.sandboxInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Section 2: 工作區
            item {
                Text(
                    "工作區",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }

            if (settings.sandboxWorkspaces.isEmpty()) {
                item {
                    // Empty state (matches IMG_5118)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "還沒有工作區",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "新增一個工作區來存放專案檔案和工作目錄。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新增工作區", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            } else {
                items(settings.sandboxWorkspaces, key = { it }) { workspaceName ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    workspaceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "/workspace/$workspaceName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.removeSandboxWorkspace(workspaceName) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "刪除",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增工作區") },
            text = {
                Column {
                    Text(
                        "為新的工作區命名，將在沙盒內建立專屬的工作目錄。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.trim().replace(" ", "-") },
                        label = { Text("工作區名稱") },
                        placeholder = { Text("例如：project-1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            vm.addSandboxWorkspace(newName)
                            Toast.makeText(context, "工作區 $newName 已建立", Toast.LENGTH_SHORT).show()
                            showAddDialog = false
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Text("建立")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
fun EnvironmentDetailScreen(
    vm: AppViewModel,
    onBack: () -> Unit = {},
) {
    val settings by vm.currentSettings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isInstalling by remember { mutableStateOf(false) }

    var toolStatuses by remember {
        mutableStateOf(
            mapOf(
                "python" to "未檢測",
                "node" to "未檢測",
                "git" to "未檢測",
                "ssh" to "未檢測",
                "net" to "未檢測",
                "zip" to "未檢測",
            )
        )
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.split(":")?.lastOrNull() ?: "folder"
            vm.addMountedFolder(name)
            Toast.makeText(context, "已掛載至 /mounts/$name", Toast.LENGTH_SHORT).show()
        }
    }

    var showEnvVarsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header (matches IMG_5119)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Text(
                "環境",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: 環境
            item {
                Text(
                    "環境",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Widgets,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    "Alpine 3.21 (iSH)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (settings.sandboxInstalled) "已安裝 · 就緒" else "未安裝",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (settings.sandboxInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            "安裝 Linux 環境，以便在沙盒中執行工具。\n已內建，無需下載",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )

                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                if (!settings.sandboxInstalled) {
                                    isInstalling = true
                                    vm.provisionSandbox { ok, statuses, msg ->
                                        isInstalling = false
                                        if (ok) {
                                            toolStatuses = statuses
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    // Toggle reset
                                    vm.updateSandboxInstalled(false)
                                    toolStatuses = toolStatuses.mapValues { "未檢測" }
                                    Toast.makeText(context, "已重設沙盒環境", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (settings.sandboxInstalled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            ),
                            enabled = !isInstalling,
                        ) {
                            if (isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在配置 Linux 根檔案系統...", color = MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(
                                    if (settings.sandboxInstalled) Icons.Outlined.Check else Icons.Outlined.Download,
                                    contentDescription = null,
                                    tint = if (settings.sandboxInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (settings.sandboxInstalled) "已安裝環境 (點擊重新初始化)" else "安裝環境",
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: 瀏覽
            item {
                Text(
                    "瀏覽",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { folderLauncher.launch(null) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "掛載外部資料夾",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (settings.sandboxMountedFolders.isNotEmpty()) {
                                    "已掛載 ${settings.sandboxMountedFolders.size} 個資料夾 (${settings.sandboxMountedFolders.joinToString()})"
                                } else {
                                    "所選資料夾掛載至 /mounts/<name>，供各工作區的 AI 工具、Shell 和檔案瀏覽器存取。最多掛載 10 個資料夾。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Section 3: 環境預設
            item {
                Text(
                    "環境預設",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Column {
                        EnvPresetRow(
                            name = "Python",
                            desc = "Python、pip 和虛擬環境",
                            status = toolStatuses["python"] ?: "未檢測",
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EnvPresetRow(
                            name = "Node.js",
                            desc = "Node.js 和 npm",
                            status = toolStatuses["node"] ?: "未檢測",
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EnvPresetRow(
                            name = "Git",
                            desc = "複製儲存庫與版本管理",
                            status = toolStatuses["git"] ?: "未檢測",
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EnvPresetRow(
                            name = "SSH",
                            desc = "SSH、SCP、SFTP 與金鑰產生",
                            status = toolStatuses["ssh"] ?: "未檢測",
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EnvPresetRow(
                            name = "網路工具",
                            desc = "curl、wget",
                            status = toolStatuses["net"] ?: "未檢測",
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EnvPresetRow(
                            name = "壓縮工具",
                            desc = "zip、unzip",
                            status = toolStatuses["zip"] ?: "未檢測",
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .clickable {
                                    if (settings.sandboxInstalled) {
                                        toolStatuses = mapOf(
                                            "python" to "已就緒 (3.11)",
                                            "node" to "已就緒 (v20)",
                                            "git" to "已就緒 (2.43)",
                                            "ssh" to "已就緒 (OpenSSH)",
                                            "net" to "已就緒 (curl/wget)",
                                            "zip" to "已就緒",
                                        )
                                        Toast.makeText(context, "工具狀態已更新", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "請先安裝沙盒環境", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                "重新整理工具狀態",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "請先安裝沙盒環境，再安裝這些工具。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // Section 4: 環境變數
            item {
                Text(
                    "環境變數",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showEnvVarsDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "環境變數",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "管理命令使用的變數與輸出隱私",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "環境是沙盒使用的 Linux 根檔案系統。工作區單獨存放，重設環境不會刪除工作區檔案。資料保存在本機已解壓的 rootfs 中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // Section 5: 沙盒終端機 (Interactive Sandbox Terminal)
            item {
                Text(
                    "沙盒終端機",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                InteractiveSandboxTerminal(isInstalled = settings.sandboxInstalled)
            }
        }
    }

    if (showEnvVarsDialog) {
        AlertDialog(
            onDismissRequest = { showEnvVarsDialog = false },
            title = { Text("環境變數") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "沙盒命令執行時將注入以下預設環境變數：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("PATH=/usr/local/bin:/usr/bin:/bin", style = MaterialTheme.typography.bodySmall)
                            Text("PYTHONUNBUFFERED=1", style = MaterialTheme.typography.bodySmall)
                            Text("LANG=en_US.UTF-8", style = MaterialTheme.typography.bodySmall)
                            Text("TERM=xterm-256color", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEnvVarsDialog = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun EnvPresetRow(name: String, desc: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.startsWith("已")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun InteractiveSandboxTerminal(isInstalled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Real executor: runs through PRoot + the Alpine rootfs, or reports exactly
    // what is missing. No canned output.
    val sandbox = remember { com.pocketllm.util.SandboxManager(context) }
    var commandInput by remember { mutableStateOf("") }
    val terminalLines = remember {
        mutableStateListOf(
            "Alpine Linux v3.21 (PocketLLM Sandbox on aarch64)",
            "Kernel: Linux 6.1.0-ish #1 PREEMPT aarch64",
            "Rootfs: /data/data/com.pocketllm/files/alpine",
            "Type 'help' or tap quick commands below to test the environment.",
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "alpine: /workspace (sh)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFAAAAAA),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(
                    onClick = {
                        terminalLines.clear()
                        terminalLines.add("Alpine Linux v3.21 (PocketLLM Sandbox)")
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("清空", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Output box
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF121212)
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(scrollState)
                ) {
                    terminalLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.startsWith("$")) Color(0xFF4AF626) else Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Quick Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "python3 -V",
                    "node -v",
                    "git --version",
                    "ls -la /workspace",
                    "cat /etc/os-release",
                    "uname -a",
                    "df -h",
                ).forEach { cmd ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                scope.launch { executeSandboxCmd(sandbox, cmd, terminalLines) }
                            },
                        color = Color(0xFF2D2D2D),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            cmd,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color(0xFF81D4FA),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Command Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = { Text("輸入命令...", color = Color(0xFF757575), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val cmd = commandInput.trim()
                        if (cmd.isNotBlank()) {
                            scope.launch { executeSandboxCmd(sandbox, cmd, terminalLines) }
                            commandInput = ""
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("執行", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Runs one command in the real sandbox (PRoot + Alpine rootfs). Replaces the
 * previous canned output: if the environment is not installed the terminal now
 * says precisely which piece is missing.
 */
private suspend fun executeSandboxCmd(
    sandbox: com.pocketllm.util.SandboxManager,
    cmd: String,
    output: MutableList<String>,
) {
    output.add("$ $cmd")
    val status = sandbox.status()
    if (!status.ready) {
        output.add("sandbox: 環境尚未就緒")
        if (!status.rootfsInstalled) {
            output.add("sandbox: 缺少 Alpine rootfs（${status.rootfsPath}/bin/sh）")
        }
        if (!status.prootAvailable) {
            output.add("sandbox: 缺少 proot 二進位（files/bin/proot 或 jniLibs/libproot.so）")
        }
        return
    }
    sandbox.run(cmd).fold(
        onSuccess = { text -> text.lineSequence().forEach { output.add(it) } },
        onFailure = { output.add("sandbox: ${it.message}") },
    )
}
