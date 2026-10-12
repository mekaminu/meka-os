package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.BriefLength
import os.meka.core.domain.BriefScriptRules
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.BriefScriptCodec
import os.meka.core.domain.CivilDate
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.ReadOut
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A morning brief worth listening to, slice 1: the facade fills the story and Listen and Talk read the same script. */
class BriefScriptFacadeTest {
    private var now = 1_791_786_600_000L // Mon 12 Oct 2026, 07:30 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theBriefNamesTheTaskAndHabitAndTalkReadsTheSameScript() = runTest {
        val fold = core()
        fold.addTask("Renew passport")
        fold.addHabit("Gym", 3, HabitTiming.EVENING, 60, null)
        val v = fold.briefView.value
        assertEquals(CivilDate.toEpochDay(2026, 10, 12), v.story.epochDay)
        assertEquals(false, v.story.weekend)
        assertEquals("Renew passport", v.story.focus.single().title)
        val s = BriefScriptRules.script(v)
        assertTrue(s.startsWith("Good morning, Meka. It's Monday 12 October"), s)
        assertTrue("Renew passport, it's up next." in s, s)
        assertTrue(s.endsWith(BriefScriptRules.CLOSE), s)
        assertEquals(s, fold.talkReadOut(ReadOut(ReadOut.Kind.BRIEF)))
    }

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<BriefScriptCodec.Request>()
        var reply: (BriefScriptCodec.Request) -> BriefScriptCodec.Response = { BriefScriptCodec.Response("off") }
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun briefScript(request: BriefScriptCodec.Request): BriefScriptCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply(request)
        }
    }

    @Test
    fun theAiWritesTheBriefFromTheFactsOnceAndAnythingInventedIsDropped() = runTest {
        val server = Server(SyncService(InMemoryServerOpStore()))
        val fold = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        fold.addTask("Renew passport")
        val own = BriefScriptRules.script(fold.briefView.value)
        // AI off: MEKA's own brief.
        assertEquals(own, fold.briefScript())
        assertEquals(1, server.sent.size)
        val facts = server.sent.single().facts
        assertEquals(BriefScriptRules.body(fold.briefView.value), facts)
        assertEquals("2026-10-12", server.sent.single().date)
        assertFalse(server.sent.single().weekend)

        server.reply = {
            BriefScriptCodec.Response("answered", "Good morning, Meka. It's Monday 12 October. " +
                "Lunch with Priya at 1 o'clock. Your one thing to focus on is Renew passport, as it's up next.")
        }
        val s = fold.briefScript()
        assertTrue(s.startsWith("Good morning, Meka. It's Monday 12 October. Your one thing to focus on is Renew passport, as it's up next."), s)
        assertFalse("Priya" in s, s)
        assertTrue(s.endsWith(BriefScriptRules.CLOSE), s)
        // One call per brief: the same facts reuse the answer, and Talk's read-out says the same.
        assertEquals(s, fold.briefScript())
        assertEquals(2, server.sent.size)
        assertEquals(s, fold.talkReadOut(ReadOut(ReadOut.Kind.BRIEF)))

        // New facts (another task) ask again; offline reads MEKA's own.
        fold.addTask("Call the garage")
        server.down = true
        assertEquals(BriefScriptRules.script(fold.briefView.value), fold.briefScript())
        assertEquals(3, server.sent.size)
        assertEquals(BriefScriptRules.script(fold.briefView.value), fold.talkReadOut(ReadOut(ReadOut.Kind.BRIEF)))
    }

    @Test
    fun theLengthIsChosenOnceForBothDevicesAndTheFocusRowsTickTasksOff() = runTest {
        val service = SyncService(InMemoryServerOpStore())
        fun device(id: String) = MekaCore(
            householdId = "hh", deviceId = id, store = InMemoryReplicaStore(),
            transport = os.meka.core.testing.FaultyTransport(service),
            secureRandom = Random(id.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val fold = device("android")
        val mac = device("mac")
        fold.addTask("Renew passport")
        assertEquals(BriefLength.STANDARD, fold.briefView.value.story.length)
        assertEquals("Focus · Renew passport · up next", fold.briefView.value.focusCardLine)

        fold.setBriefLength("short")
        assertEquals(BriefLength.SHORT, fold.briefView.value.story.length)
        assertEquals(listOf(true, false, false), fold.briefView.value.lengths.map { it.chosen })
        fold.syncNow(); mac.syncNow()
        assertEquals(BriefLength.SHORT, mac.briefView.value.story.length)
        mac.setBriefLength("full")
        mac.syncNow(); fold.syncNow()
        assertEquals(BriefLength.FULL, fold.briefView.value.story.length)

        // The focus row ticks the task off, like the brief's Today rows.
        val row = fold.briefView.value.focusLines.single()
        assertEquals("Renew passport", row.title)
        fold.complete(row.taskId!!)
        assertTrue(fold.briefView.value.focusLines.isEmpty())
        assertEquals(null, fold.briefView.value.focusCardLine)
    }
}
