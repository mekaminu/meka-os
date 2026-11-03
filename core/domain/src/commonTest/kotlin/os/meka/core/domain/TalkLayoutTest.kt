package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkLayoutTest {
    @Test
    fun aTalkIsAnythingButEnded() {
        assertFalse(TalkLayoutRules.talking(TalkPhase.ENDED))
        TalkPhase.entries.filter { it != TalkPhase.ENDED }.forEach { assertTrue(TalkLayoutRules.talking(it), it.name) }
    }

    @Test
    fun theBedsideClockWaitsForTheTalkToEnd() {
        assertTrue(TalkLayoutRules.bedside(shows = true, talking = false))
        assertFalse(TalkLayoutRules.bedside(shows = true, talking = true), "standing on the table mid-talk keeps the orb")
        assertFalse(TalkLayoutRules.bedside(shows = false, talking = false))
        assertFalse(TalkLayoutRules.bedside(shows = false, talking = true))
    }

    @Test
    fun onlyARealChangeDuringATalkIsNoted() {
        assertTrue(TalkLayoutRules.noteLayout("BOTTOM_BAR", "RAIL", talking = true))
        assertFalse(TalkLayoutRules.noteLayout("BOTTOM_BAR", "RAIL", talking = false))
        assertFalse(TalkLayoutRules.noteLayout("RAIL", "RAIL", talking = true))
        assertFalse(TalkLayoutRules.noteLayout(null, "RAIL", talking = true), "the first reading isn't a change")
    }

    @Test
    fun theLogSaysTheTalkKeptGoing() {
        val log = LiveTalkLog(0)
        log.layoutKept(65_000)
        assertEquals(listOf("1:05 layout changed, kept talking"), log.lines())
    }
}
