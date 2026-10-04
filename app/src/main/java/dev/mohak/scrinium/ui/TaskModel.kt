package dev.mohak.scrinium.ui

import androidx.compose.ui.graphics.Color
import dev.mohak.scrinium.data.remote.CalendarEvent
import dev.mohak.scrinium.data.remote.TaskDto
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

// Task vocabulary, mirrored from the web client's taskModel.ts. The keys are
// the wire values; order is the board's column order.
enum class TaskStatus(val key: String, val label: String) {
    Inbox("inbox", "Inbox"),
    Todo("todo", "This week"),
    Doing("doing", "Doing"),
    Waiting("waiting", "Waiting"),
    Done("done", "Done");

    companion object {
        fun of(key: String): TaskStatus = entries.firstOrNull { it.key == key } ?: Todo
    }
}

enum class TaskArea(val key: String, val label: String, val color: Color) {
    College("college", "College", Color(0xFF8FB3FF)),
    Learning("learning", "Learning", Color(0xFFC4A8FF)),
    Work("work", "Work", Color(0xFFF0A35E)),
    Life("life", "Life", Color(0xFFD6C3A1));

    companion object {
        fun of(key: String?): TaskArea? = entries.firstOrNull { it.key == key }
    }
}

enum class TaskPriority(val key: String, val label: String) {
    None("none", "No priority"),
    Low("low", "Low"),
    Medium("medium", "Medium"),
    High("high", "High"),
    Urgent("urgent", "Urgent");

    companion object {
        fun of(key: String): TaskPriority = entries.firstOrNull { it.key == key } ?: None
    }
}

// Soft cap on the Doing column; the board warns past it, never blocks.
const val DOING_LIMIT = 3

private const val DAY_MS = 86_400_000L

val TaskDto.statusEnum: TaskStatus get() = TaskStatus.of(status)
val TaskDto.isDone: Boolean get() = status == TaskStatus.Done.key

fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun TaskDto.isOverdue(): Boolean = !isDone && dueAt != null && dueAt < startOfToday()

fun TaskDto.isDueToday(): Boolean {
    val due = dueAt ?: return false
    val today = startOfToday()
    return due >= today && due < today + DAY_MS
}

// "Sep 26", or "Sep 26, 9:00 AM" when the due time isn't midnight.
fun dueLabel(dueAt: Long?): String {
    if (dueAt == null) return "No date"
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dueAt))
    val cal = Calendar.getInstance().apply { timeInMillis = dueAt }
    if (cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0) return date
    return "$date, ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dueAt))}"
}

fun stampLabel(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

// Compact due label for task rows: "Today", "Tomorrow", a weekday within
// the coming week, else "Sep 26"; a set time is appended ("Thu 9:00 AM").
fun shortDue(dueAt: Long, now: Long = System.currentTimeMillis()): String {
    val today = startOfDay(now)
    val day = startOfDay(dueAt)
    val date = when {
        day == today -> "Today"
        day == today + DAY_MS -> "Tomorrow"
        day > today && day < today + 7 * DAY_MS -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(dueAt))
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(Date(dueAt))
    }
    val cal = Calendar.getInstance().apply { timeInMillis = dueAt }
    if (cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0) return date
    return "$date ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dueAt))}"
}

// List view grouping: one group per status in working order (what's new,
// what's in hand, the week, what's blocked, done), open tasks by due date.
data class TaskGroup(val status: TaskStatus, val rows: List<TaskDto>)

val ListOrder = listOf(TaskStatus.Inbox, TaskStatus.Doing, TaskStatus.Todo, TaskStatus.Waiting, TaskStatus.Done)

fun groupByStatus(tasks: List<TaskDto>): List<TaskGroup> = ListOrder.map { s ->
    val rows = tasks.filter { it.status == s.key }
    TaskGroup(
        s,
        if (s == TaskStatus.Done) rows.sortedByDescending { it.updatedAt }
        else rows.sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.createdAt }))
    )
}.filter { it.rows.isNotEmpty() }

/**
 * Local-midnight days an event covers. All-day events come from Google as
 * UTC midnights with an exclusive end, so their dates are read in UTC and
 * rebuilt as local days. Timed events cover every day they touch.
 */
fun eventDays(e: CalendarEvent): List<Long> {
    val (first, last) = if (e.allDay) {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        fun local(ms: Long): Long {
            utc.timeInMillis = ms
            return Calendar.getInstance().apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        local(e.start) to local(maxOf(e.start, e.end - 1))
    } else {
        startOfDay(e.start) to startOfDay(maxOf(e.start, e.end - 1))
    }
    val days = mutableListOf<Long>()
    val cal = Calendar.getInstance().apply { timeInMillis = first }
    while (cal.timeInMillis <= last && days.size < 62) {
        days += cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    return days
}

fun eventTimeLabel(e: CalendarEvent): String {
    if (e.allDay) return "All day"
    val f = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${f.format(Date(e.start))} - ${f.format(Date(e.end))}"
}
