package com.pocketllm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pocketllm.AppViewModel
import java.util.Locale

/**
 * Proactive check-ins: whether she reaches out on her own, how often, and when
 * to stay quiet.
 *
 * The notification permission prompt lives here too — on Android 13+ it is
 * also what makes the bubble's own ongoing notification visible at all.
 */
@Composable
fun CompanionCheckInSection(vm: AppViewModel) {
    val s by vm.currentSettings.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Either way the setting applies; only visibility changes. */ }

    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val intervals = listOf(30, 60, 120, 240, 480)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check-ins", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reach out sometimes", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "She may send a short hello while you are doing something " +
                            "else. Needs the bubble switched on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = s.companionProactiveNudges,
                    enabled = s.companionEnabled,
                    onCheckedChange = {
                        vm.updateCompanionProactiveNudges(it)
                        if (it) requestNotificationsIfNeeded()
                    },
                )
            }

            if (s.companionProactiveNudges && s.companionEnabled) {
                Text("How often at most", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    intervals.forEach { minutes ->
                        FilterChip(
                            selected = s.companionNudgeIntervalMinutes == minutes,
                            onClick = { vm.updateCompanionNudgeInterval(minutes) },
                            label = {
                                Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h")
                            },
                        )
                    }
                }

                Text("Quiet hours", style = MaterialTheme.typography.bodySmall)
                Text(
                    "She stays silent between these times.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HourStepper(
                    label = "From",
                    hour = s.companionQuietStartHour,
                    onChange = { vm.updateCompanionQuietHours(it, s.companionQuietEndHour) },
                )
                HourStepper(
                    label = "To",
                    hour = s.companionQuietEndHour,
                    onChange = { vm.updateCompanionQuietHours(s.companionQuietStartHour, it) },
                )
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((hour + 23) % 24) }) { Text("-") }
        Text(
            String.format(Locale.US, "%02d:00", hour),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
    }
}
