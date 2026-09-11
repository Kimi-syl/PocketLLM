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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketllm.AppViewModel

/**
 * Reminders she is holding. Just the list and a way to cancel — they are
 * created by talking to her ("remind me to call mum in 20 minutes"), which is
 * the point of a companion.
 */
@Composable
fun CompanionRemindersSection(vm: AppViewModel) {
    val reminders by vm.reminders.collectAsState()
    val context = LocalContext.current

    // The list lives on disk, so pick up anything set while the app was away.
    LaunchedEffect(Unit) { vm.refreshReminders() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reminders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ask her in the bubble — “remind me to call mum in 20 minutes”.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (reminders.isEmpty()) {
                Text("Nothing scheduled.", style = MaterialTheme.typography.bodySmall)
            }

            reminders.forEach { reminder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(reminder.subject, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            com.pocketllm.companion.ReminderScheduler.describe(reminder.triggerAt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { vm.cancelReminder(context, reminder.id) }) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
