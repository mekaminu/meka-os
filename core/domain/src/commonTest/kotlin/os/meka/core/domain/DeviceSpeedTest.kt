package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSpeedTest {
    private val now = 1_791_476_100_000L
    private val hour = 60 * 60_000L

    @Test
    fun dueOnceADay() {
        assertTrue(DeviceSpeedRules.due(null, now))
        assertFalse(DeviceSpeedRules.due(now - hour, now))
        assertFalse(DeviceSpeedRules.due(now - 19 * hour, now))
        assertTrue(DeviceSpeedRules.due(now - 20 * hour, now))
        assertTrue(DeviceSpeedRules.due(now + hour, now)) // the clock moved back
    }

    @Test
    fun theLastDaysTurnsBecomeCountsAveragesAndTheSlowest() {
        val s = DeviceSpeedRules.summary(
            listOf(
                TurnTrace(TurnKind.HELLO, now - hour, 300),
                TurnTrace(TurnKind.HELLO, now - hour, 600),
                TurnTrace(TurnKind.TALK, now - hour, 1_200),
                TurnTrace(TurnKind.TALK, now - hour, 2_400),
                TurnTrace(TurnKind.TALK, now - 25 * hour, 9_000),
                TurnTrace(TurnKind.TALK, now - hour, null),
            ),
            "phone", now,
        )!!
        assertEquals(DeviceSpeedSummary("phone", now, 2, 450, 600, 2, 1_800, 2_400), s)
        assertEquals(
            "Phone · 2 hellos avg 0.5 s, slowest 0.6 s (aim 0.6 s) · 2 answers avg 1.8 s, slowest 2.4 s (aim 1.5 s), over aim",
            DeviceSpeedRules.line(s),
        )
        assertTrue(DeviceSpeedRules.overAim(s))
        assertNull(DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.TALK, now - 25 * hour, 900)), "phone", now))
    }

    @Test
    fun oneKindAloneAndLabelsAreLettersOnly() {
        val s = DeviceSpeedRules.summary(listOf(TurnTrace(TurnKind.HELLO, now - hour, 400)), "Mac", now)!!
        assertEquals("Mac · 1 hello avg 0.4 s, slowest 0.4 s (aim 0.6 s)", DeviceSpeedRules.line(s))
        assertFalse(DeviceSpeedRules.overAim(s))
        assertEquals("Macscript", DeviceSpeedRules.label("Mac<script>1"))
        assertEquals("device", DeviceSpeedRules.label("123"))
    }
}
