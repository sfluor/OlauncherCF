package app.olaunchercf.helper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class HijriDateTest {

    @Test
    fun formatsShortWeekdayDayAndNumberedMonth() {
        // Monday, 15 Jumada al-Ula
        assertEquals("اثنين، ١٥ جمادى ١", formatHijriDate(Calendar.MONDAY, 15, 4))
    }

    @Test
    fun numbersTheTwoRabiAndJumadaMonths() {
        assertEquals("أحد، ١ ربيع ١", formatHijriDate(Calendar.SUNDAY, 1, 2))
        assertEquals("أحد، ١ ربيع ٢", formatHijriDate(Calendar.SUNDAY, 1, 3))
        assertEquals("أحد، ١ جمادى ٢", formatHijriDate(Calendar.SUNDAY, 1, 5))
    }

    @Test
    fun keepsOtherMonthNamesWhole() {
        assertEquals("جمعة، ٢٧ رمضان", formatHijriDate(Calendar.FRIDAY, 27, 8))
        assertEquals("سبت، ١٠ ذو الحجة", formatHijriDate(Calendar.SATURDAY, 10, 11))
        assertEquals("خميس، ٣٠ محرم", formatHijriDate(Calendar.THURSDAY, 30, 0))
    }

    @Test
    fun convertsAllDigitsToArabicIndic() {
        assertEquals("١٢٣٤٥٦٧٨٩٠", toArabicDigits(1234567890))
    }

    @Test
    fun convertsDigitsInsideText() {
        assertEquals("٠٩:٤٠", toArabicDigits("09:40"))
        assertEquals("٢١:٠٥ PM", toArabicDigits("21:05 PM"))
        assertEquals("١٦:١٢", toArabicDigits("١٦:١٢"))
    }
}
