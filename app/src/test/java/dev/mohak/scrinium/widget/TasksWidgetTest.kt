package dev.mohak.scrinium.widget

import dev.mohak.scrinium.data.remote.TaskDto
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TasksWidgetTest {
    private fun local(y: Int, m: Int, d: Int, h: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun task(id: String, due: Long?, status: String = "doing") =
        TaskDto(id = id, title = id, status = status, dueAt = due)

    @Test
    fun bucketsByDayAndKeepsTheWholeThisWeekColumn() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val now = local(2026, 10, 13, 15)
        val tasks = listOf(
            task("late", local(2026, 10, 11)),
            task("todayTimed", local(2026, 10, 13, 9)),
            task("tomorrow", local(2026, 10, 14)),
            task("sat", local(2026, 10, 17)),
            task("nextTue", local(2026, 10, 20)),
            task("undated", null),
            task("weekUndated", null, status = "todo"),
            task("weekLater", local(2026, 10, 28), status = "todo")
        )
        val got = agenda(tasks, now).map { s -> s.bucket to s.rows.map { it.id } }
        assertEquals(
            listOf(
                Bucket.Overdue to listOf("late"),
                Bucket.Today to listOf("todayTimed"),
                Bucket.Tomorrow to listOf("tomorrow"),
                Bucket.Week to listOf("sat", "weekLater", "weekUndated")
            ),
            got
        )
    }

    @Test
    fun todayRowsShowOnlyTheirTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val now = local(2026, 10, 13, 8)
        assertEquals("", rowMeta(local(2026, 10, 13), Bucket.Today, now))
        assertEquals("", rowMeta(local(2026, 10, 14), Bucket.Tomorrow, now))
        assertEquals("Sat", rowMeta(local(2026, 10, 17), Bucket.Week, now))
    }
}
