package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.remote.CalendarEvent
import dev.mohak.scrinium.data.remote.CalendarEventDto
import dev.mohak.scrinium.data.remote.TaskDto
import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskModelTest {
    private fun local(y: Int, m: Int, d: Int, h: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private val json = Json { ignoreUnknownKeys = true }
    private fun event(s: String) = json.decodeFromString<CalendarEventDto>(s).toEvent()

    @Test
    fun allDayEventsKeepTheirDatesInAnyZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // Google: Oct 4-5 all day is start 2026-10-04, exclusive end 2026-10-06.
        val e = event("""{"id":"a","title":"Trip","start":"2026-10-04","end":"2026-10-06","allDay":true,"calendar":"Home"}""")!!
        assertEquals(local(2026, 10, 4), e.start)
        assertEquals(listOf(local(2026, 10, 4), local(2026, 10, 5)), eventDays(e, local(2026, 10, 1), local(2026, 11, 1)))
    }

    @Test
    fun timedEventsDecodeTheirOffset() {
        val e = event("""{"id":"b","title":"Call","start":"2026-10-04T09:00:00-04:00","end":"2026-10-04T10:00:00Z","allDay":false}""")!!
        assertEquals(1791118800000, e.start)
        assertEquals(1791072000000 + 10 * 3_600_000L, e.end)
        assertNull(event("""{"id":"c","title":"Bad","start":"soon","end":"later","allDay":false}"""))
    }

    @Test
    fun timedEventsCoverEveryDayTheyTouch() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val month = local(2026, 10, 1) to local(2026, 11, 1)
        val e = CalendarEvent("b", "Late", local(2026, 10, 4, 23), local(2026, 10, 5, 1), allDay = false)
        assertEquals(listOf(local(2026, 10, 4), local(2026, 10, 5)), eventDays(e, month.first, month.second))
        val oneHour = CalendarEvent("c", "Call", local(2026, 10, 4, 9), local(2026, 10, 4, 10), allDay = false)
        assertEquals(listOf(local(2026, 10, 4)), eventDays(oneHour, month.first, month.second))
    }

    @Test
    fun longEventsFillTheWholeRange() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // 100 days from mid-August: every October day, nothing outside it.
        val e = CalendarEvent("d", "Semester", local(2026, 8, 15), addDays(local(2026, 8, 15), 100), allDay = true)
        val days = eventDays(e, local(2026, 10, 1), local(2026, 11, 1))
        assertEquals(31, days.size)
        assertEquals(local(2026, 10, 1), days.first())
        assertEquals(local(2026, 10, 31), days.last())
    }

    @Test
    fun tomorrowSurvivesDaylightSavingChanges() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // Fall back (25h day), spring forward (23h day), and an ordinary day.
        assertEquals("Tomorrow", shortDue(local(2026, 11, 2), now = local(2026, 11, 1, 12)))
        assertEquals("Tomorrow", shortDue(local(2027, 3, 15), now = local(2027, 3, 14, 12)))
        assertEquals("Tomorrow", shortDue(local(2026, 10, 6), now = local(2026, 10, 5, 12)))
    }

    @Test
    fun viewsKeepProjectBacklogsOffTheAllBoard() {
        val loose = TaskDto("1", "Loose", status = "inbox")
        val backlog = TaskDto("2", "Backlog", status = "inbox", project = "Jobs")
        val active = TaskDto("3", "Active", status = "todo", project = "Jobs")
        val dated = TaskDto("4", "Dated", status = "inbox", project = "Jobs", dueAt = 1L)
        val underBacklog = TaskDto("5", "Sub", status = "todo", project = "Jobs", parentId = "2")
        val datedUnderBacklog = TaskDto("6", "Sub dated", status = "todo", project = "Jobs", parentId = "2", dueAt = 1L)
        val all = listOf(loose, backlog, active, dated, underBacklog, datedUnderBacklog)
        assertEquals(listOf(loose, active, dated, datedUnderBacklog), selectTasks(all, ProjectView.All))
        assertEquals(listOf(loose), selectTasks(all, ProjectView.Project(null)))
        assertEquals(all - loose, selectTasks(all, ProjectView.Project("Jobs")))
    }

    @Test
    fun snoozeOffersTheEveningOnlyBeforeFive() {
        val morning = Calendar.getInstance().apply { timeInMillis = local(2026, 11, 10, 9) }
        assertEquals(
            listOf("10 minutes", "1 hour", "This evening, 6 PM", "Tomorrow, 9 AM"),
            snoozeChoices(morning).map { it.label }
        )
        assertEquals(local(2026, 11, 10, 18), snoozeChoices(morning)[2].at)
        val evening = Calendar.getInstance().apply { timeInMillis = local(2026, 11, 30, 19) }
        assertEquals(local(2026, 12, 1, 9), snoozeChoices(evening).last().at)
        assertEquals(3, snoozeChoices(evening).size)
    }

    @Test
    fun firedRemindersSkipDoneAndFutureOnes() {
        val t = TaskDto(id = "a", title = "a", status = "todo", remindAt = 100)
        val tasks = listOf(t, t.copy(id = "b", status = "done"), t.copy(id = "c", remindAt = 300), t.copy(id = "d", remindAt = 50))
        assertEquals(listOf("d", "a"), firedReminders(tasks, 200).map { it.id })
    }

    @Test
    fun projectColorsHashLikeTheWeb() {
        // Expected values from the web's projectStyle, run in node.
        assertEquals("green", projectColorName("scrinium", null))
        assertEquals("sand", projectColorName("robotics", null))
        assertEquals("violet", projectColorName("Robotics/Arm", null))
        assertEquals("blue", projectColorName("caf\u00e9", null))
        assertEquals("rose", projectColorName("scrinium", "rose"))
        assertEquals("green", projectColorName("scrinium", "pink"))
    }

    @Test
    fun dueGroupsSplitByDay() {
        val now = local(2026, 11, 10, 15)
        val t = TaskDto(id = "x", title = "x", status = "todo")
        val tasks = listOf(
            t.copy(id = "late", dueAt = local(2026, 11, 9, 23)),
            t.copy(id = "tonight", dueAt = local(2026, 11, 10, 20)),
            t.copy(id = "today", dueAt = local(2026, 11, 10)),
            t.copy(id = "sixth", dueAt = local(2026, 11, 16, 23)),
            t.copy(id = "week", dueAt = local(2026, 11, 17)),
            t.copy(id = "none")
        )
        val groups = dueGroups(tasks, now)
        assertEquals(10, groups.size)
        assertEquals(listOf("late"), groups[0].rows.map { it.id })
        assertEquals(listOf("today", "tonight"), groups[1].rows.map { it.id })
        assertEquals(local(2026, 11, 10), groups[1].day)
        assertEquals(listOf("sixth"), groups[7].rows.map { it.id })
        assertEquals(listOf("week"), groups[8].rows.map { it.id })
        assertEquals(listOf("none"), groups[9].rows.map { it.id })
    }
}
