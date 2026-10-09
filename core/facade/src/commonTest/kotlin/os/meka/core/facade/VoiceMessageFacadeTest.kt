package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Callers' recordings through the facade (call assistant polish 8c): only a message in the summary with one kept. */
class VoiceMessageFacadeTest {
    private var now = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private val ops = InMemoryServerOpStore()
    private var seq = 0

    private inner class Server(service: SyncService) : SyncTransport, VoiceMessageApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<String>()
        var down = false
        val audio = mutableMapOf<String, ByteArray>()
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun voiceMessageAudio(id: String): ByteArray? {
            asked += id
            if (down) throw TransportException("offline")
            return audio[id]
        }
    }

    private val server = Server(SyncService(ops))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    /** MEKA's server took a message on [call] (and kept its recording when [audio]). */
    private fun serverWrites(call: String, audio: Boolean): String {
        val id = CallAssistantRules.heldId("twilio", call)
        val fields = CallAssistantRules.messageFields("+447700900123", now) + (if (audio) mapOf(HeldMessageFields.AUDIO to true.fv()) else emptyMap())
        for ((f, v) in fields) ops.append(Op("s${seq++}", "hh", EntityTypes.HELD_MESSAGE, id, f, v, Hlc(now, seq, "server"), emptyList(), "server"))
        return id
    }

    @Test
    fun aKeptRecordingIsFetchedAndNothingIsAskedForOtherwise() = runTest {
        val kept = serverWrites("CA1", audio = true)
        val notKept = serverWrites("CA2", audio = false)
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0)
        server.audio[kept] = mp3
        val c = core()
        c.syncNow()
        assertTrue(c.afterWork.value.people.flatMap { it.items }.single { it.id == kept }.hasAudio)

        assertContentEquals(mp3, c.voiceMessageAudio(kept))
        assertEquals(kotlin.io.encoding.Base64.encode(mp3), c.voiceMessageAudioBase64(kept))
        // No recording kept, an id not in the summary, or not an id at all: the server isn't asked.
        assertNull(c.voiceMessageAudio(notKept))
        assertNull(c.voiceMessageAudio("h0000000000000000"))
        assertNull(c.voiceMessageAudio("../x"))
        assertEquals(listOf(kept, kept), server.asked)

        // Offline: nothing to play, no error.
        server.down = true
        assertNull(c.voiceMessageAudio(kept))
        server.down = false

        // Done: gone from the summary, so it is never fetched again.
        c.clearAfterWork()
        c.syncNow()
        val before = server.asked.size
        assertNull(c.voiceMessageAudio(kept))
        assertEquals(before, server.asked.size)
    }
}
