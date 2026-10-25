package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeelTest {
    private val t0 = 1_792_144_800_000L

    @Test
    fun keepsTheWorstPerMomentWithWhereItWas() {
        val log = FeelLog()
        log.record(FeelMoment.TAB, 90, "Calendar", t0)
        log.record(FeelMoment.TAB, 320, "Ask", t0 + 1)
        log.record(FeelMoment.TAB, 60, "Today", t0 + 2)
        log.record(FeelMoment.COLD_START, 1_200, null, t0 + 3)
        val all = log.all()
        assertEquals(listOf(FeelMoment.COLD_START, FeelMoment.TAB), all.map { it.moment }, "cold start first, always the same order")
        assertEquals(FeelEntry(FeelMoment.TAB, 320, "Ask", 60, 3, 1), all[1])
    }

    @Test
    fun implausibleTimesAreDropped() {
        val log = FeelLog()
        log.record(FeelMoment.TAB, -5, null, t0)
        log.record(FeelMoment.TAB, FeelRules.MAX_MS + 1, null, t0)
        log.record(FeelMoment.COLD_START, 45_000, null, t0) // the process was already up for a notification
        assertTrue(log.all().isEmpty())
        assertTrue(FeelRules.plausible(FeelMoment.COLD_START, FeelRules.COLD_START_MAX_MS))
        assertFalse(FeelRules.plausible(FeelMoment.COLD_START, FeelRules.COLD_START_MAX_MS + 1))
    }

    @Test
    fun aDayLaterAMomentStartsAgain() {
        val log = FeelLog()
        log.record(FeelMoment.SCREEN, 900, "Money", t0)
        log.record(FeelMoment.SCREEN, 80, "Money", t0 + FeelRules.WINDOW_MS)
        assertEquals(FeelEntry(FeelMoment.SCREEN, 80, "Money", 80, 1, 0), log.all().single())
    }

    @Test
    fun linesSayTheAim() {
        assertEquals("Tab switch · 320 ms (Ask) · aim 150 ms · 3 times, 1 over", FeelRules.line(FeelEntry(FeelMoment.TAB, 320, "Ask", 60, 3, 1)))
        assertEquals("Cold start to Today · 1.2 s · aim 1.5 s", FeelRules.line(FeelEntry(FeelMoment.COLD_START, 1_200, null, 1_200, 1, 0)))
        assertEquals("First content · 90 ms (Money) · aim 300 ms · 2 times", FeelRules.line(FeelEntry(FeelMoment.SCREEN, 90, "Money", 40, 2, 0)))
    }

    @Test
    fun healthLeadsWithWhatFeelsSlow() {
        val tab = FeelEntry(FeelMoment.TAB, 320, "Ask", 60, 3, 1)
        val cold = FeelEntry(FeelMoment.COLD_START, 2_000, null, 2_000, 1, 1)
        // 320/150 is further over than 2000/1500: the tab leads.
        assertEquals(tab, FeelRules.worstOver(listOf(cold, tab)))
        val work = listOf(SpeedEntry("Money view", 1_800, 50, 12, 3))
        val row = SpeedRules.healthRow(work, listOf(cold, tab))!!
        assertEquals("Slower than it should be: Tab switch · 320 ms (aim 150 ms)", row.line)
        assertEquals(
            "How it feels · last day\nCold start to Today · 2.0 s · aim 1.5 s\nTab switch · 320 ms (Ask) · aim 150 ms · 3 times, 1 over\n\n" +
                "Slowest work · last hour\nMoney view · 1.8 s · 12 times, 3 slow",
            row.detail,
        )
        assertEquals(HealthState.OK, row.state, "information, never a nag")
        // Within its aims, the slow core work still leads.
        val fine = SpeedRules.healthRow(work, listOf(FeelEntry(FeelMoment.TAB, 90, null, 90, 1, 0)))!!
        assertEquals("Slowest in the last hour: Money view · 1.8 s", fine.line)
        // Only feel timed so far.
        val only = SpeedRules.healthRow(null, listOf(FeelEntry(FeelMoment.TAB, 90, null, 90, 1, 0)))!!
        assertEquals("Quick · everything within its aim today", only.line)
        assertEquals("How it feels · last day\nTab switch · 90 ms · aim 150 ms", only.detail)
        assertNull(SpeedRules.healthRow(null, emptyList()))
    }
}
