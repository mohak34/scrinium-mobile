package dev.mohak.scrinium.ui

import androidx.compose.ui.graphics.Color
import dev.mohak.scrinium.data.remote.TaskDto
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

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

fun TaskDto.dueText(): String =
    if (isDueToday() && !isDone) "Today" else dueLabel(dueAt)

// List view grouping, same buckets and order as the web task list.
data class TaskGroup(val label: String, val rows: List<TaskDto>)

fun groupByDue(tasks: List<TaskDto>): List<TaskGroup> {
    val tomorrow = startOfToday() + DAY_MS
    val open = tasks.filter { !it.isDone }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val done = tasks.filter { it.isDone }.sortedByDescending { it.updatedAt }
    return listOf(
        TaskGroup("Overdue", open.filter { it.isOverdue() }),
        TaskGroup("Today", open.filter { !it.isOverdue() && it.isDueToday() }),
        TaskGroup("Upcoming", open.filter { it.dueAt != null && it.dueAt >= tomorrow }),
        TaskGroup("No date", open.filter { it.dueAt == null }),
        TaskGroup("Done", done)
    ).filter { it.rows.isNotEmpty() }
}
