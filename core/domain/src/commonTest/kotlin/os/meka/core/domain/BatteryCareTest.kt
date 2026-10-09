package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryCareTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    /** UTC as local time; day 20000 is a plain day. */
    private val utc: (Long) -> Int = { ((it / min) % (24 * 60)).toInt() }
    private fun at(h: Int, m: Int = 0, d: Long = 20_000) = d * day + h * hour + m * min

    /** A beat every 15 minutes from [from] to [to]. */
    private fun every15(from: Long, to: Long) = (from..to step 15 * min).toList()

    private val samsung = BatteryFacts(exempt = false, samsung = true)
    private val samsungExempt = BatteryFacts(exempt = true, samsung = true)

    @Test
    fun beatsAreSpacedSortedAndPruned() {
        val now = at(12)
        val beats = BatteryCareRules.record(listOf(now - 3 * day, now - 10 * min), now)
        assertEquals(listOf(now - 10 * min, now), beats)
        // Under five minutes after the last one: unchanged.
        assertEquals(beats, BatteryCareRules.record(beats, now + 4 * min))
        assertEquals(beats + (now + 5 * min), BatteryCareRules.record(beats, now + 5 * min))
        assertEquals(listOf(1L, 22L), BatteryCareRules.decode(BatteryCareRules.encode(listOf(22L, 1L))))
        assertEquals(listOf(5L), BatteryCareRules.decode("x,5,,-3"))
        assertTrue(BatteryCareRules.decode(null).isEmpty())
    }

    @Test
    fun onlyWakingHoursCount() {
        // 22:00 → 08:00 next day: 30 min before 22:30 and an hour after 07:00.
        assertEquals(90 * min, BatteryCareRules.wakingMs(at(22), at(8, d = 20_001), utc))
        assertEquals(3 * hour, BatteryCareRules.wakingMs(at(9), at(12), utc))
        assertEquals(0L, BatteryCareRules.wakingMs(at(23), at(6, d = 20_001), utc))
    }

    @Test
    fun aQuietNightIsNotAStop() {
        val beats = every15(at(18), at(23)) + every15(at(7, 10, 20_001), at(9, d = 20_001))
        assertNull(BatteryCareRules.lastStop(beats, at(9, d = 20_001), utc))
        val view = BatteryCareRules.view(samsungExempt, beats, at(9, d = 20_001), utc, null)
        assertEquals(BatteryCareStatus.OK, view.status)
        assertNull(view.line)
    }

    @Test
    fun aSilentAfternoonIsAStop() {
        val beats = every15(at(9, 5), at(14, 5)) + every15(at(17, 20), at(18))
        val gap = BatteryCareRules.lastStop(beats, at(17, 50), utc)
        assertEquals(BatteryGap(at(14, 5), at(17, 20)), gap)
        val view = BatteryCareRules.view(samsungExempt, beats, at(18), utc, null)
        assertEquals(BatteryCareStatus.STOPPED, view.status)
        assertTrue(view.critical)
        assertEquals("MEKA was stopped 14:05–17:20, likely by Samsung's battery saver · Keep MEKA awake", view.line)
        assertTrue(view.openSamsung)
        assertFalse(view.allowInAndroid)
        assertEquals(BatteryCareRules.SAMSUNG_STEPS, view.steps)
        // Under two waking hours: not a stop.
        assertNull(BatteryCareRules.lastStop(every15(at(9), at(14)) + at(15, 55), at(16), utc))
    }

    @Test
    fun gotItPutsThatStopAwayAndAnOldStopFades() {
        val beats = every15(at(9), at(10)) + every15(at(13), at(14))
        val gap = BatteryCareRules.lastStop(beats, at(14), utc)!!
        val gone = BatteryCareRules.view(samsungExempt, beats, at(14), utc, dismissedEndMs = gap.endMs)
        assertNull(gone.line)
        // Not exempt: the quieter line takes its place.
        val quieter = BatteryCareRules.view(samsung, beats, at(14), utc, dismissedEndMs = gap.endMs)
        assertEquals(BatteryCareStatus.NOT_EXEMPT, quieter.status)
        assertEquals("Samsung may put MEKA to sleep · Keep MEKA awake", quieter.line)
        assertFalse(quieter.critical)
        assertTrue(quieter.allowInAndroid)
        // A newer stop shows again.
        val later = beats + every15(at(17), at(17, 30))
        assertEquals(BatteryCareStatus.STOPPED, BatteryCareRules.view(samsungExempt, later, at(17, 30), utc, gap.endMs).status)
        // More than a day ago: not shown.
        val old = beats + every15(at(14, 15), at(15, d = 20_001))
        assertNull(BatteryCareRules.lastStop(old, at(15, d = 20_001), utc))
    }

    @Test
    fun anotherPhoneGetsAndroidsWords() {
        val other = BatteryFacts(exempt = false, samsung = false)
        val view = BatteryCareRules.view(other, listOf(at(9)), at(9), utc, null)
        assertEquals("Android may pause MEKA · Keep MEKA awake", view.line)
        assertFalse(view.openSamsung)
        assertEquals(BatteryCareRules.ANDROID_STEPS, view.steps)
        assertEquals("09:05", BatteryCareRules.clock(545))
    }
}
