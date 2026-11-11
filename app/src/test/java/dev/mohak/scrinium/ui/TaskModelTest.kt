package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.remote.CalendarEvent
import dev.mohak.scrinium.data.remote.TaskDto
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskModelTest {
    private fun local(y: Int, m: Int, d: Int, h: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test
    fun allDayEventsKeepTheirDatesInAnyZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // Google: Oct 4-5 all day = UTC midnight Oct 4 to exclusive UTC midnight Oct 6.
        val e = CalendarEvent("a", "Trip", 1791072000000, 1791244800000, allDay = true)
        assertEquals(listOf(local(2026, 10, 4), local(2026, 10, 5)), eventDays(e))
    }

    @Test
    fun timedEventsCoverEveryDayTheyTouch() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val e = CalendarEvent("b", "Late", local(2026, 10, 4, 23), local(2026, 10, 5, 1), allDay = false)
        assertEquals(listOf(local(2026, 10, 4), local(2026, 10, 5)), eventDays(e))
        val oneHour = CalendarEvent("c", "Call", local(2026, 10, 4, 9), local(2026, 10, 4, 10), allDay = false)
        assertEquals(listOf(local(2026, 10, 4)), eventDays(oneHour))
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
    fun boardViewsSplitQuotidianFromProjects() {
        val loose = TaskDto("1", "Loose", status = "inbox")
        val backlog = TaskDto("2", "Backlog", status = "inbox", project = "Jobs")
        val active = TaskDto("3", "Active", status = "todo", project = "Jobs")
        assertEquals(listOf(loose, active), listOf(loose, backlog, active).filter { ProjectView.All.shows(it) })
        assertTrue(ProjectView.Project(null).shows(loose))
        assertFalse(ProjectView.Project(null).shows(active))
        assertEquals(listOf(backlog, active), listOf(loose, backlog, active).filter { ProjectView.Project("Jobs").shows(it) })
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
