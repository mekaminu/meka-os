package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.TeamRules
import os.meka.core.domain.TeamStatus
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Your team through the facade (slice 1): what the Fold posted today shows on the Mac's team once synced. */
class TeamFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun notice(key: String, source: NoticeSource, title: String) = Notice(
        key = key, source = source, tier = NoticeTier.HEADS_UP, title = title, text = "", atMs = now, target = NoticeTarget.TODAY,
    )

    @Test
    fun todaysRemindersShowOnBothAppsTeams() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val quiet = mac.teamView()
        assertEquals(listOf("Obi", "Inbox", "Calls", "Money", "Kestrel"), quiet.cards.map { it.title })
        assertTrue(quiet.cards.filter { it.title != "Kestrel" }.all { it.today == TeamRules.NOTHING_YET })
        assertEquals(TeamStatus.NOT_SET_UP, quiet.card("money")!!.status)
        fold.notificationsPosted(listOf(
            notice("t1", NoticeSource.TASK_REMINDER, "Call the garage"),
            notice("m1", NoticeSource.BILLS, "Water bill due Friday"),
        ))
        fold.syncNow()
        mac.syncNow()
        val v = mac.teamView()
        assertEquals("1 reminder", v.card("obi")!!.today)
        assertEquals("1 heads-up", v.card("money")!!.today)
        assertEquals(1, v.card("obi")!!.log.size)
        assertEquals("2", v.tiles.last().value)
        assertEquals(TeamStatus.WORKING, v.card("money")!!.status, "a heads-up today is Money at work, never Not set up")
        assertEquals("2", v.tiles.first().value, "WORKING is the cards that say Working")
        assertEquals(fold.teamView().cards.map { it.today }, v.cards.map { it.today })
    }

    /** Slice 2: Pause for today on the Fold shows on the Mac, holds Money's heads-ups there too, and ends at midnight. */
    @Test
    fun aPauseOnTheFoldHoldsTheMacsMoneyHeadsUpsTillMidnight() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val v = fold.setTeamPause("money", true)
        assertTrue(v.card("money")!!.paused)
        assertEquals(TeamStatus.PAUSED, v.card("money")!!.status)
        assertTrue(!fold.setTeamPause("calls", true).card("calls")!!.paused, "Calls can't be paused here")
        fold.syncNow()
        mac.syncNow()
        assertTrue(mac.teamView().card("money")!!.paused)
        now += 15L * 60 * 60_000 // 01:00 the next day
        assertTrue(!mac.teamView().card("money")!!.paused, "over at midnight")
        assertTrue(mac.setTeamPause("obi", true).card("obi")!!.paused)
        assertTrue(!mac.setTeamPause("obi", false).card("obi")!!.paused, "Resume")
    }
}
