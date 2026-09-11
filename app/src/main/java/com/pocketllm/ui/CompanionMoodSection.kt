package com.pocketllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
