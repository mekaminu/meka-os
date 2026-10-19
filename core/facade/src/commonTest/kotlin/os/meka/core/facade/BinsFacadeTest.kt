package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.BinRules
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.WeekAheadKind
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bin days through the facade (slice 1): typed on the Fold, on the Mac too; the evening before on Today, the heads-up and the week ahead. */
class BinsFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun binsTypedOnTheFoldGoOutTheEveningBeforeOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(BinRules.EMPTY_LINE, fold.binsView.value.summary)
        assertEquals("Added Black bin · every other Tuesday · next Tue 13 Oct", fold.addBin("Black bin Tue fortnightly from 13 Oct").line)
        assertEquals("Added Food caddy · every Tuesday · next Tue 13 Oct", fold.addBin("Food caddy every Tuesday").line)
        assertTrue(fold.addBin("the bins").refused)
        assertEquals("Next: Black bin and food caddy · Tue 13 Oct", fold.binsView.value.summary)
        assertNull(fold.today.value.bins)

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Black bin", "Food caddy"), mac.binsView.value.rows.map { it.name })
        // The week ahead (from Saturday, next week) shows Tuesday's collection.
        val tue = mac.weekAheadView.value.days.first { it.label.startsWith("Tue") }
        assertEquals(listOf("Black bin and food caddy"), tue.lines.filter { it.kind == WeekAheadKind.BINS }.map { it.text })

        // Monday 16:00: Today says so; 19:00: the heads-up.
        now += 2 * 24 * 3_600_000L + 6 * 3_600_000L
        mac.tick()
        assertEquals("Black bin and food caddy out tonight", mac.today.value.bins?.text)
        fun bins(g: os.meka.core.domain.GovernorResult) = g.post.filter { it.source == NoticeSource.BINS }
        val four = mac.governNotifications(null, DeviceAlerts.ALL)
        assertTrue(bins(four).isEmpty())
        // 18:00: the evening digest goes (nothing of the bins yet); 19:00: the heads-up arrives on its own.
        now += 2 * 3_600_000L
        val six = mac.governNotifications(four.stateEncoded, DeviceAlerts.ALL)
        assertTrue(bins(six).isEmpty())
        now += 3_600_000L
        val seven = mac.governNotifications(six.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf("Black bin and food caddy out tonight" to "Collected tomorrow, Tue 13 Oct"), bins(seven).map { it.title to it.text })
        assertTrue(bins(mac.governNotifications(seven.stateEncoded, DeviceAlerts.ALL)).isEmpty())

        // Removed on the Mac: gone on the Fold.
        assertTrue(mac.removeBin(mac.binsView.value.rows.first().id))
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("Food caddy"), fold.binsView.value.rows.map { it.name })
    }

    @Test
    fun aOneOffTypedOnTheMacMovesTheFoldsCollectionAndRemoveTakesItBack() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addBin("Food caddy every Tuesday")
        fold.syncNow(); mac.syncNow()
        assertEquals("Moved Food caddy to Wed 14 Oct instead of Tue 13 Oct", mac.addBin("Food caddy Wed 14 Oct instead of 13 Oct").line)
        assertTrue(mac.addBin("Food caddy Wed 14 Oct instead of 15 Oct").refused)
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("Wed 14 Oct instead of Tue 13 Oct"), fold.binsView.value.oneOffs.map { it.line })
        assertEquals("Next: Food caddy · Wed 14 Oct", fold.binsView.value.summary)
        val wed = fold.weekAheadView.value.days.first { it.label.startsWith("Wed") }
        assertEquals(listOf("Food caddy · moved from Tue 13 Oct"), wed.lines.filter { it.kind == WeekAheadKind.BINS }.map { it.text })

        assertTrue(fold.removeBinMove(fold.binsView.value.oneOffs.single().id))
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.binsView.value.oneOffs.isEmpty())
        assertEquals("Next: Food caddy · Tue 13 Oct", mac.binsView.value.summary)
    }

    @Test
    fun theReminderTimeSetOnTheFoldMovesTheMacsHeadsUpAndTheWatchShowsTonightsBins() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addBin("Food caddy every Tuesday")
        assertEquals("Reminder at 20:00 · on both devices", fold.setBinReminder(20 * 60))
        assertNull(fold.setBinReminder(20 * 60))
        assertNull(fold.setBinReminder(23 * 60))
        fold.syncNow(); mac.syncNow()
        assertEquals(20 * 60, mac.binsView.value.remindMinute)

        // Monday 16:00: the watch shows tonight's bins, not lit yet.
        now += 2 * 24 * 3_600_000L + 6 * 3_600_000L
        mac.tick()
        val early = mac.watchHome().bins
        assertEquals("OUT TONIGHT" to "Food caddy", early?.label to early?.line)
        assertEquals(false, early?.lit)
        fun bins(g: os.meka.core.domain.GovernorResult) = g.post.filter { it.source == NoticeSource.BINS }
        val four = mac.governNotifications(null, DeviceAlerts.ALL)
        now += 3 * 3_600_000L
        val seven = mac.governNotifications(four.stateEncoded, DeviceAlerts.ALL)
        assertTrue(bins(seven).isEmpty(), "not at 19:00 any more")
        now += 3_600_000L
        mac.tick()
        assertEquals(true, mac.watchHome().bins?.lit)
        val eight = mac.governNotifications(seven.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf("Food caddy out tonight"), bins(eight).map { it.title })
    }
}
