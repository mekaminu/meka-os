package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.SpeechCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice completeness, slice 3 (the first words sooner): a spoken question asks for the answer's first piece with the
 * answer, and the device plays it from memory without asking the voice again.
 */
class SpokenFirstPieceFacadeTest {
    private val now = 1_791_476_100_000L // Thu 8 Oct 2026, 16:15 UTC

    private class Server(service: SyncService) : SyncTransport, SpeechApi, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val said = mutableListOf<String>()
        val asked = mutableListOf<String>() // "ask" or "spoken:<voice>"
        var first: Pair<String, String>? = "It's on Friday at 10:00." to "Zmlyc3Q="
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            said += text
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz" + text.length, format = "mp3")
        }
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += "ask"
            return AskReply.Answered(ANSWER, emptyList())
        }
        override suspend fun askSpoken(question: String, context: AskContext, history: List<TalkTurn>, speakVoice: String?): AskReply {
            asked += "spoken:$speakVoice"
            val f = first
            return AskReply.Answered(ANSWER, emptyList(), f?.first, f?.second)
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aSpokenAnswersFirstPieceComesWithItAndPlaysWithoutAskingAgain() = runTest {
        val c = core()
        assertIs<AskOutcome.Answered>(c.talk("tell me something nice", emptyList()))
        assertEquals(listOf("spoken:null"), server.asked) // the default voice
        assertEquals("Zmlyc3Q=", c.speechClip("It's on Friday at 10:00.", first = true))
        assertTrue(server.said.isEmpty()) // never asked the voice for it
        assertEquals("bXAz15", c.speechClip("Bring your kit.", first = false)) // the rest as before
        assertTrue(c.voiceTimingLine()!!.endsWith("· with the answer"))
        // Played once: a second time it's an ordinary cached clip, not counted again.
        c.speechClip("It's on Friday at 10:00.", first = true)
        assertEquals(2, c.voiceTimingLine()!!.split(" · ").size - 1)
    }

    @Test
    fun theChosenVoiceIsAskedForAndTypedOrDeviceVoiceQuestionsDont() = runTest {
        val c = core()
        assertTrue(c.chooseMekaVoice("Brian"))
        c.talk("tell me something nice", emptyList())
        assertEquals("spoken:Brian", server.asked.last())
        // A clip said in Brian is kept for Brian: it plays straight away.
        assertEquals("Zmlyc3Q=", c.speechClip("It's on Friday at 10:00.", first = true))
        // A typed question isn't spoken: no clip asked for.
        c.askMeka("tell me something nice")
        assertEquals("ask", server.asked.last())
        // The device's own voice chosen: nothing of the answer is said on the server.
        assertTrue(c.chooseMekaVoice("device"))
        c.talk("tell me something nice", emptyList())
        assertEquals("ask", server.asked.last())
        assertTrue(server.said.isEmpty())
    }

    @Test
    fun withoutTheClipTheDeviceAsksForTheFirstPieceAsBefore() = runTest {
        server.first = null
        val c = core()
        c.talk("tell me something nice", emptyList())
        assertEquals("bXAz24", c.speechClip("It's on Friday at 10:00.", first = true))
        assertEquals(listOf("It's on Friday at 10:00."), server.said)
        assertNull(c.voiceTimingLine()?.takeIf { it.contains("with the answer") })
    }

    private companion object {
        const val ANSWER = "It's on Friday at 10:00. Bring your kit."
    }
}
