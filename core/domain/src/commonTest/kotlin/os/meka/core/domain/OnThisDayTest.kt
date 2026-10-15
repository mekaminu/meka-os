package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnThisDayTest {
    private val today = CivilDate.toEpochDay(2026, 10, 15)

    private fun photo(id: String, y: Int, m: Int, d: Int, hour: Int = 12, path: String = "DCIM/Camera/", name: String = "$id.jpg", w: Int = 4000, h: Int = 3000): MemoryPhoto {
        val day = CivilDate.toEpochDay(y, m, d)
        return MemoryPhoto(id, day * CivilDate.DAY_MS + hour * 3_600_000L, day, path, name, w, h)
    }

    @Test
    fun theSameDateInEachEarlierYear() {
        val w = OnThisDayRules.windows(today, years = 3)
        assertEquals(listOf(1, 2, 3), w.map { it.yearsAgo })
        assertEquals(listOf(CivilDate.toEpochDay(2025, 10, 15), CivilDate.toEpochDay(2024, 10, 15), CivilDate.toEpochDay(2023, 10, 15)), w.map { it.epochDay })
        val leap = OnThisDayRules.windows(CivilDate.toEpochDay(2028, 2, 29), years = 4)
        assertEquals(CivilDate.toEpochDay(2027, 2, 28), leap[0].epochDay)
        assertEquals(CivilDate.toEpochDay(2024, 2, 29), leap[3].epochDay)
    }

    @Test
    fun onlyHisOwnPhotosCount() {
        assertTrue(OnThisDayRules.isPhoto(photo("a", 2024, 10, 15)))
        assertTrue(OnThisDayRules.isPhoto(photo("b", 2024, 10, 15, path = "Pictures/WhatsApp/", name = "IMG-20241015-WA0001.jpg", w = 1600, h = 1200)))
        assertFalse(OnThisDayRules.isPhoto(photo("c", 2024, 10, 15, path = "DCIM/Screenshots/", name = "Screenshot_20241015.jpg")))
        assertFalse(OnThisDayRules.isPhoto(photo("d", 2024, 10, 15, path = "Download/")))
        assertFalse(OnThisDayRules.isPhoto(photo("e", 2024, 10, 15, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/")))
        assertFalse(OnThisDayRules.isPhoto(photo("f", 2024, 10, 15, w = 320, h = 240)))
        assertFalse(OnThisDayRules.isPhoto(photo("g", 2024, 10, 15, w = 1080, h = 6000)))
        assertFalse(OnThisDayRules.isPhoto(photo("h", 2024, 10, 15, name = "funny.gif")))
    }

    @Test
    fun nothingFromThisDateMeansNoPhoto() {
        assertNull(OnThisDayRules.pick(listOf(photo("a", 2024, 10, 14), photo("b", 2026, 10, 15)), today))
        assertNull(OnThisDayRules.pick(emptyList(), today))
    }

    @Test
    fun cameraPhotosComeBeforeChatImagesAndHiddenOnesNeverShow() {
        val chat = photo("chat", 2024, 10, 15, path = "Pictures/WhatsApp/")
        val cam = photo("cam", 2023, 10, 15)
        assertEquals("cam", OnThisDayRules.pick(listOf(chat, cam), today)!!.photo.id)
        assertEquals("chat", OnThisDayRules.pick(listOf(chat), today)!!.photo.id)
        assertNull(OnThisDayRules.pick(listOf(cam), today, hidden = setOf("cam")))
    }

    @Test
    fun theMiddleOfTheDayComesFirstAndAnotherWalksTheYears() {
        val photos = listOf(
            photo("a1", 2025, 10, 15, hour = 9), photo("a2", 2025, 10, 15, hour = 12), photo("a3", 2025, 10, 15, hour = 18),
            photo("b1", 2022, 10, 15, hour = 10),
        )
        val all = OnThisDayRules.ordered(photos, today)
        assertEquals(4, all.size)
        // The years alternate: one from each year before the second from either.
        assertNotEquals(all[0].second, all[1].second)
        assertEquals("a2", all.first { it.second == 1 }.first.id)
        val first = OnThisDayRules.pick(photos, today)!!
        val next = OnThisDayRules.pick(photos, today, index = 1)!!
        assertNotEquals(first.photo.id, next.photo.id)
        assertEquals(first.photo.id, OnThisDayRules.pick(photos, today, index = 4)!!.photo.id) // wraps
        assertEquals("1 of 4 from this date", OnThisDayRules.countLine(first))
    }

    @Test
    fun theSameEveningPicksTheSamePhotoAndTheLineSaysWhen() {
        val photos = listOf(photo("x", 2023, 10, 15), photo("y", 2025, 10, 15))
        assertEquals(OnThisDayRules.pick(photos, today)!!.photo.id, OnThisDayRules.pick(photos.reversed(), today)!!.photo.id)
        val p = OnThisDayRules.pick(listOf(photo("x", 2023, 10, 15)), today)!!
        assertEquals(3, p.yearsAgo)
        assertEquals("3 years ago · Sun 15 Oct 2023", p.line)
        assertNull(OnThisDayRules.countLine(p))
        assertEquals("1 year ago · Wed 15 Oct 2025", OnThisDayRules.line(1, CivilDate.toEpochDay(2025, 10, 15)))
    }

    @Test
    fun aPhotoListedTwiceCountsOnce() {
        val p = photo("a", 2024, 10, 15)
        assertEquals(1, OnThisDayRules.ordered(listOf(p, p), today).size)
    }
}
