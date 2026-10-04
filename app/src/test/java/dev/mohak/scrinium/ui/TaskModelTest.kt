package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.remote.CalendarEvent
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
