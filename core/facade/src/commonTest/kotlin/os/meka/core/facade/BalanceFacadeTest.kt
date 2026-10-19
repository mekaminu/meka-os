package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.BalanceArea
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Balance view through the facade (slice 1): the review's week counted, targets set on one app reach the other. */
class BalanceFacadeTest {
    private var now = 1_792_163_100_000L // Fri 16 Oct 2026, 16:05 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theReviewsWeekIsCountedAndATargetSetOnTheFoldReachesTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.tick()
        val v = fold.balanceView.value
        assertEquals(fold.reviewView.value.weekStart, v.weekStart)
        assertTrue(v.isCurrent)
        // Mon–Wed 8 h 30, Thursday's short day 6 h 30, Friday so far 7 h 05.
        val work = v.rows.first { it.area == BalanceArea.WORK }
        assertEquals("39 h 05 of your 40 h 30", work.line)
        assertEquals("0 of 3 targets met so far", v.line)

        assertEquals("Family: 10 h a week · on both devices", fold.setBalanceTarget("FAMILY", 600))
        assertNull(fold.setBalanceTarget("FAMILY", 600))
        assertNull(fold.setBalanceTarget("SLEEP", 600))
        assertEquals("0 h of 10 h", fold.balanceView.value.rows.first { it.area == BalanceArea.FAMILY }.line)

        fold.syncNow(); mac.syncNow()
        assertEquals("0 h of 10 h", mac.balanceView.value.rows.first { it.area == BalanceArea.FAMILY }.line)

        // Last week in the review: the balance follows it.
        mac.showReviewWeek(-1)
        assertEquals(mac.reviewView.value.weekStart, mac.balanceView.value.weekStart)
        assertEquals("40 h 30 of your 40 h 30", mac.balanceView.value.rows.first { it.area == BalanceArea.WORK }.line)
    }
}
