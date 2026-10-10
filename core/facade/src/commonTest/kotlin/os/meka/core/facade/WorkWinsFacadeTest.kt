package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.WinRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Work wins through the facade (slice 1): kept in the review's week, synced, and Friday's question. */
class WorkWinsFacadeTest {
    private var now = 1_792_163_100_000L // Fri 16 Oct 2026, 16:05 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aWinKeptOnTheFoldReachesTheMacsReviewAndStopsFridaysQuestion() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.tick()
        assertTrue(fold.winsView.value.asking)
        assertEquals(WinRules.NONE_THIS_WEEK, fold.winsView.value.weekLine)

        assertFalse(fold.addWin("Closed the Henley deal").refused)
        assertEquals("1 win this week", fold.winsView.value.weekLine)
        assertFalse(fold.winsView.value.asking)

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Closed the Henley deal"), mac.winsView.value.week.map { it.text })

        // Last week in the review: the hint and line say "that week", and a win added is dated that Friday.
        mac.showReviewWeek(-1)
        assertEquals(WinRules.HINT_PAST_WEEK, mac.winsView.value.hint)
        assertEquals("Kept for Fri 9 Oct: Fixed the build", mac.addWin("Fixed the build").line)
        assertEquals("This quarter · Oct – Dec · 2 wins", mac.winsView.value.quarterTitle)

        assertTrue(mac.removeWin(mac.winsView.value.week.single().id))
        assertEquals(1, mac.winsView.value.quarterCount)
    }

    @Test
    fun withNoWinFridaysQuestionGoesThroughTheGovernor() = runTest {
        val fold = core("android")
        fold.tick()
        val r = fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val q = r.post.filter { it.source == NoticeSource.WINS }
        assertEquals(WinRules.NOTICE_TITLE, q.single().title)
    }
}
