package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CountdownRules
import os.meka.core.domain.LifeTile
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Countdowns through the facade (slice 1): typed on the Fold, seen on the Mac; Gifts' birthdays, Sleeps, the one on Today. */
class CountdownsFacadeTest {
    private var now = 1_794_477_600_000L // Thu 12 Nov 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()
    private val day = 24 * 3_600_000L

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aCountdownTypedOnTheFoldCountsDownOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        // Christmas is there before anything is typed, and on Ask's Life tile.
        assertEquals("Christmas", fold.countdownsView.value.hero?.name)
        assertEquals("43", fold.countdownsView.value.hero?.number)
        assertEquals("Christmas in 43 days", fold.lifeLines.value.of(LifeTile.COUNTDOWNS)?.text)

        assertEquals("Added Holiday · Sat 13 Feb · 93 days to go", fold.addCountdown("Holiday 13 Feb").line)
        assertTrue(fold.addCountdown("Holiday soon").refused)
        // A birthday in Gifts appears by itself.
        fold.addOccasion("Sam's birthday 2 Dec 2017")
        val v = fold.countdownsView.value
        assertEquals("Sam's birthday", v.hero?.name)
        assertEquals("turns 9", v.hero?.extra)
        assertEquals(listOf("Christmas", "Holiday"), v.rows.map { it.name })

        // Holiday on Today and Sleeps, chosen on the Fold, reach the Mac.
        val holiday = v.rows.first { it.name == "Holiday" }.key
        assertTrue(fold.showCountdownOnToday(holiday))
        assertTrue(fold.setCountdownSleeps(true))
        fold.syncNow(); mac.syncNow()
        val m = mac.countdownsView.value
        assertEquals("Holiday", m.hero?.name)
        assertTrue(m.heroOnToday)
        assertTrue(m.sleeps)
        assertEquals("93 sleeps", "${m.hero?.number} ${m.hero?.unit}")
        assertEquals("Holiday in 93 sleeps", mac.lifeLines.value.of(LifeTile.COUNTDOWNS)?.text)

        // The next day it's one fewer.
        now += day
        mac.tick()
        assertEquals("92", mac.countdownsView.value.hero?.number)

        // Christmas hidden on the Mac, then shown again; the holiday removed for good.
        assertTrue(mac.removeCountdown(CountdownRules.CHRISTMAS_KEY))
        assertEquals(1, mac.countdownsView.value.hiddenCount)
        assertTrue(mac.showHiddenCountdowns())
        assertFalse(mac.showHiddenCountdowns())
        assertTrue(mac.removeCountdown(holiday))
        mac.syncNow(); fold.syncNow()
        val f = fold.countdownsView.value
        assertFalse(f.heroOnToday)
        assertEquals("Sam's birthday", f.hero?.name)
        assertEquals(listOf("Christmas"), f.rows.map { it.name })
    }
}
