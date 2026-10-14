package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun screenshotsAreKnownByTheirFolderOrName() {
        assertTrue(ScreenshotRules.isScreenshot("DCIM/Screenshots/", "Screenshot_20261014_140212_Gmail.jpg"))
        assertTrue(ScreenshotRules.isScreenshot("Pictures/Screenshots/", "IMG_1.png"))
        assertTrue(ScreenshotRules.isScreenshot(null, "Screenshot 2026-10-14 at 14.02.12.png"))
        assertTrue(ScreenshotRules.isScreenshot("", "screenshot_1.png"))
        assertFalse(ScreenshotRules.isScreenshot("DCIM/Camera/", "20261014_140212.jpg"))
        assertFalse(ScreenshotRules.isScreenshot("Pictures/WhatsApp/", "IMG-20261014-WA0001.jpg"))
        assertFalse(ScreenshotRules.isScreenshot("DCIM/MyScreenshotsOfCats/", "cat.jpg"))
    }

    @Test
    fun onlyNewRecentScreenshotsAreReadNewestFirstAtMostThree() {
        val since = now - 30 * min
        val c = listOf(
            ScreenshotCandidate("1", now - 1 * min, true),
            ScreenshotCandidate("2", now - 2 * min, false), // a camera photo
            ScreenshotCandidate("3", now - 20 * min, true), // after the switch went on but too old to offer
            ScreenshotCandidate("4", now - 3 * min, true),
            ScreenshotCandidate("5", now - 4 * min, true),
            ScreenshotCandidate("6", now - 5 * min, true),
            ScreenshotCandidate("1", now - 1 * min, true), // listed twice
            ScreenshotCandidate("7", now + 10 * min, true), // a clock far ahead
        )
        assertEquals(listOf("1", "4", "5"), ScreenshotRules.pick(c, since, now).map { it.id })
        // Already seen up to 4: only 1 is new.
        assertEquals(listOf("1"), ScreenshotRules.pick(c, now - 3 * min, now).map { it.id })
        // Before the switch went on nothing is offered.
        assertEquals(emptyList(), ScreenshotRules.pick(c, now, now))
        assertEquals(now - 1 * min, ScreenshotRules.nextSince(c, since, now))
        assertEquals(now, ScreenshotRules.nextSince(c, now, now))
        assertEquals(since, ScreenshotRules.nextSince(emptyList(), since, now))
    }

    @Test
    fun whatAScreenshotLooksLike() {
        assertEquals(ScreenshotKind.PARKING, ScreenshotRules.kind("RingGo\nParking session confirmed\nLocation 81234\nExpires 15:30"))
        assertEquals(ScreenshotKind.APPOINTMENT, ScreenshotRules.kind("Oakfield Dental\nYour appointment is on Thu 16 Oct at 15:00\nPlease arrive 5 minutes early"))
        assertEquals(ScreenshotKind.TICKET, ScreenshotRules.kind("Boarding pass\nLondon to Barcelona\nDeparts 07:15 · Gate 22 · Seat 14C"))
        assertEquals(ScreenshotKind.BOOKING, ScreenshotRules.kind("Your booking is confirmed\nTable for 4\nSat 18 Oct, 19:30"))
        assertEquals(ScreenshotKind.ORDER, ScreenshotRules.kind("Your order has been dispatched\nArriving tomorrow\nOrder number 204-55"))
        assertEquals(ScreenshotKind.RECEIPT, ScreenshotRules.kind("Corner Café\nFlat white\nTotal £3.40\nContactless"))
        assertEquals(ScreenshotKind.LETTER, ScreenshotRules.kind("Dear parents and carers\nPlease return the reply slip with your consent for the trip"))
        assertEquals(ScreenshotKind.DATED, ScreenshotRules.kind("Football training moved\nSaturday 25 Oct at 10:00 on the top pitch"))
        // A chat, a meme, a home screen: nothing to act on, so no notification.
        assertNull(ScreenshotRules.kind("Sam\nhaha that's brilliant\nsee you later then"))
        assertNull(ScreenshotRules.kind("when the code compiles first time"))
        assertNull(ScreenshotRules.kind(""))
        // A booking word with no date or time isn't enough.
        assertNull(ScreenshotRules.kind("Thanks for booking with us, we hope you enjoyed your stay"))
    }

    @Test
    fun theNoticeSaysWhatItIsAndItsTellingLine() {
        val n = ScreenshotRules.noticeFor("14:02\n4G\nOakfield Dental\nYour appointment is on Thu 16 Oct at 15:00\nPlease arrive early")!!
        assertEquals("Screenshot · looks like an appointment", n.title)
        assertEquals("Your appointment is on Thu 16 Oct at 15:00", n.text)
        assertEquals(ScreenshotRules.ACTION, n.action)
        val p = ScreenshotRules.noticeFor("RingGo\nParking session confirmed\nLocation 81234\nExpires 15:30")!!
        assertEquals("Screenshot · looks like a parking confirmation", p.title)
        assertEquals("Expires 15:30", p.text)
        assertEquals(ParkingConfirmationRules.ACTION, p.action)
        assertNull(ScreenshotRules.noticeFor("haha see you later then"))
        val long = "Your booking is confirmed for the whole family weekend at the lakeside lodge on Sat 18 Oct 2026 from 15:00 onwards"
        val b = ScreenshotRules.noticeFor(long)!!
        assertEquals(ScreenshotRules.MAX_LINE, b.text.length)
        assertTrue(b.text.endsWith("…"))
    }
}
