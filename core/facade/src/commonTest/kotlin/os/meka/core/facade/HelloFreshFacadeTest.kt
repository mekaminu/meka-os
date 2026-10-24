package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.AskContext
import os.meka.core.domain.HelloFreshRules
import os.meka.core.domain.HelloOpeners
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.HelloOpenersCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The orb says hello like a person, slice 4: fresh openers Obi writes in the background, through the facade. */
class HelloFreshFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_831_600_000L // Mon 12 Oct 2026, 20:00 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<HelloOpenersCodec.Request>()
        var reply = HelloOpenersCodec.Response(
            "answered",
            (0 until 6).map { HelloOpenersCodec.Opener("evening", "Hey {n}, fresh line ${"abcdef"[it]} for you.", "Anything on your mind?") } +
                HelloOpenersCodec.Opener("evening", "Evening, {n}. How's Sam doing?", "What's up?"),
        )
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun helloOpeners(request: HelloOpenersCodec.Request): HelloOpenersCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(5), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aBatchIsAskedOnceKeptCheckedAndSaid() = runTest(timeout = SLOW_RUNNER) {
        val c = core()
        c.setAboutYou(AboutField.SHOWN_NAME, "Alex")
        assertTrue(c.freshenHelloOpeners())
        assertEquals(listOf(HelloOpenersCodec.Request(HelloFreshRules.PER_PART)), server.sent)
        // Not again the same day, or the same week.
        assertFalse(c.freshenHelloOpeners())
        now += 3 * 24 * 60 * 60_000L
        c.warmHello()
        assertEquals(1, server.sent.size)
        // The hellos through the evenings say them now and then; the line naming someone never.
        val said = mutableListOf<String>()
        val evening = now
        repeat(5) { d ->
            now = evening + d * 24 * 60 * 60_000L
            c.hello() // the day's first: Obi introduces himself
            repeat(8) {
                now += HelloOpeners.AGAIN_WITHIN_MS + 60_000
                said += c.hello().opening
            }
        }
        assertTrue(said.any { "fresh line" in it }, said.toString())
        assertTrue(said.none { "Sam" in it })
        assertTrue(said.filter { "fresh line" in it }.all { "Alex" in it })
    }

    @Test
    fun offlineOrOffKeepsTheHandWrittenOnesAndTriesTomorrow() = runTest(timeout = SLOW_RUNNER) {
        val c = core()
        server.down = true
        assertFalse(c.freshenHelloOpeners())
        assertFalse(c.freshenHelloOpeners())
        assertEquals(1, server.sent.size)
        server.down = false
        now += 21 * 60 * 60_000L
        server.reply = HelloOpenersCodec.Response("over", reason = "used up")
        assertFalse(c.freshenHelloOpeners())
        assertEquals(2, server.sent.size)
        now += 21 * 60 * 60_000L
        server.reply = HelloOpenersCodec.Response("answered", listOf(HelloOpenersCodec.Opener("evening", "Hey {n}, settling in?", "What's up?")))
        assertTrue(c.freshenHelloOpeners())
    }
}
