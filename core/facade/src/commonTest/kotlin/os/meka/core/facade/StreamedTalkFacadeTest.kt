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
 * The companion, slice 9 (speech that starts within about a second): a spoken question's first sentence comes ahead
 * of the rest of the answer, already said, and plays from memory while the answer is still on its way.
 */
class StreamedTalkFacadeTest {
    private val now = 1_791_476_100_000L // Thu 8 Oct 2026, 16:15 UTC

    private class Server(service: SyncService) : SyncTransport, SpeechApi, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val said = mutableListOf<String>()
        val asked = mutableListOf<String>()
        /** What the core had heard of the answer when the server was still writing it. */
        var heardBeforeEnd: String? = null
        var core: MekaCore? = null
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
        override suspend fun askStreamed(
            question: String, context: AskContext, history: List<TalkTurn>, speakVoice: String?,
            onFirst: suspend (text: String, audio: String, voice: String?) -> Unit,
        ): AskReply {
            asked += "streamed:$speakVoice"
            onFirst(FIRST, "Zmlyc3Q=", speakVoice)
            onFirst("Again.", "QWdhaW4=", speakVoice) // a second one is never taken
            heardBeforeEnd = core?.talkEarlyPiece()
            return AskReply.Answered(ANSWER, emptyList())
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    ).also { server.core = it }

    @Test
    fun theFirstSentenceComesAheadOnceAndPlaysFromMemory() = runTest {
        val c = core()
        val early = mutableListOf<String>()
        val out = assertIs<AskOutcome.Answered>(c.talkStreamed("why is the sky blue", emptyList()) { early += it })
        assertEquals(listOf(FIRST), early)
        assertEquals(FIRST, server.heardBeforeEnd) // known while the answer was still coming
        assertEquals(listOf("streamed:null"), server.asked)
        assertEquals(ANSWER, out.answer.text)
        // Its clip is ready: saying it asks the voice for nothing.
        assertEquals("Zmlyc3Q=", c.speechClip(FIRST, first = true))
        assertTrue(server.said.isEmpty())
        // The next spoken question starts with nothing ahead.
        c.talk("and why are sunsets red", emptyList())
        assertEquals("streamed:null", server.asked.last())
        assertEquals(FIRST, c.talkEarlyPiece()) // the Mac's talk streams too
    }

    @Test
    fun typedOrDeviceVoiceQuestionsDontStream() = runTest {
        val c = core()
        c.askMeka("why is the sky blue")
        assertEquals("ask", server.asked.last())
        assertNull(c.talkEarlyPiece())
        assertTrue(c.chooseMekaVoice("device"))
        val early = mutableListOf<String>()
        c.talkStreamed("why is the sky blue", emptyList()) { early += it }
        assertEquals("ask", server.asked.last())
        assertTrue(early.isEmpty())
        assertNull(c.talkEarlyPiece())
    }

    private companion object {
        const val FIRST = "The sky looks blue because of Rayleigh scattering."
        const val ANSWER = "The sky looks blue because of Rayleigh scattering. Blue light bounces about more."
    }
}
