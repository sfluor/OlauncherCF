package app.olaunchercf.helper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class CalendarEventsTest {

    private val hour = 3_600_000L
    private val startOfDay = 1_795_305_600_000L // 2026-11-22T00:00Z
    private fun at(h: Double) = startOfDay + (h * hour).toLong()
    private val formatTime: (Long) -> String = { "${(it - startOfDay) / hour}h" }

    private fun event(id: Long, title: String, begin: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(id, title, begin, end, allDay)

    @Test
    fun computesJulianDayInLocalTime() {
        assertEquals(2440588, julianDay(0, 0))
        assertEquals(2461367, julianDay(1_795_390_200_000, 0)) // 2026-11-22 23:30 UTC
        assertEquals(2461368, julianDay(1_795_390_200_000, hour)) // already the 23rd in Paris
        assertEquals(2440587, julianDay(0, -hour)) // still Dec 31st west of UTC
    }

    @Test
    fun putsAllDayEventsFirstThenSortsByStart() {
        val events = listOf(
            event(1, "Dentist", at(10.0), at(11.0)),
            event(2, "Birthday", at(0.0), at(24.0), allDay = true),
            event(3, "Breakfast", at(8.0), at(9.0)),
        )
        val today = dayEvents(events, startOfDay, now = at(7.0), limit = 5, formatTime = formatTime)
        assertEquals(listOf("Birthday", "Breakfast", "Dentist"), today.rows.map { it.title })
        assertEquals(listOf(null, "8h", "10h"), today.rows.map { it.time })
    }

    @Test
    fun dimsTimedEventsThatAlreadyEnded() {
        val events = listOf(
            event(1, "Breakfast", at(8.0), at(9.0)),
            event(2, "Dentist", at(10.0), at(11.0)),
            event(3, "Birthday", at(0.0), at(24.0), allDay = true),
        )
        val today = dayEvents(events, startOfDay, now = at(10.5), limit = 5, formatTime = formatTime)
        assertEquals(listOf(false, true, false), today.rows.map { it.finished })
    }

    @Test
    fun showsEventsStartedBeforeTodayAsAllDay() {
        val events = listOf(event(1, "Night shift", at(-2.0), at(6.0)))
        val today = dayEvents(events, startOfDay, now = at(7.0), limit = 5, formatTime = formatTime)
        assertEquals(null, today.rows.single().time)
        assertEquals(true, today.rows.single().finished)
    }

    @Test
    fun keepsTheFirstEventsAndCountsTheRest() {
        val events = (1..7).map { event(it.toLong(), "E$it", at(it.toDouble()), at(it + 0.5)) }
        val today = dayEvents(events, startOfDay, now = at(0.0), limit = 5, formatTime = formatTime)
        assertEquals(listOf("E1", "E2", "E3", "E4", "E5"), today.rows.map { it.title })
        assertEquals(2, today.more)
    }

    @Test
    fun dropsFinishedEventsFirstWhenTheListIsCut() {
        val events = (1..7).map { event(it.toLong(), "E$it", at(it.toDouble()), at(it + 0.5)) } +
            event(8, "Birthday", at(0.0), at(24.0), allDay = true)
        val today = dayEvents(events, startOfDay, now = at(5.75), limit = 5, formatTime = formatTime)
        assertEquals(listOf("Birthday", "E4", "E5", "E6", "E7"), today.rows.map { it.title })
        assertEquals(3, today.more)
    }

    @Test
    fun leavesOutHiddenCalendars() {
        val shown = "visible = 1 AND selfAttendeeStatus != 2"
        assertEquals(shown, eventsSelection(emptySet()))
        assertEquals("$shown AND calendar_id NOT IN (3, 12)", eventsSelection(setOf(12, 3)))
    }

    @Test
    fun emptyDayHasNoRows() {
        val today = dayEvents(emptyList(), startOfDay, now = at(9.0), limit = 5, formatTime = formatTime)
        assertEquals(0, today.rows.size)
        assertEquals(0, today.more)
    }

    @Test
    fun leavesOutTimedEventsCarriedOverFromTheDayBefore() {
        val tomorrow = startOfDay + 24 * hour
        val events = listOf(
            event(1, "Late dinner", at(22.0), at(25.0)),
            event(2, "Holidays", at(0.0), at(72.0), allDay = true),
            event(3, "Gym", at(31.0), at(32.0)),
        )
        assertEquals(listOf("Holidays", "Gym"), eventsStartingOn(tomorrow, events).map { it.title })
    }

    @Test
    fun findsTheStartOfTomorrowAcrossDaylightSavingTime() {
        val default = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val saturdayNoon = 1_792_836_000_000L // 2026-10-24T12:00+02:00, clocks go back on the 25th
            assertEquals(1_792_792_800_000L, startOfDay(saturdayNoon)) // 2026-10-24T00:00+02:00
            assertEquals(1_792_879_200_000L, startOfDay(saturdayNoon, plusDays = 1)) // 2026-10-25T00:00+02:00
            assertEquals(1_792_969_200_000L, startOfDay(saturdayNoon, plusDays = 2)) // 2026-10-26T00:00+01:00
        } finally {
            TimeZone.setDefault(default)
        }
    }
}
