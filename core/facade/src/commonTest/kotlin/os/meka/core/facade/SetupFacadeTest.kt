package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.RequestWatch
import os.meka.core.domain.SetupDevice
import os.meka.core.domain.SetupState
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.HealthCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Setup checklist through the facade (Meka approved 2026-10-09): the server's answers (shared with Health) and this device's facts. */
class SetupFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London

    private inner class Server(service: SyncService) : SyncTransport, AiApi, AccountsApi, HealthApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var healthAsked = 0
        var macs: Int? = 0
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply = AskReply.Answered("", emptyList())
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun householdHealth(): HealthCodec.Response? {
            healthAsked++
            return HealthCodec.Response(HealthCodec.Response.PUSH_ON, calls = true, speech = true, atMs = now, macs = macs)
        }
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = listOf(
            ConnectedAccount("google", "meka@gmail.com", "ok", now - 120_000),
            ConnectedAccount("weather", "home", "ok", now - 600_000),
        )
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = accounts()
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val fold = SetupDevice(
        mac = false, notificationsAllowed = true, batteryExempt = true, notificationAccess = true, callRoleHeld = true,
        people = PeopleLists(family = setOf("Jeanette"), numbers = mapOf("Jeanette" to setOf("+447700900123"))),
        watch = RequestWatch(people = setOf("Jeanette")),
    )

    @Test
    fun theChecklistFollowsTheReplicaAndTodayReusesTheServersAnswers() = runTest {
        val c = core()
        c.syncNow()
        val v = c.refreshSetup(fold)
        assertEquals(1, server.healthAsked)
        assertEquals(SetupState.TODO, v.steps.first { it.key == "voice.picked" }.state)
        assertEquals(SetupState.TODO, v.steps.first { it.key == "mac" }.state)
        assertEquals("Home Biggleswade · work Canary Wharf · change in Calendars", v.steps.first { it.key == "weather" }.line)
        assertEquals(v, c.setupView.value)

        // Picking a voice ticks its step; Today's open within the quarter hour doesn't ask the server again.
        c.chooseMekaVoice("Amy")
        server.macs = 1
        val again = c.refreshSetup(fold, force = false)
        assertEquals(1, server.healthAsked)
        assertEquals("Speaking as Amy", again.steps.first { it.key == "voice.picked" }.line)
        assertEquals(SetupState.TODO, again.steps.first { it.key == "mac" }.state)

        // The Setup page asks afresh: the Mac is now connected.
        val fresh = c.refreshSetup(fold)
        assertEquals(2, server.healthAsked)
        assertEquals(SetupState.DONE, fresh.steps.first { it.key == "mac" }.state)
    }

    @Test
    fun theFoldsScreenSaverStepComesWithItsAnswerAndTheMacsNever() = runTest {
        val c = core()
        c.syncNow()
        assertEquals(null, c.refreshSetup(fold).steps.firstOrNull { it.key == os.meka.core.domain.BedsideDreamRules.SETUP_KEY })
        val off = c.refreshSetup(fold, os.meka.core.domain.SetupScreenSaver(false))
        val step = off.steps.first { it.key == os.meka.core.domain.BedsideDreamRules.SETUP_KEY }
        assertEquals(SetupState.TODO, step.state)
        assertEquals(os.meka.core.domain.SetupFix.SCREEN_SAVER, step.fix)
        assertEquals(off, c.setupView.value)
        val on = c.refreshSetup(fold, os.meka.core.domain.SetupScreenSaver(true), force = false)
        assertEquals(SetupState.DONE, on.steps.first { it.key == os.meka.core.domain.BedsideDreamRules.SETUP_KEY }.state)
        assertEquals(off.done + 1, on.done)
        val mac = c.refreshSetup(SetupDevice(mac = true, notificationsAllowed = true), os.meka.core.domain.SetupScreenSaver(false))
        assertEquals(null, mac.steps.firstOrNull { it.key == os.meka.core.domain.BedsideDreamRules.SETUP_KEY })
    }

    @Test
    fun theScreenSaverIsTheBedsideClockWithNothingToTap() = runTest {
        val c = core()
        c.syncNow()
        val dream = c.bedsideDream(null)
        val bedside = c.bedside(null)
        assertEquals(bedside.time, dream.time)
        assertEquals(bedside.lines, dream.lines)
        assertEquals(bedside.dim, dream.dim)
        assertEquals(os.meka.core.domain.BedsideDreamRules.shift(now), dream.shiftXDp to dream.shiftYDp)
    }

    @Test
    fun theCommuteIsAStepUntilEitherAppSetsItAndSettingItTicksAtOnce() = runTest {
        val c = core()
        c.syncNow()
        val v = c.refreshSetup(fold)
        val step = v.steps.first { it.key == os.meka.core.domain.SetupRules.COMMUTE_KEY }
        assertEquals(SetupState.TODO, step.state)
        assertEquals(os.meka.core.domain.SetupFix.WORK, step.fix)
        // Tapping 1 h 30 under To work in Work mode: the step ticks without asking the server again.
        val asked = server.healthAsked
        c.setCommute(90, 80)
        val ticked = c.setupView.value!!.steps.first { it.key == os.meka.core.domain.SetupRules.COMMUTE_KEY }
        assertEquals(SetupState.DONE, ticked.state)
        assertEquals("1 h 30 there · 1 h 20 home · the wake alarm counts it", ticked.line)
        assertEquals(v.done + 1, c.setupView.value!!.done)
        assertEquals(asked, server.healthAsked)
        // The Mac's Setup has it too.
        val mac = c.refreshSetup(SetupDevice(mac = true, notificationsAllowed = true))
        assertEquals(SetupState.DONE, mac.steps.first { it.key == os.meka.core.domain.SetupRules.COMMUTE_KEY }.state)
    }
}
