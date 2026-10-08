package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionCheckTest {
    @Test
    fun phoneRowsSayWhatMekaSees() {
        val c = MotionCheckRules.phone(MotionChoice.EXPRESSIVE, 1f, powerSave = false)
        assertEquals(
            listOf(
                MotionCheckRow("MEKA Motion", "Expressive"),
                MotionCheckRow("Phone's animation scale", "1×"),
                MotionCheckRow("Power saving", "Off"),
            ),
            c.rows,
        )
        assertEquals("Animations on · Expressive", c.result)
        assertTrue(c.on)
        assertNull(c.fix)
    }

    @Test
    fun removeAnimationsWithNothingChosenStillPlaysExpressive() {
        // Meka, 2026-10-08 20:58: what kept MEKA still; now nothing chosen means Expressive whatever the phone says.
        val c = MotionCheckRules.phone(null, 0f, powerSave = true)
        assertEquals("Not chosen", c.rows[0].value)
        assertEquals("Off (Remove animations)", c.rows[1].value)
        assertEquals("On", c.rows[2].value)
        assertEquals("Animations on · Expressive · playing even with Remove animations on", c.result)
        assertTrue(c.on)
        assertNull(c.fix)
    }

    @Test
    fun aChoicePlaysEvenWithRemoveAnimationsOrPowerSaving() {
        assertEquals("Animations on · Expressive · playing even with Remove animations on",
            MotionCheckRules.phone(MotionChoice.EXPRESSIVE, 0f, powerSave = true).result)
        assertEquals("Animations on · Subtle · playing even with power saving on",
            MotionCheckRules.phone(MotionChoice.SUBTLE, 1f, powerSave = true).result)
        // Nothing chosen and the scale fine: Expressive by default, power saving doesn't still it.
        val c = MotionCheckRules.phone(null, 1f, powerSave = true)
        assertTrue(c.on)
        assertEquals("Animations on · Expressive · playing even with power saving on", c.result)
    }

    @Test
    fun mekaOffSaysSo() {
        val c = MotionCheckRules.phone(MotionChoice.OFF, 1f, powerSave = false)
        assertEquals("Animations off because MEKA's Motion is Off — tap to turn them on", c.result)
        assertEquals(MotionChoice.EXPRESSIVE, c.fix)
    }

    @Test
    fun scaleLabels() {
        assertEquals("1×", MotionCheckRules.scaleLabel(1f))
        assertEquals("0.5×", MotionCheckRules.scaleLabel(0.5f))
        assertEquals("1.5×", MotionCheckRules.scaleLabel(1.5f))
        assertEquals("10×", MotionCheckRules.scaleLabel(10f))
        assertEquals("Off (Remove animations)", MotionCheckRules.scaleLabel(0f))
        assertEquals("Unknown", MotionCheckRules.scaleLabel(null))
    }

    @Test
    fun macCheck() {
        val off = MotionCheckRules.mac(MotionChoice.OFF, reduceMotion = true, lowPower = false)
        assertEquals(listOf("MEKA Motion", "Reduce Motion", "Low Power Mode"), off.rows.map { it.label })
        assertEquals("Animations off because MEKA's Motion is Off — click to turn them on", off.result)
        assertEquals("Animations on · Expressive · playing even with Reduce Motion on",
            MotionCheckRules.mac(null, reduceMotion = true, lowPower = false).result)
        assertEquals("Animations on · Subtle · playing even with Reduce Motion on",
            MotionCheckRules.mac(MotionChoice.SUBTLE, reduceMotion = true, lowPower = true).result)
        assertEquals("Animations on · Expressive · playing even in Low Power Mode",
            MotionCheckRules.mac(null, reduceMotion = false, lowPower = true).result)
    }

    @Test
    fun versionLine() {
        assertEquals("MEKA 0.1.412 · build 412", AppUpdateRules.versionLine("0.1.412", 412, null))
        assertEquals("MEKA 0.1.412 · build 412 · up to date", AppUpdateRules.versionLine("0.1.412", 412, 412))
        assertEquals("MEKA 0.1.412 · build 412 · up to date", AppUpdateRules.versionLine("0.1.412", 412, 400))
        assertEquals("MEKA 0.1.412 · build 412 · update ready (build 415)", AppUpdateRules.versionLine("0.1.412", 412, 415))
    }
}
