package app.olaunchercf.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MawaqitTest {

    // a few of the names Mawaqit ships, mapped to their prayer index
    private val names = mapOf(
        "Fajr" to 0, "Dhuhr" to 1, "Asr" to 2, "Maghrib" to 3, "Isha" to 4, "Jum'a" to 5, "Jumu'a" to 5,
        "Sabah" to 0, "İkindi" to 2, "Аср" to 2,
        "الفجر" to 0, "العصر" to 2, "فجر" to 0, "عصر" to 2,
    )

    @Test
    fun findsArabicNameFromCountdownLabelInAnyLanguage() {
        assertEquals("العصر", arabicPrayerName("Asr in", names))
        assertEquals("العصر", arabicPrayerName("Asr dans", names))
        assertEquals("العصر", arabicPrayerName("İkindi Sonra", names))
        assertEquals("العصر", arabicPrayerName("в Аср", names))
        assertEquals("الفجر", arabicPrayerName("Sabah Sonra", names))
        assertEquals("الجمعة", arabicPrayerName("Jumu'a dans", names))
    }

    @Test
    fun prefersTheLongestMatchingName() {
        // "عصر" (Urdu) is inside "العصر" (Arabic), both are Asr
        assertEquals("العصر", arabicPrayerName("العصر بعد", names))
        assertEquals("الفجر", arabicPrayerName("فجر میں", names))
    }

    @Test
    fun returnsNullForUnknownName() {
        assertNull(arabicPrayerName("Shuruq in", names))
        assertNull(arabicPrayerName("", names))
    }

    @Test
    fun joinsNameAndTimeInArabicDigits() {
        assertEquals("العصر ١٦:١٢", formatNextPrayer("العصر", "16:12"))
        assertEquals("العصر ١٦:١٢", formatNextPrayer("العصر", " ١٦:١٢ "))
    }

    @Test
    fun showsTimeAloneWithoutName() {
        assertEquals("١٦:١٢", formatNextPrayer(null, "16:12"))
    }

    @Test
    fun hidesPlaceholderOrMissingTime() {
        assertNull(formatNextPrayer("العصر", "--:--"))
        assertNull(formatNextPrayer("العصر", ""))
        assertNull(formatNextPrayer("العصر", null))
    }
}
