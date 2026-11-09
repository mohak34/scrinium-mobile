package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.remote.CalendarEvent
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

val TaskDto.statusEnum: TaskStatus get() = TaskStatus.of(status)
val TaskDto.isDone: Boolean get() = status == TaskStatus.Done.key

// A project's undated Inbox is its backlog and stays on the project board;
// the All views show a project task once it is planned or dated.
private val TaskDto.onMainBoard: Boolean get() = project == null || status != TaskStatus.Inbox.key || dueAt != null

fun projectName(path: String): String = path.substringAfterLast('/')

// Which project the task and Notes tabs show, like the web's project tabs.
// A null path is Quotidian: tasks in no project, notes outside every
// project folder.
sealed interface ProjectView {
    data object All : ProjectView
    data class Project(val path: String?) : ProjectView
}

/**
 * The tasks a view shows, subtasks included, ported from the web's
 * selectTasks. One project, or Quotidian, is every task in it. All is every
 * dated task plus the rest unless they or an ancestor sit in a project
 * backlog. The list, board and widget select through here.
 */
fun selectTasks(all: List<TaskDto>, view: ProjectView): List<TaskDto> {
    if (view is ProjectView.Project) return all.filter { it.project == view.path }
    val byId = all.associateBy { it.id }
    fun planned(t: TaskDto): Boolean {
        val seen = mutableSetOf<String>()
        var a: TaskDto? = t
        while (a != null && seen.add(a.id)) {
            if (!a.onMainBoard) return false
            a = a.parentId?.let(byId::get)
        }
        return true
    }
    return all.filter { it.dueAt != null || planned(it) }
}

const val QUOTIDIAN = "Quotidian"

fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// Local midnight [n] calendar days after the day [ms] falls on. Not n * 24h:
// days across a daylight-saving change are 23 or 25 hours long, and where
// the clocks jump at midnight a day starts at 01:00, so re-take its start.
fun addDays(ms: Long, n: Int): Long = startOfDay(Calendar.getInstance().apply {
    timeInMillis = startOfDay(ms)
    add(Calendar.DAY_OF_MONTH, n)
}.timeInMillis)

fun TaskDto.isOverdue(): Boolean = !isDone && dueAt != null && dueAt < startOfToday()

fun TaskDto.isDueToday(): Boolean {
    val due = dueAt ?: return false
    val today = startOfToday()
    return due >= today && due < addDays(today, 1)
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
        day == addDays(today, 1) -> "Tomorrow"
        day > today && day < addDays(today, 7) -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(dueAt))
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
 * Local-midnight days an event covers within [from, to). All-day events
 * already start at a local midnight with an exclusive end (see
 * CalendarEventDto); timed events cover every day they touch.
 */
fun eventDays(e: CalendarEvent, from: Long, to: Long): List<Long> {
    val last = startOfDay(maxOf(e.start, e.end - 1))
    val days = mutableListOf<Long>()
    var day = startOfDay(maxOf(e.start, from))
    while (day <= last && day < to) {
        days += day
        day = addDays(day, 1)
    }
    return days
}

fun eventTimeLabel(e: CalendarEvent): String {
    if (e.allDay) return "All day"
    val f = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${f.format(Date(e.start))} - ${f.format(Date(e.end))}"
}
