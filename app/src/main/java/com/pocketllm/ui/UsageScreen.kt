package com.pocketllm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import com.pocketllm.usage.UsageRecord
import com.pocketllm.usage.UsageRepository
import com.pocketllm.usage.UsageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dayStart(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Composable
fun UsageScreen(vm: AppViewModel) {
    val records by vm.usageRecords.collectAsState()

    val today = UsageRepository.aggregate(records, dayStart())
    val week = UsageRepository.aggregate(records, dayStart() - 6L * 24 * 60 * 60 * 1000)
    val all = UsageStats(records.size, records.sumOf { it.promptTokens.toLong() }, records.sumOf { it.completionTokens.toLong() })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Tokens served by your phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.clearUsage() }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear usage")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Today", today, Modifier.weight(1f))
                StatCard("7 days", week, Modifier.weight(1f))
                StatCard("All time", all, Modifier.weight(1f))
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
                    WeeklyChart(records)
                }
            }
        }

        BreakdownCard("By model", UsageRepository.byModel(records), all.totalTokens)
        BreakdownCard("By source", UsageRepository.bySource(records), all.totalTokens)

        item {
            Text("Recent requests", style = MaterialTheme.typography.titleMedium)
        }
        val recent = records.sortedByDescending { it.ts }.take(30)
        if (recent.isEmpty()) {
            item {
                Text(
                    "No requests yet. Chat with a model or point an OpenAI client at the server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(recent.size) { index ->
                val record = recent[index]
                RequestRow(record)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, stats: UsageStats, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatTokens(stats.totalTokens),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${stats.requests} req",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeeklyChart(records: List<UsageRecord>) {
    val daily = UsageRepository.dailyTotals(records, 7)
    val max = daily.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / daily.size
            val barWidth = slot * 0.55f
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(corner, corner),
            )
            daily.forEachIndexed { index, (_, total) ->
                val fraction = (total.toFloat() / max).coerceIn(0.02f, 1f)
                val barHeight = size.height * fraction
                val x = index * slot + (slot - barWidth) / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(corner, corner),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            daily.forEach { (dayStartTs, _) ->
                Text(
                    SimpleDateFormat("EEE", Locale.US).format(Date(dayStartTs)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private fun LazyListScope.BreakdownCard(title: String, breakdown: Map<String, UsageStats>, grandTotal: Long) {
    if (breakdown.isEmpty()) return
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                breakdown.forEach { (name, stats) ->
                    val fraction = if (grandTotal > 0) stats.totalTokens.toFloat() / grandTotal else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                "${formatTokens(stats.totalTokens)} · ${(fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(record: UsageRecord) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    record.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(record.model, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    formatTime(record.ts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "+${record.completionTokens}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "-${record.promptTokens} prompt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ts))
