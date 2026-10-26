package app.olaunchercf.helper

import android.icu.util.Calendar
import android.icu.util.IslamicCalendar
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Date

// Arabic has no standard short weekday or Hijri month names, and ICU's abbreviated
// forms differ between Android versions, so the short forms are written out here.
// Weekdays start on Sunday (Calendar.SUNDAY == 1), months on Muharram (0).
private val shortWeekdays = arrayOf("أحد", "اثنين", "ثلاثاء", "أربعاء", "خميس", "جمعة", "سبت")
private val shortMonths = arrayOf(
    "محرم", "صفر", "ربيع ١", "ربيع ٢", "جمادى ١", "جمادى ٢",
    "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
)

// e.g. "اثنين، ١٥ جمادى ١"
fun formatHijriDate(weekday: Int, day: Int, month: Int): String {
    return "${shortWeekdays[weekday - 1]}، ${toArabicDigits(day)} ${shortMonths[month]}"
}

fun toArabicDigits(number: Int): String {
    return toArabicDigits(number.toString())
}

fun toArabicDigits(text: CharSequence): String {
    return text.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
}

// Umm al-Qura calendar. The adjustment moves the Hijri day only, the weekday stays today's.
@RequiresApi(Build.VERSION_CODES.N)
fun hijriDateText(adjustmentDays: Int, now: Date = Date()): String {
    val calendar = IslamicCalendar()
    calendar.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
    calendar.time = now
    val weekday = calendar.get(Calendar.DAY_OF_WEEK)
    calendar.add(Calendar.DATE, adjustmentDays)
    return formatHijriDate(weekday, calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.MONTH))
}
