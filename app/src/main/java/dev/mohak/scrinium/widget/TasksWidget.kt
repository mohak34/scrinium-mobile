package dev.mohak.scrinium.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.widget.RemoteViewsCompat
import dev.mohak.scrinium.MainActivity
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.ui.TaskArea
import dev.mohak.scrinium.ui.TaskStatus
import dev.mohak.scrinium.ui.addDays
import dev.mohak.scrinium.ui.isDone
import dev.mohak.scrinium.ui.onMainBoard
import dev.mohak.scrinium.ui.shortDue
import dev.mohak.scrinium.ui.startOfDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen widget: overdue tasks, each of the next seven days, then the
 * rest of the app's "This week" column, with the inbox count below. Tasks are online-only, so it draws the
 * last full list the app loaded, saved to a file by [save]; nothing fetches
 * for it. One non-waking alarm redraws it after midnight so "Today" moves on
 * while the app is closed; it fires when the phone next wakes up anyway.
 */
object TasksWidget {
    private const val FILE = "tasks_widget.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Snapshot(val savedAt: Long, val tasks: List<TaskDto>)

    /** Stores the open tasks the widget can show and redraws it. Call off the main thread. */
    fun save(context: Context, tasks: List<TaskDto>) {
        val kept = tasks.filter { !it.isDone && it.parentId == null && it.onMainBoard }
        File(context.filesDir, FILE).writeText(json.encodeToString(Snapshot(System.currentTimeMillis(), kept)))
        render(context)
    }

    /** Forgets the stored tasks, on sign-out. */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        render(context)
    }

    fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val snap = load(context)
        val tasks = snap?.tasks.orEmpty()
        val now = System.currentTimeMillis()

        val views = RemoteViews(context.packageName, R.layout.widget_tasks)
        val openTasks = open(context, Uri.parse("scrinium:tasks")) { putExtra(MainActivity.EXTRA_OPEN_TASKS, true) }
        views.setOnClickPendingIntent(R.id.widget_title, openTasks)
        views.setOnClickPendingIntent(R.id.widget_inbox, openTasks)
        views.setOnClickPendingIntent(
            R.id.widget_new,
            open(context, Uri.parse("scrinium:new-task")) { putExtra(MainActivity.EXTRA_NEW_TASK, true) }
        )

        // Only a collection view scrolls in a widget, so the rows go in a
        // ListView; a row tap fills its task id into the shared template.
        views.setPendingIntentTemplate(R.id.widget_list, rowTemplate(context))
        val sections = agenda(tasks, now)
        val rows = sections.flatMap { section ->
            val header = RemoteViews(context.packageName, R.layout.widget_task_section)
            header.setTextViewText(R.id.section, section.label)
            header.setTextColor(R.id.section, if (section.kind == SectionKind.Overdue) RED else DIM)
            listOf(header) + section.rows.map { row(context, it, section.kind, now) }
        }
        val items = RemoteViewsCompat.RemoteCollectionItems.Builder().setViewTypeCount(2)
        rows.forEachIndexed { i, row -> items.addItem(i.toLong(), row) }
        val list = items.build()

        views.setViewVisibility(R.id.widget_empty, if (sections.isEmpty()) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_inbox_count, tasks.count { it.status == TaskStatus.Inbox.key }.toString())
        views.setTextViewText(R.id.widget_stamp, snap?.let { stamp(it.savedAt, now) } ?: "")
        for (id in ids) {
            RemoteViewsCompat.setRemoteAdapter(context, views, id, R.id.widget_list, list)
            manager.updateAppWidget(id, views)
        }
        scheduleMidnight(context, ids)
    }

    fun cancelMidnight(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(midnightIntent(context, IntArray(0)))
    }

    private fun row(context: Context, task: TaskDto, kind: SectionKind, now: Long): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_task_row)
        row.setTextViewText(R.id.row_title, task.title)
        val area = TaskArea.of(task.area)
        if (area != null) row.setInt(R.id.row_dot, "setColorFilter", area.color.toArgb())
        row.setViewVisibility(R.id.row_dot, if (area != null) View.VISIBLE else View.INVISIBLE)
        row.setTextViewText(R.id.row_meta, task.dueAt?.let { rowMeta(it, kind, now) } ?: "")
        row.setTextColor(R.id.row_meta, if (kind == SectionKind.Overdue) RED else DIM)
        row.setOnClickFillInIntent(R.id.row, Intent().putExtra(MainActivity.EXTRA_TASK_ID, task.id))
        return row
    }

    // Mutable so each row can fill in its task id; explicit, so nothing else can claim it.
    private fun rowTemplate(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun load(context: Context): Snapshot? = runCatching {
        json.decodeFromString<Snapshot>(File(context.filesDir, FILE).readText())
    }.getOrNull()

    // Time of the last load, or its date once that was another day.
    private fun stamp(savedAt: Long, now: Long): String =
        if (startOfDay(savedAt) == startOfDay(now)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(savedAt))
        else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(savedAt))

    // Inexact and non-waking: a redraw a little late is fine, waking the
    // phone for it is not. Rescheduled on every render.
    private fun scheduleMidnight(context: Context, ids: IntArray) {
        val at = addDays(System.currentTimeMillis(), 1) + 60_000
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, midnightIntent(context, ids))
    }

    private fun midnightIntent(context: Context, ids: IntArray): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TasksWidgetProvider::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // Distinct data per target keeps each PendingIntent separate.
    private fun open(context: Context, data: Uri, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setData(data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // Every row sets its colors: the list reuses row views, so a color set once sticks.
    private const val RED = 0xFFFF7A85.toInt()
    private const val DIM = 0xFF6C6965.toInt()
}

enum class SectionKind { Overdue, Day, Rest }

data class AgendaSection(val kind: SectionKind, val label: String, val rows: List<TaskDto>)

/**
 * Overdue tasks, then one section per day for the next seven ("Today",
 * "Tomorrow", then weekday names), then the rest of the app's "This week"
 * status column (undated or due later). Soonest first; empty sections are
 * left out.
 */
fun agenda(tasks: List<TaskDto>, now: Long): List<AgendaSection> {
    val today = startOfDay(now)
    val weekEnd = addDays(today, 7)
    val shown = tasks
        .filter { (it.dueAt != null && it.dueAt < weekEnd) || it.status == TaskStatus.Todo.key }
        .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.createdAt }))
    val days = (0 until 7).map { n ->
        val start = addDays(today, n)
        val end = addDays(today, n + 1)
        val label = when (n) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(start))
        }
        AgendaSection(SectionKind.Day, label, shown.filter { it.dueAt != null && it.dueAt >= start && it.dueAt < end })
    }
    return (
        listOf(AgendaSection(SectionKind.Overdue, "Overdue", shown.filter { it.dueAt != null && it.dueAt < today })) +
            days +
            AgendaSection(SectionKind.Rest, "This week", shown.filter { it.dueAt == null || it.dueAt >= weekEnd })
        ).filter { it.rows.isNotEmpty() }
}

// A day section already names the day, so its rows show only a set time;
// overdue and later rows show "Oct 11". Undated rows show nothing.
fun rowMeta(dueAt: Long, kind: SectionKind, now: Long): String = when (kind) {
    SectionKind.Day -> shortDue(dueAt, now).substringAfter(' ', "")
    SectionKind.Overdue, SectionKind.Rest -> shortDue(dueAt, now)
}

class TasksWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TasksWidget.render(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        TasksWidget.cancelMidnight(context)
    }
}
