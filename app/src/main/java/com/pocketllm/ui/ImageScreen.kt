package com.pocketllm.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.img.ImageGenState
import java.io.File

/**
 * On-device Stable Diffusion: prompt in, PNG out.
 *
 * Everything runs locally through libsdcpp.so; the results land in the app's
 * external files dir and are shown here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(vm: AppViewModel, openDrawer: () -> Unit) {
    val state by vm.imageGenState.collectAsState()
    val settings by vm.imageGenSettings.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("圖片生成") },
                navigationIcon = {
                    OutlinedButton(onClick = openDrawer) { Text("選單") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!vm.imageGenAvailable) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("圖片引擎未載入", fontWeight = FontWeight.SemiBold)
                        Text(
                            vm.imageGenError ?: "libsdcpp.so 不存在或載入失敗",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("提示詞", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("a serene mountain lake at sunrise, highly detailed") },
                    )
                    Text("反向提示詞", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = negative,
                        onValueChange = { negative = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("blurry, low quality, watermark") },
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("參數", fontWeight = FontWeight.SemiBold)
                    Text(
                        "尺寸 ${settings.width}×${settings.height} · 步數 ${settings.steps} · CFG ${settings.cfg} · 種子 ${settings.seed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "所有參數可在「設定 → 進階設定 → 圖片生成」調整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.generateImage(prompt, negative) },
                    enabled = vm.imageGenAvailable && state !is ImageGenState.Running &&
                        state !is ImageGenState.Loading && settings.modelPath.isNotBlank(),
                ) { Text("產生圖片") }
                OutlinedButton(
                    onClick = { vm.cancelImageGeneration() },
                    enabled = state is ImageGenState.Running,
                ) { Text("取消") }
            }

            if (settings.modelPath.isBlank() && vm.imageGenAvailable) {
                Text(
                    "尚未設定模型檔。請到「設定 → 進階設定 → 圖片生成」指定 Stable Diffusion 模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val s = state) {
                is ImageGenState.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                }
                is ImageGenState.Running -> {
                    val frac = if (s.steps > 0) s.step.toFloat() / s.steps else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "採樣中 ${s.step}/${s.steps}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is ImageGenState.Failed -> {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is ImageGenState.Done -> {
                    Text("已產生 ${s.images.size} 張", fontWeight = FontWeight.SemiBold)
                    s.images.forEach { img ->
                        ResultImage(img.file)
                    }
                }
                ImageGenState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ResultImage(file: File) {
    val bmp = remember(file.absolutePath) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
        Text(
            "已儲存：${file.absolutePath}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
}
