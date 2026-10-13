package dev.mohak.scrinium.widget

import dev.mohak.scrinium.data.remote.TaskDto
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TasksWidgetTest {
    private fun local(y: Int, m: Int, d: Int, h: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun task(id: String, due: Long?, status: String = "doing") =
        TaskDto(id = id, title = id, status = status, dueAt = due)

    @Test
    fun oneSectionPerDayThenTheRestOfThisWeek() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)
        val now = local(2026, 10, 13, 15)
        val tasks = listOf(
            task("late", local(2026, 10, 11)),
            task("todayTimed", local(2026, 10, 13, 9)),
            task("tomorrow", local(2026, 10, 14)),
            task("sat", local(2026, 10, 17)),
            task("mon", local(2026, 10, 19, 23)),
            task("nextTue", local(2026, 10, 20)),
            task("undated", null),
            task("weekUndated", null, status = "todo"),
            task("weekLater", local(2026, 10, 28), status = "todo")
        )
        val got = agenda(tasks, now).map { s -> s.label to s.rows.map { it.id } }
        assertEquals(
            listOf(
                "Overdue" to listOf("late"),
                "Today" to listOf("todayTimed"),
                "Tomorrow" to listOf("tomorrow"),
                "Saturday" to listOf("sat"),
                "Monday" to listOf("mon"),
                "This week" to listOf("weekLater", "weekUndated")
            ),
            got
        )
    }

    @Test
    fun dayRowsShowOnlyTheirTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)
        val now = local(2026, 10, 13, 8)
        assertEquals("", rowMeta(local(2026, 10, 13), SectionKind.Day, now))
        assertEquals("", rowMeta(local(2026, 10, 17), SectionKind.Day, now))
        assertEquals("9:00 AM", rowMeta(local(2026, 10, 17, 9), SectionKind.Day, now))
        assertEquals("Oct 28", rowMeta(local(2026, 10, 28), SectionKind.Rest, now))
    }
}
