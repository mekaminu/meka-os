package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Sunday week ahead through the facade: next week's view, Sunday's 18:00 heads-up and Today's line. */
class WeekAheadFacadeTest {
    private var now = 1_791_734_400_000L // Sun 11 Oct 2026, 17:00 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun sundayEveningBringsNextWeek() = runTest {
        val fold = core()
        val v = fold.weekAheadView.value
        assertEquals(20_738L, v.weekStart) // Mon 12 Oct
        assertEquals("Next week", v.title)
        assertEquals(7, v.days.size)
        assertEquals("Office 09:00–17:30", v.days.first().dayLine)
        assertNull(fold.today.value.weekAhead)
        val early = fold.governNotifications(null, DeviceAlerts.ALL)
        assertTrue(early.post.none { it.source == NoticeSource.WEEK_AHEAD })

        now += 3_600_000L // 18:00: the evening digest goes; the week ahead waits five minutes to arrive on its own
        val six = fold.governNotifications(early.stateEncoded, DeviceAlerts.ALL)
        assertTrue(six.post.none { it.source == NoticeSource.WEEK_AHEAD })
        now += 5 * 60_000L
        val r = fold.governNotifications(six.stateEncoded, DeviceAlerts.ALL)
        val n = r.post.single { it.source == NoticeSource.WEEK_AHEAD }
        assertEquals("Your week ahead", n.title)
        assertEquals(NoticeTarget.WEEK_AHEAD, n.target)
        assertEquals("Your week ahead · ${fold.weekAheadView.value.summary}", fold.today.value.weekAhead?.text)
    }

    @Test
    fun theSundayTimeSettingSyncsAndMovesTheHeadsUp() = runTest {
        val fold = core()
        assertEquals("18:00", fold.weekAheadSettingView.value.timeLabel)
        assertEquals("On · Sundays at 19:00 · Listen reads it out", fold.setWeekAhead(true, 19 * 60))
        assertNull(fold.setWeekAhead(true, 19 * 60)) // nothing changed
        assertNull(fold.setWeekAhead(true, 23 * 60 + 30)) // out of range
        assertEquals(19 * 60, fold.weekAheadSettingView.value.minute)

        now += 3_600_000L + 5 * 60_000L // 18:05: not yet
        val early = fold.governNotifications(null, DeviceAlerts.ALL)
        assertTrue(early.post.none { it.source == NoticeSource.WEEK_AHEAD })
        assertNull(fold.today.value.weekAhead)
        now += 55 * 60_000L // 19:00, on the hour (no digest then), with Listen
        val r = fold.governNotifications(early.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf(os.meka.core.domain.NoticeAction.LISTEN), r.post.single { it.source == NoticeSource.WEEK_AHEAD }.actions)

        // Off: no heads-up next Sunday either, and no Today line; the pane still has the week.
        assertEquals("Off · no Sunday heads-up · the week ahead is still here", fold.setWeekAhead(false, 19 * 60))
        assertNull(fold.today.value.weekAhead)
        assertEquals("Next week", fold.weekAheadView.value.title)
    }
}
