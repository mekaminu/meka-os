package os.meka.android.capture

import os.meka.android.capture.CaptureRequests.ACTION_CAPTURE
import os.meka.android.capture.CaptureRequests.ACTION_PROCESS_TEXT
import os.meka.android.capture.CaptureRequests.ACTION_SEND
import os.meka.core.domain.QuickCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureRequestTest {
    @Test
    fun widgetAndTileOpenAnEmptySheetOptionallyListening() {
        assertEquals(CaptureRequest("", null, false), CaptureRequests.from(ACTION_CAPTURE, null, null, null, false))
        assertEquals(CaptureRequest("", null, true), CaptureRequests.from(ACTION_CAPTURE, null, null, null, true))
    }

    @Test
    fun shareSheetBringsTextAndSubject() {
        assertEquals(CaptureRequest("https://x.org/a", "An article", false),
            CaptureRequests.from(ACTION_SEND, "text/plain", "https://x.org/a", "  An article ", true))
        assertEquals(CaptureRequest("", "Only a subject", false),
            CaptureRequests.from(ACTION_SEND, "text/plain", null, "Only a subject", false))
    }

    @Test
    fun selectedTextIgnoresSubject() {
        assertEquals(CaptureRequest("Book the MOT", null, false),
            CaptureRequests.from(ACTION_PROCESS_TEXT, "text/plain", "Book the MOT", "ignored", false))
    }

    @Test
    fun nonTextEmptyAndUnknownAreRefused() {
        assertNull(CaptureRequests.from(ACTION_SEND, "image/png", "x", null, false))
        assertNull(CaptureRequests.from(ACTION_SEND, "text/plain", "   ", null, false))
        assertNull(CaptureRequests.from("android.intent.action.VIEW", "text/plain", "x", null, false))
        assertNull(CaptureRequests.from(null, null, null, null, false))
    }

    @Test
    fun hugeSharesAreCut() {
        val r = CaptureRequests.from(ACTION_SEND, "text/plain", "a".repeat(50_000), null, false)
        assertEquals(CaptureRequests.MAX_SHARED, r?.text?.length)
    }

    @Test
    fun spokenTakesTheFirstNonBlankGuess() {
        assertEquals("Buy milk", CaptureRequests.spoken(listOf(" ", "buy milk", "by milk")))
        assertNull(CaptureRequests.spoken(listOf("", " ")))
        assertNull(CaptureRequests.spoken(null))
    }

    @Test
    fun composedTextRoundTripsThroughTheCore() {
        val d = QuickCapture.draft("https://www.bbc.co.uk/sport")!!
        val again = QuickCapture.draft(CaptureRequests.compose(d.title, d.notes))
        assertEquals(d, again)
        assertEquals("Edited", QuickCapture.draft(CaptureRequests.compose("  Edited ", null))?.title)
        val long = QuickCapture.draft("word ".repeat(60).trim())!!
        assertEquals(long, QuickCapture.draft(CaptureRequests.compose(long.title, long.notes)))
    }
}
