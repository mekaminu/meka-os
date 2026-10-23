package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Quality gate slice 32: Mail's switch cards show a short line; the full one is behind "How it works". */
class MailSwitchShortTest {
    private val r = MailAutopilotPanelRules
    private fun panel(on: Boolean = true, paused: Boolean = false, markRead: Boolean = true, reader: Boolean = false) =
        r.panel(MailAutopilotSetting(on, null, markRead, reader), paused)

    @Test fun eachStateHasItsShortLineAndKeepsTheFullOne() {
        assertEquals(r.ON_SHORT, panel().shortLine)
        assertEquals(r.ON_LINE, panel().line)
        assertEquals(r.OFF_SHORT, panel(on = false).shortLine)
        assertEquals(r.PAUSED_SHORT, panel(paused = true).shortLine)
        assertEquals(r.OFF_SHORT, panel(on = false, paused = true).shortLine)
        assertEquals(r.MARK_READ_ON_SHORT, panel().markReadShort)
        assertEquals(r.MARK_READ_OFF_SHORT, panel(markRead = false).markReadShort)
        assertEquals(r.READER_READ_OFF_SHORT, panel().readerReadShort)
        assertEquals(r.READER_READ_ON_SHORT, panel(reader = true).readerReadShort)
    }

    @Test fun shortLinesAreShortAndSayTheSameState() {
        val p = listOf(panel(), panel(on = false), panel(paused = true), panel(markRead = false), panel(reader = true))
        p.flatMap { listOf(it.shortLine to it.line, it.markReadShort to it.markReadLine, it.readerReadShort to it.readerReadLine) }
            .forEach { (short, full) ->
                assertTrue(short.length <= 80, short)
                assertEquals(full.substringBefore(" "), short.substringBefore(" "), "$short / $full")
                assertTrue(short.length <= full.length)
            }
    }

    @Test fun theButtonGoesUnderTheWordsOnTheCoverScreen() {
        assertTrue(r.stacked(344f))
        assertTrue(r.stacked(419f))
        assertFalse(r.stacked(420f))
        assertFalse(r.stacked(829f))
    }

    @Test fun howItWorksOnlyWhenThereIsMore() {
        assertTrue(r.hasMore(r.ON_SHORT, r.ON_LINE))
        assertFalse(r.hasMore("Off", "Off"))
    }
}
