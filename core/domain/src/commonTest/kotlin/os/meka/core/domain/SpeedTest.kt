package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedTest {
    private val t0 = 1_792_144_800_000L

    @Test
    fun keepsTheWorstPerNameSlowestFirst() {
        val log = SpeedLog()
        log.record("Money view", 1_800, t0)
        log.record("Money view", 40, t0 + 1)
        log.record("Search", 120, t0 + 2)
        log.record("Calendar", 12, t0 + 3)
        val w = log.worst()
        assertEquals(listOf("Money view", "Search", "Calendar"), w.map { it.name })
        assertEquals(SpeedEntry("Money view", 1_800, 40, 2, 1), w[0])
        assertEquals(2, log.worst(2).size)
    }

    @Test
    fun anHourLaterANameStartsAgain() {
        val log = SpeedLog()
        log.record("Money view", 1_800, t0)
        log.record("Money view", 30, t0 + SpeedRules.WINDOW_MS)
        assertEquals(SpeedEntry("Money view", 30, 30, 1, 0), log.worst().single())
    }

    @Test
    fun saysTimesPlainly() {
        assertEquals("240 ms", SpeedRules.said(240))
        assertEquals("1.8 s", SpeedRules.said(1_780))
        assertEquals("12.0 s", SpeedRules.said(11_990))
        assertEquals("Money view · 1.8 s · 12 times, 3 slow", SpeedRules.line(SpeedEntry("Money view", 1_800, 50, 12, 3)))
        assertEquals("Search · 60 ms · 4 times", SpeedRules.line(SpeedEntry("Search", 60, 50, 4, 0)))
        assertEquals("Calendar · 60 ms", SpeedRules.line(SpeedEntry("Calendar", 60, 60, 1, 0)))
    }

    @Test
    fun healthNamesTheSlowestAndListsTheWorstTenAsInformation() {
        assertNull(SpeedRules.healthRow(null))
        assertNull(SpeedRules.healthRow(emptyList()))
        val slow = SpeedRules.healthRow(listOf(SpeedEntry("Money view", 1_800, 50, 12, 3), SpeedEntry("Search", 60, 50, 4, 0)))!!
        assertEquals("Slowest in the last hour: Money view · 1.8 s", slow.line)
        assertEquals("Money view · 1.8 s · 12 times, 3 slow\nSearch · 60 ms · 4 times", slow.detail)
        assertEquals(HealthState.OK, slow.state)
        assertFalse(slow.needsLook)
        val quick = SpeedRules.healthRow(listOf(SpeedEntry("Search", 60, 50, 4, 0)))!!
        assertEquals("Quick · nothing over 100 ms in the last hour", quick.line)
        val many = SpeedRules.healthRow((1..15).map { SpeedEntry("Part $it", 200L - it, 10, 1, 1) })!!
        assertEquals(10, many.detail!!.lines().size)
    }

    @Test
    fun theMoneyViewIsKeptOnlyWhenEveryInputMatchesAndItStillShows() {
        val view = Any()
        val inputs = listOf<Any?>(listOf(1, 2, 3), "Oct", 5L)
        assertTrue(MoneyViewMemo.same(inputs, listOf(listOf(1, 2, 3), "Oct", 5L), view, view))
        assertFalse(MoneyViewMemo.same(null, inputs, view, view))
        assertFalse(MoneyViewMemo.same(inputs, listOf(listOf(1, 2, 4), "Oct", 5L), view, view))
        assertFalse(MoneyViewMemo.same(inputs, inputs, view, Any()), "something else set the screen (a lock)")
        assertFalse(MoneyViewMemo.same(inputs, inputs, null, null))
    }
}
