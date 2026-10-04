package dev.mohak.scrinium.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.mohak.scrinium.MainActivity
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.ui.isDone

/**
 * Task reminders as phone notifications. Reminders live on the server, so
 * whenever the app loads the task list it hands it here and we keep one
 * exact alarm per future reminder. Nothing polls: an alarm wakes the app
 * once, at reminder time, to post the notification. The schedule is kept in
 * SharedPreferences so a reboot can re-arm it without the network.
 *
 * Reminders set on the web while the phone app is closed are picked up the
 * next time the app opens.
 */
class Reminders(context: Context) {
    private val ctx = context.applicationContext
    private val store = ctx.getSharedPreferences("scrinium_reminders", Context.MODE_PRIVATE)
    private val alarms = ctx.getSystemService(AlarmManager::class.java)

    // The on/off switch in Settings. Kept apart from [store], which holds
    // only the alarm entries.
    private val settings = ctx.getSharedPreferences("scrinium_reminder_settings", Context.MODE_PRIVATE)

    data class Entry(val at: Long, val title: String)

    var enabled: Boolean
        get() = settings.getBoolean("enabled", true)
        set(value) {
            settings.edit().putBoolean("enabled", value).apply()
            if (!value) clearAll()
        }

    /** Alarms waiting to fire, soonest first. */
    fun scheduled(): List<Entry> = stored().values.sortedBy { it.at }

    /** Makes the scheduled alarms match [tasks]' future, not-done reminders. */
    fun sync(tasks: List<TaskDto>) {
        if (!enabled) return clearAll()
        val now = System.currentTimeMillis()
        val want = tasks
            .filter { !it.isDone && it.remindAt != null && it.remindAt > now }
            .associate { it.id to Entry(it.remindAt!!, it.title) }
        val have = stored()
        val edit = store.edit()
        for (id in have.keys - want.keys) {
            alarms.cancel(pending(id, have.getValue(id).title))
            edit.remove(id)
        }
        for ((id, e) in want) {
            if (have[id] == e) continue
            arm(id, e)
            edit.putString(id, "${e.at}\t${e.title}")
        }
        edit.apply()
    }

    /** After a reboot or app update the system drops alarms; set them again. */
    fun rearm() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val edit = store.edit()
        for ((id, e) in stored()) {
            if (e.at > now) arm(id, e) else edit.remove(id)
        }
        edit.apply()
    }

    fun clearAll() {
        for ((id, e) in stored()) alarms.cancel(pending(id, e.title))
        store.edit().clear().apply()
    }

    internal fun fire(id: String, title: String) {
        store.edit().remove(id).apply()
        val open = PendingIntent.getActivity(
            ctx,
            id.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TASK_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Task reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val manager = NotificationManagerCompat.from(ctx)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(id.hashCode(), notification)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun stored(): Map<String, Entry> = store.all.mapNotNull { (id, v) ->
        val parts = (v as? String)?.split('\t', limit = 2) ?: return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        id to Entry(at, parts.getOrElse(1) { "" })
    }.toMap()

    // Exact when allowed (USE_EXACT_ALARM on 13+, granted by default on 12),
    // otherwise the system may batch it by a few minutes.
    private fun arm(id: String, e: Entry) {
        val pi = pending(id, e.title)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pi)
        }
    }

    private fun pending(id: String, title: String): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        id.hashCode(),
        Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(android.net.Uri.fromParts("task", id, null))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL = "reminders"
        internal const val ACTION_FIRE = "dev.mohak.scrinium.REMINDER"
        internal const val EXTRA_ID = "id"
        internal const val EXTRA_TITLE = "title"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}

/** Alarm target: posts the reminder. Not exported, so only our alarms reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_FIRE) return
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        Reminders(context).fire(id, intent.getStringExtra(Reminders.EXTRA_TITLE).orEmpty().ifBlank { "Task reminder" })
    }
}

/** Re-arms stored reminders after a reboot or an app update. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders(context).rearm()
        }
    }
}
