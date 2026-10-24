package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.LipSync
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
import kotlin.test.assertTrue

/** Obi's face, slice 3b: while the face is drawn, clips come with their mouth sounds and the speaker can have them. */
class ObiFaceMarksFacadeTest {
    private class Server(service: SyncService) : SyncTransport, SpeechApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val plain = mutableListOf<String>()
        val marked = mutableListOf<String>()
        var marks: String? = "0:sil 62:p 125:a 300:sil"
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            plain += text
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz", format = "mp3")
        }
        override suspend fun speakMarked(text: String, voice: String?): SpeechCodec.Response {
            marked += text
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz", format = "mp3", visemes = marks)
        }
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON)
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 1_791_476_100_000L },
    )

    @Test
    fun withoutTheFaceClipsAreAskedForPlainAndHaveNoMarks() = runTest {
        val c = core()
        assertEquals("bXAz", c.speechClip("Right, done.", first = true))
        assertEquals(listOf("Right, done."), server.plain)
        assertTrue(server.marked.isEmpty())
        assertTrue(c.speechMarks("Right, done.").isEmpty())
    }

    @Test
    fun withTheFaceEachClipBringsItsMouthSounds() = runTest {
        val c = core()
        c.speechFaceMarks(true)
        assertEquals("bXAz", c.speechClip("Right, done.", first = true))
        assertEquals(listOf("Right, done."), server.marked)
        assertEquals(
            listOf(LipSync.Mark(0, "sil"), LipSync.Mark(62, "p"), LipSync.Mark(125, "a"), LipSync.Mark(300, "sil")),
            c.speechMarks(" Right, done. "),
        )
        assertTrue(c.speechMarks("Something else.").isEmpty())
        // Slice 4, the Mac: the same marks as a track asked at the player's position; none for other words.
        val track = c.speechMouth("Right, done.", 400)
        assertEquals(os.meka.core.domain.MouthShape.M, track?.at(200)?.over)
        assertEquals(null, c.speechMouth("Something else.", 400))
        // The track's patch names are the wire's allow-listed ones.
        assertEquals(
            os.meka.core.wire.FaceCodec.MOUTHS.toSet(),
            os.meka.core.domain.MouthShape.entries.map { os.meka.core.domain.MouthMix.file(it) }.toSet(),
        )
        assertEquals(os.meka.core.wire.FaceCodec.FILES, os.meka.core.domain.FaceFileRules.FILES.toSet())
        assertEquals(os.meka.core.wire.FaceCodec.LOOP, os.meka.core.domain.FaceFileRules.LOOP)
    }

    @Test
    fun badMarksAreNoneAndTheClipStillPlays() = runTest {
        val c = core()
        c.speechFaceMarks(true)
        server.marks = "0:sil 50:zz"
        assertEquals("bXAz", c.speechClip("Right, done.", first = true))
        assertTrue(c.speechMarks("Right, done.").isEmpty())
    }

    @Test
    fun turningTheFaceOnAsksAgainForClipsMadeWithoutMarks() = runTest {
        val c = core()
        c.speechClip("Good evening.", first = true)
        c.speechFaceMarks(true)
        c.speechClip("Good evening.", first = true)
        assertEquals(listOf("Good evening."), server.marked)
        assertEquals(4, c.speechMarks("Good evening.").size)
        // Kept now: a second line with the same words asks nothing more.
        c.speechClip("Good evening.", first = true)
        assertEquals(1, server.marked.size)
    }

    @Test
    fun turningTheFaceOffForgetsTheMarks() = runTest {
        val c = core()
        c.speechFaceMarks(true)
        c.speechClip("Right, done.", first = true)
        c.speechFaceMarks(false)
        assertTrue(c.speechMarks("Right, done.").isEmpty())
    }
}
