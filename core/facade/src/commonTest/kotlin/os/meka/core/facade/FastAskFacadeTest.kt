package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.FastAskRules
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AI layer completeness: "end my fast", "resume my fast", "how long left?" through the facade, never the AI. */
class FastAskFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London

    private class Server(service: SyncService) : os.meka.core.sync.SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var asked = 0
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked++
            return AskReply.Answered("From the AI.", emptyList())
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(device: String = "android") = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun MekaCore.say(q: String): String {
        val a = assertIs<AskOutcome.Answered>(askMeka(q)).answer
        assertEquals(FastAskRules.link, a.help, q)
        assertTrue(a.cards.isEmpty(), q)
        return a.text
    }

    @Test
    fun endsAnswersAndResumesTheFastOnBothDevicesWithoutTheAi() = runTest {
        val fold = core()
        val mac = core("mac")
        // 17:15 is inside the default 12:00–20:00 window.
        assertEquals("You're not fasting · eating window open until 20:00 · nothing to end.", fold.say("end my fast"))

        fold.startFast(0)
        now += 14 * 3_600_000L + 30 * 60_000L // 07:45 on Friday
        assertEquals("Yes · 14 h 30 m in · 1 h 30 m to go to your 16 h goal, at 09:15.", fold.say("How long left?"))
        assertEquals(fold.say("how long left on my fast?"), fold.say("am I fasting?"))

        assertTrue(fold.say("I've broken my fast").startsWith("Ended your fast · 14 h 30 m · goal 16 h. Ended by mistake?"))
        assertFalse(fold.fastingView.value.isFasting)
        fold.syncNow(); mac.syncNow()
        assertFalse(mac.fastingView.value.isFasting)

        now += 2 * 60_000L
        assertEquals("Your fast is running again · 14 h 32 m in, from 17:15 yesterday.", fold.say("resume my fast"))
        assertTrue(fold.fastingView.value.isFasting)
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.fastingView.value.isFasting)

        // Too late to take back once ten minutes have passed.
        fold.say("end my fast")
        now += 11 * 60_000L
        assertTrue(fold.say("resume my fast").startsWith("Your last fast ended more than 10 minutes ago"))
        assertFalse(fold.fastingView.value.isFasting)
        assertEquals(0, server.asked)
    }
}
