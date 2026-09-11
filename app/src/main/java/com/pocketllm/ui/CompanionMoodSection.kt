package com.pocketllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explicit mood check-ins only. Nothing is inferred from what you say in
 * passing — she asks, you tap a face, and that is the record.
 */
@Composable
fun CompanionMoodSection(vm: AppViewModel) {
    val entries by vm.moodEntries.collectAsState()
    val summary by vm.moodSummary.collectAsState()
    val trend by vm.moodTrend.collectAsState()

    LaunchedEffect(Unit) { vm.refreshMood() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How you've been", style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Text(
                "Tap “How am I?” in the bubble to check in. She uses this only to be " +
                    "a little gentler after a hard stretch.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (trend.any { it != null }) {
                Text("Last two weeks", style = MaterialTheme.typography.bodySmall)
                MoodTrendChart(trend)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "2 weeks ago",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (entries.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.bodySmall)
                val formatter = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                entries.takeLast(8).reversed().forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            com.pocketllm.companion.MoodScale.face(entry.score),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "  ${com.pocketllm.companion.MoodScale.label(entry.score)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatter.format(Date(entry.at)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.clearMoodJournal() }) {
                        Text("Forget the journal")
                    }
                }
            }
        }
    }
}

/**
 * One bar per day, taller for a better day, with empty days left as a low stub.
 *
 * Deliberately unlabelled: this is a glance at how a fortnight has gone, not
 * something to read precise numbers from.
 */
@Composable
private fun MoodTrendChart(trend: List<Double?>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        trend.forEach { value ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    // A floor keeps days without a check-in visible as a
                    // baseline, so the row always reads as two weeks.
                    .fillMaxHeight(if (value == null) 0.06f else (value / 5.0).toFloat().coerceIn(0.1f, 1f))
                    .background(
                        if (value == null) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}
