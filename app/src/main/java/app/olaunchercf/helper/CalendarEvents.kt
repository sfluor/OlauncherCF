package app.olaunchercf.helper

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

class CalendarInfo(val id: Long, val name: String)

// time is null for all-day events, and for events that started before that day
class EventRow(val event: CalendarEvent, val time: String?, val finished: Boolean) {
    val title get() = event.title
}

class DayEvents(val rows: List<EventRow>, val more: Int)

private const val DAY_MS = 86_400_000L
private const val EPOCH_JULIAN_DAY = 2_440_588

// the calendar provider indexes instances by Julian day, in local time
fun julianDay(millis: Long, utcOffsetMillis: Long): Int {
    return ((millis + utcOffsetMillis).floorDiv(DAY_MS) + EPOCH_JULIAN_DAY).toInt()
}

fun dayEvents(events: List<CalendarEvent>, startOfDay: Long, now: Long, limit: Int, formatTime: (Long) -> String): DayEvents {
    val rows = events
        .map { event ->
            val wholeDay = event.allDay || event.begin < startOfDay
            EventRow(event, if (wholeDay) null else formatTime(event.begin), !event.allDay && event.end <= now)
        }
        .sortedWith(compareBy<EventRow> { it.time != null }.thenBy { it.event.begin }.thenBy { it.title })
    // when the list is cut, the events already over make room first, oldest first
    val over = rows.filter { it.finished }.take(maxOf(0, rows.size - limit)).toSet()
    val kept = rows.filter { it !in over }.take(limit)
    return DayEvents(kept, rows.size - kept.size)
}

// timed events that began the day before are already listed under that day
fun eventsStartingOn(startOfDay: Long, events: List<CalendarEvent>): List<CalendarEvent> {
    return events.filter { it.allDay || it.begin >= startOfDay }
}

fun startOfDay(now: Long, plusDays: Int = 0): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = now
    calendar.add(Calendar.DATE, plusDays)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

fun hasCalendarPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

// shown in the calendar app, declined invitations and the hidden calendars left out
fun eventsSelection(hiddenCalendars: Set<Long>): String {
    val selection = "${Instances.VISIBLE} = 1 AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
    if (hiddenCalendars.isEmpty()) return selection
    return "$selection AND ${Instances.CALENDAR_ID} NOT IN (${hiddenCalendars.sorted().joinToString(", ")})"
}

// the calendars shown in the calendar app, the ones events can come from
fun queryCalendars(context: Context): List<CalendarInfo> {
    if (!hasCalendarPermission(context)) return emptyList()
    val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME)
    val calendars = mutableListOf<CalendarInfo>()
    try {
        context.contentResolver.query(
            Calendars.CONTENT_URI, projection, "${Calendars.VISIBLE} = 1", null,
            "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars.add(CalendarInfo(cursor.getLong(0), cursor.getString(1).orEmpty()))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return calendars
}

// the day's instances, see eventsSelection
fun queryDayEvents(context: Context, startOfDay: Long, hiddenCalendars: Set<Long>): List<CalendarEvent> {
    if (!hasCalendarPermission(context)) return emptyList()
    val day = julianDay(startOfDay, TimeZone.getDefault().getOffset(startOfDay).toLong())
    val uri = Instances.CONTENT_BY_DAY_URI.buildUpon()
        .appendPath(day.toString())
        .appendPath(day.toString())
        .build()
    val projection = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY)
    val selection = eventsSelection(hiddenCalendars)

    val events = mutableListOf<CalendarEvent>()
    try {
        context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(
                    CalendarEvent(
                        id = cursor.getLong(0),
                        title = cursor.getString(1).orEmpty(),
                        begin = cursor.getLong(2),
                        end = cursor.getLong(3),
                        allDay = cursor.getInt(4) == 1,
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return events
}

fun openCalendarEvent(context: Context, event: CalendarEvent) {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
    openCalendar(context, intent)
}

fun openCalendarDay(context: Context, day: Long) {
    val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(day.toString()).build()
    openCalendar(context, Intent(Intent.ACTION_VIEW, uri))
}

private fun openCalendar(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        showToastShort(context, "No calendar app found")
    }
}
