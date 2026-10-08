package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun expressiveIsTheDefaultWhenTheDeviceAnimates() {
        assertEquals(MotionChoice.EXPRESSIVE, MotionRules.effective(null, systemOff = false))
        assertTrue(MotionRules.expressive(null, false))
        assertFalse(MotionRules.reduced(null, false))
        assertEquals("Bigger entrances and bouncier springs", MotionRules.line(null, false, mac = false))
    }

    @Test
    fun withNothingChosenTheDevicesAnimationsOffNoLongerStillMeka() {
        // Meka, 2026-10-08 20:58: no choice + animator scale 0 kept MEKA still; now Expressive plays, its chip lit.
        assertEquals(MotionChoice.EXPRESSIVE, MotionRules.effective(null, systemOff = true))
        assertFalse(MotionRules.reduced(null, true))
        assertTrue(MotionRules.expressive(null, true))
        assertEquals("Bigger entrances and bouncier springs", MotionRules.line(null, true, mac = false))
        assertEquals("Bigger entrances and bouncier springs", MotionRules.line(null, true, mac = true))
        assertEquals(MotionChoice.EXPRESSIVE, MotionRules.lit(null))
        assertEquals(MotionChoice.OFF, MotionRules.lit(MotionChoice.OFF))
    }

    @Test
    fun aChoiceWinsOverTheDevice() {
        for (systemOff in listOf(false, true)) {
            assertEquals(MotionChoice.EXPRESSIVE, MotionRules.effective(MotionChoice.EXPRESSIVE, systemOff))
            assertEquals(MotionChoice.SUBTLE, MotionRules.effective(MotionChoice.SUBTLE, systemOff))
            assertEquals(MotionChoice.OFF, MotionRules.effective(MotionChoice.OFF, systemOff))
            assertFalse(MotionRules.reduced(MotionChoice.SUBTLE, systemOff))
            assertFalse(MotionRules.expressive(MotionChoice.SUBTLE, systemOff))
            assertTrue(MotionRules.reduced(MotionChoice.OFF, systemOff))
            assertEquals("Small, quick movement", MotionRules.line(MotionChoice.SUBTLE, systemOff, mac = false))
            assertEquals("Cross-fades only · nothing moves", MotionRules.line(MotionChoice.OFF, systemOff, mac = true))
        }
    }

    @Test
    fun storedIdsReadBackAndUnknownOnesMeanNothingChosen() {
        assertEquals(MotionChoice.EXPRESSIVE, MotionRules.choice("expressive"))
        assertEquals(MotionChoice.SUBTLE, MotionRules.choice("subtle"))
        assertEquals(MotionChoice.OFF, MotionRules.choice("off"))
        assertNull(MotionRules.choice(null))
        assertNull(MotionRules.choice(""))
        assertNull(MotionRules.choice("bouncy"))
        assertEquals(listOf("Expressive", "Subtle", "Off"), MotionChoice.entries.map { it.label })
    }

    @Test
    fun theCardIsRetiredNowThatTheDeviceNeverStillsMeka() {
        assertNull(MotionRules.systemCard(null, systemOff = true, mac = false))
        assertNull(MotionRules.systemCard(null, true, mac = true))
        assertNull(MotionRules.systemCard(null, systemOff = false, mac = false))
        // Either button makes a choice, so the card doesn't come back.
        assertNull(MotionRules.systemCard(MotionRules.CARD_TURN_ON, true, mac = false))
        assertNull(MotionRules.systemCard(MotionRules.CARD_KEEP_STILL, true, mac = false))
        assertEquals(MotionChoice.EXPRESSIVE, MotionRules.effective(MotionRules.CARD_TURN_ON, true))
        assertEquals(MotionChoice.OFF, MotionRules.effective(MotionRules.CARD_KEEP_STILL, true))
    }
}
