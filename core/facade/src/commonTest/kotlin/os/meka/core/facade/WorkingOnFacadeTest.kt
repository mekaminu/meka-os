package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskItemKind
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.StandingErrand
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.WorkingOnRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ada plans with you, part 3 slice 1: Obi is working on, the standing errands over his radar, Gifts and Waiting for,
 * switchable on either app, and the same lines in Ask and the orb's look-up.
 */
class WorkingOnFacadeTest {
    private var now = 1_793_181_600_000L // Wed 28 Oct 2026, 10:00 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Pair<String, AskContext>>()
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += question to context
            return AskReply.Answered("Ok.", emptyList())
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theStandingErrandsSayWhatTheyDidAndWhatTheyNeedAndSwitchOffOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.workingOnView().isEmpty)
        assertEquals(WorkingOnRules.EMPTY_LINE, fold.workingOnView().summary)
        val today = fold.todayEpochDay()
        fold.addRenewal("Home insurance", ObligationKind.INSURANCE, today + 15, RenewalRepeat.YEARLY, null, null)
        fold.addOccasion("Sam's birthday 3 Nov")
        fold.addWaiting("The drill back", "Alex", 0)
        fold.tick()

        val v = fold.workingOnView()
        val all = v.cards.joinToString("\n") { "${it.title} | ${it.did} | ${it.need}" } + "\n" + v.summary
        assertEquals(listOf(StandingErrand.RENEWALS, StandingErrand.BIRTHDAYS, StandingErrand.FOLLOW_UPS), v.cards.map { it.errand }, all)
        assertEquals(3, v.needCount, all)
        assertTrue(v.summary.startsWith("Keeping an eye on 3 things"), all)
        assertTrue(v.cards[0].did.startsWith("Watching 1 renewal · next: Home insurance"), all)
        assertTrue(v.cards[0].need!!.startsWith("Home insurance "), all)
        assertTrue(v.cards[1].did.startsWith("Sam's birthday"), all)
        assertTrue(v.cards[1].need!!.startsWith("Pick a gift · "), all)
        assertTrue(v.cards[2].need!!.startsWith("Time to chase: The drill back"), all)

        // Off on the Fold: gone there and, after a sync, on the Mac too.
        val off = fold.setStandingErrand("birthdays", false)
        assertEquals(listOf(StandingErrand.RENEWALS, StandingErrand.FOLLOW_UPS), off.cards.map { it.errand })
        assertEquals("Turn on", off.switches.single { it.errand == StandingErrand.BIRTHDAYS }.action)
        fold.syncNow(); mac.syncNow()
        mac.tick()
        val onMac = mac.workingOnView()
        assertTrue(onMac.cards.none { it.errand == StandingErrand.BIRTHDAYS })
        assertTrue(!onMac.switches.single { it.errand == StandingErrand.BIRTHDAYS }.on)
        // An unknown code changes nothing.
        assertEquals(onMac, mac.setStandingErrand("boiler", false))
        mac.setStandingErrand("birthdays", true)
        assertTrue(mac.workingOnView().cards.any { it.errand == StandingErrand.BIRTHDAYS })
    }

    @Test
    fun askAndTheOrbSeeTheSameListAndOneLineWhenAskedSomethingElse() = runTest {
        val c = core("android")
        val today = c.todayEpochDay()
        c.addRenewal("MOT", ObligationKind.MOT, today + 40, RenewalRepeat.YEARLY, null, null)
        c.addWaiting("Refund", "Sports Direct", 5)
        c.tick()
        c.askMeka("What are you working on for me?")
        val full = server.asked.last().second.items.filter { it.kind == AskItemKind.WORKING_ON }.map { it.line }
        assertEquals("Obi is working on · 3 standing errands on · nothing needs you", full.first())
        assertTrue(full.any { it.startsWith("Renewals watcher · Watching 1 renewal · next: MOT due ") }, full.joinToString("\n"))
        assertTrue(full.any { it.startsWith("Follow-up chaser · Waiting on 1 thing for you · next chase ") }, full.joinToString("\n"))
        assertEquals(full.joinToString("\n") { "working_on: $it" }, c.liveNavilife("working_on", "what are you working on?"))
        c.askMeka("Why is the sky blue?")
        val brief = server.asked.last().second.items.filter { it.kind == AskItemKind.WORKING_ON }.map { it.line }
        assertEquals(listOf(full.first()), brief)
        // An errand turned off is said as off.
        c.setStandingErrand("follow_ups", false)
        val lines = c.liveNavilife("working_on", "")
        assertTrue("working_on: Follow-up chaser · off" in lines, lines)
        assertNull(lines.lines().firstOrNull { it.contains("Waiting on") })
    }
}
