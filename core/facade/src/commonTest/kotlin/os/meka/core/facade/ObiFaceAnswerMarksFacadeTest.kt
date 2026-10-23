package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.LipSync
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
import kotlin.test.assertTrue

/**
 * Obi's face, slice 3c: while the face is drawn, a spoken answer's own pieces (the first one with the answer, the ones
 * said ahead while the model writes) come with their mouth sounds, so his lips follow his very first words too.
 */
class ObiFaceAnswerMarksFacadeTest {
    private class Server(service: SyncService) : SyncTransport, SpeechApi, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<String>()
        var firstMarks: String? = "0:sil 40:p 120:a 400:sil"
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?) =
            SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz", format = "mp3")
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += "ask"
            return AskReply.Answered(ANSWER, emptyList())
        }
        override suspend fun askSpoken(question: String, context: AskContext, history: List<TalkTurn>, speakVoice: String?): AskReply {
            asked += "spoken"
            return AskReply.Answered(ANSWER, emptyList(), FIRST, "Zmlyc3Q=")
        }
        override suspend fun askSpokenMarked(question: String, context: AskContext, history: List<TalkTurn>, speakVoice: String?): AskReply {
            asked += "spokenMarked"
            return AskReply.Answered(ANSWER, emptyList(), FIRST, "Zmlyc3Q=", firstVisemes = firstMarks)
        }
        override suspend fun askStreamed(
            question: String, context: AskContext, history: List<TalkTurn>, speakVoice: String?,
            onFirst: suspend (text: String, audio: String, voice: String?) -> Unit,
            onNext: (suspend (text: String, audio: String, voice: String?) -> Unit)?,
        ): AskReply {
            asked += "streamed"
            onFirst(FIRST, "Zmlyc3Q=", speakVoice)
            onNext?.invoke(NEXT, "bmV4dA==", speakVoice)
            return AskReply.Answered(ANSWER, emptyList())
        }
        override suspend fun askStreamedMarked(
            question: String, context: AskContext, history: List<TalkTurn>, speakVoice: String?,
            onFirst: suspend (text: String, audio: String, voice: String?, visemes: String?) -> Unit,
            onNext: (suspend (text: String, audio: String, voice: String?, visemes: String?) -> Unit)?,
        ): AskReply {
            asked += "streamedMarked"
            onFirst(FIRST, "Zmlyc3Q=", speakVoice, firstMarks)
            onNext?.invoke(NEXT, "bmV4dA==", speakVoice, "0:sil 30:o 200:sil")
            return AskReply.Answered(ANSWER, emptyList())
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 1_791_476_100_000L },
    )

    @Test
    fun withoutTheFaceTheAnswerIsAskedForPlainAndItsPiecesHaveNoMarks() = runTest {
        val c = core()
        assertIs<AskOutcome.Answered>(c.talkStreamed("why is the sky blue", emptyList()) {})
        assertEquals(listOf("streamed"), server.asked)
        assertTrue(c.speechMarks(FIRST).isEmpty())
        c.talk("and sunsets", emptyList())
        assertTrue(c.speechMarks(FIRST).isEmpty())
    }

    @Test
    fun withTheFaceTheStreamedPiecesBringTheirMouthSounds() = runTest {
        val c = core()
        c.speechFaceMarks(true)
        assertIs<AskOutcome.Answered>(c.talkStreamed("why is the sky blue", emptyList()) {})
        assertEquals(listOf("streamedMarked"), server.asked)
        assertEquals(
            listOf(LipSync.Mark(0, "sil"), LipSync.Mark(40, "p"), LipSync.Mark(120, "a"), LipSync.Mark(400, "sil")),
            c.speechMarks(FIRST),
        )
        assertEquals(3, c.speechMarks(NEXT).size)
        // The clip itself still plays from memory.
        assertEquals("Zmlyc3Q=", c.speechClip(FIRST, first = true))
    }

    @Test
    fun unreadableMarksAreNoneAndTheAnswersClipStillPlays() = runTest {
        val c = core()
        c.speechFaceMarks(true)
        server.firstMarks = "0:sil 90:zz"
        c.talk("tell me something nice", emptyList()) // the Fold and the Mac's talk both stream
        assertEquals(listOf("streamedMarked"), server.asked)
        assertTrue(c.speechMarks(FIRST).isEmpty()) // the loudness mouth instead
        assertEquals("Zmlyc3Q=", c.speechClip(FIRST, first = true))
    }

    @Test
    fun turningTheFaceOffForgetsAnAnswersMarks() = runTest {
        val c = core()
        c.speechFaceMarks(true)
        c.talkStreamed("why is the sky blue", emptyList()) {}
        c.speechFaceMarks(false)
        assertTrue(c.speechMarks(FIRST).isEmpty())
    }

    private companion object {
        const val ANSWER = "Light scatters. Blue scatters most."
        const val FIRST = "Light scatters."
        const val NEXT = "Blue scatters most."
    }
}
