package com.pocketllm.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketllm.MainActivity
import com.pocketllm.R
import com.pocketllm.server.ServerLog
import com.pocketllm.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a reminder is due and posts the notification.
 *
 * A receiver rather than a timer inside the overlay service, so reminders still
 * arrive when the bubble is switched off or the process has been killed.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_ID) ?: return
        val store = CompanionReminderStore(context)
        val reminder = store.all().firstOrNull { it.id == id }
        if (reminder == null) {
            ServerLog.log("companion: reminder $id fired but is no longer stored")
            return
        }
        postNotification(context, reminder)
        ServerLog.log("companion: reminder fired (${reminder.subject.take(40)})")

        // The notification is already up, so cleanup can happen off the main
        // thread. goAsync() keeps the process alive until it finishes.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // One-shot: drop it so Settings shows what is still pending.
                store.remove(id)
            } catch (e: Exception) {
                ServerLog.log("companion: reminder cleanup failed — ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(context: Context, reminder: CompanionReminder) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Reminders your companion is holding for you" }
            )
        }

        val name = SettingsRepository(context).current().companionName.ifBlank { "Momo" }
        val body = reminder.subject.ifBlank { "You asked me to remind you." }
        val open = PendingIntent.getActivity(
            context, reminder.id.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_companion)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(reminder.id.hashCode(), notification) }
    }

    private companion object {
        const val CHANNEL_ID = "pocketllm_companion_reminders"
    }
}
