package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.GiftRules
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

/** Gift planner through the facade (slice 1): typed on the Fold, on the Mac too; the heads-ups, Today's line and the week ahead. */
class GiftsFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()
    private val day = 24 * 3_600_000L

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aBirthdayTypedOnTheFoldRemindsOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(GiftRules.EMPTY_LINE, fold.giftsView.value.summary)
        assertEquals("Added Mum's birthday · Sat 24 Oct · in 14 days · turns 60 · order by Tue 20 Oct", fold.addOccasion("Mum's birthday 24 Oct 1966").line)
        assertTrue(fold.addOccasion("birthday soon").refused)
        val id = fold.giftsView.value.rows.single().id
        assertTrue(fold.addGiftIdea(id, "Silk scarf"))
        assertEquals("Next: Mum's birthday · Sat 24 Oct", fold.giftsView.value.summary)
        assertNull(fold.today.value.gifts)

        fold.syncNow(); mac.syncNow()
        val row = mac.giftsView.value.rows.single()
        assertEquals(listOf("Silk scarf"), row.ideas.map { it.text })
        assertTrue(row.noteLit)
        fun gifts(g: os.meka.core.domain.GovernorResult) = g.post.filter { it.source == NoticeSource.GIFTS }
        // Two weeks ahead, 10:00 (past 09:00): the heads-up.
        val first = mac.governNotifications(null, DeviceAlerts.ALL)
        assertEquals(listOf("Mum's birthday in 2 weeks · Sat 24 Oct" to "Ideas: Silk scarf · order by Tue 20 Oct"), gifts(first).map { it.title to it.text })

        // Tuesday 20 Oct, 10:00: the order-by day, on Today and in a heads-up.
        now += 10 * day
        mac.tick()
        assertEquals("Order Mum's gift today · birthday Sat 24 Oct", mac.today.value.gifts?.text)
        val order = mac.governNotifications(first.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf("Order Mum's gift today"), gifts(order).map { it.title })
        // The week ahead (from Tuesday) shows Saturday.
        val satLine = mac.weekAheadView.value.days.first { it.label.startsWith("Sat") }
        assertEquals(listOf("Mum's birthday · turns 60"), satLine.lines.filter { it.kind == WeekAheadKind.OCCASION }.map { it.text })

        // Sorted on the Mac: the Fold's Today line goes.
        assertTrue(mac.setGiftSorted(id, true))
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertNull(fold.today.value.gifts)
        assertEquals("✓ Gift sorted", fold.giftsView.value.rows.single().note)

        assertTrue(fold.removeOccasion(id))
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.giftsView.value.isEmpty)
    }
}
