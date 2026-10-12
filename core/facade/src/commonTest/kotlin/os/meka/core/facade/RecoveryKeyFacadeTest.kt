package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.domain.SetupDevice
import os.meka.core.domain.SetupRules
import os.meka.core.domain.SetupState
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.RecoveryCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Recovery key through the facade (Recovery key, slice 2): make, confirm 3 words, save the verifier only. */
class RecoveryKeyFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private inner class Server(service: SyncService) : SyncTransport, RecoveryApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var status = RecoveryCodec.Status(RecoveryCodec.NONE)
        val verifiers = mutableListOf<String>()
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun recoveryStatus(): RecoveryCodec.Status {
            if (down) throw TransportException("offline")
            return status
        }
        override suspend fun setRecoveryKey(verifier: String): RecoveryCodec.Status {
            if (down) throw TransportException("offline")
            verifiers += verifier
            status = RecoveryCodec.Status(RecoveryCodec.READY, createdAtMs = now, createdBy = "Fold")
            return status
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun makeConfirmAndSaveSendsOnlyTheVerifier() = runTest {
        val c = core()
        assertNull(c.recoveryKeyView.value)
        val first = c.refreshRecoveryKey()
        assertTrue(first.lit)
        assertEquals("Make a recovery key", first.action)
        assertNull(first.warning)

        val draft = c.newRecoveryKey()
        assertEquals(24, draft.words.size)
        assertEquals(6, draft.rows.size)
        assertEquals(3, draft.confirmPositions.size)
        // The words read back as the same key, and the verifier is the one RecoveryKeyRules makes from them.
        val read = RecoveryKeyRules.read(draft.words.joinToString(" ")) as RecoveryKeyRules.Reading.Ok
        assertEquals(RecoveryKeyRules.verifier(read.entropy), draft.verifier)

        // Wrong words: nothing is sent.
        assertFalse(c.saveRecoveryKey(draft, listOf("zoo", "zoo", "zoo")))
        assertEquals(RecoveryKeyRules.NOT_MATCHING, c.recoveryKeyView.value!!.problem)
        assertTrue(server.verifiers.isEmpty())

        // Right words (any case, first four letters will do).
        val answers = draft.confirmPositions.map { draft.words[it].take(4).uppercase() }
        assertTrue(c.saveRecoveryKey(draft, answers))
        assertEquals(listOf(draft.verifier), server.verifiers)
        // Only a 64-hex verifier reaches the server, never the words.
        assertTrue(RecoveryCodec.isHash(server.verifiers.single()))
        val saved = c.recoveryKeyView.value!!
        assertNull(saved.problem)
        assertFalse(saved.lit)
        assertEquals("Made today on the Fold · gets you back in if you lose both devices", saved.line)
        assertEquals("The old key stops working once the new one is saved", saved.warning)
        val activity = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }.map { it.summary }
        assertEquals(listOf(RecoveryKeyRules.MADE_SUMMARY), activity)

        // A second key is a different set of words.
        assertNotEquals(draft.words, c.newRecoveryKey().words)
    }

    @Test
    fun offlineAndUnconnectedSayWhy() = runTest {
        server.down = true
        val c = core()
        val v = c.refreshRecoveryKey()
        assertEquals(RecoveryKeyRules.OFFLINE, v.problem)
        assertEquals("Can't check right now · MEKA's server didn't answer", v.line)
        val draft = c.newRecoveryKey()
        assertFalse(c.saveRecoveryKey(draft, draft.confirmPositions.map { draft.words[it] }))
        assertEquals(RecoveryKeyRules.OFFLINE, c.recoveryKeyView.value!!.problem)

        val alone = core(transport = null)
        assertEquals(RecoveryKeyRules.NOT_CONNECTED, alone.refreshRecoveryKey().problem)
    }

    @Test
    fun setupListsTheKeyAndTicksItOnceSaved() = runTest {
        val c = core()
        val device = SetupDevice(mac = false, notificationsAllowed = true)
        val before = c.refreshSetup(device).steps.first { it.key == SetupRules.RECOVERY_KEY }
        assertEquals(SetupState.TODO, before.state)
        assertEquals("Make one", before.fixLabel)
        c.refreshRecoveryKey()
        val draft = c.newRecoveryKey()
        assertTrue(c.saveRecoveryKey(draft, draft.confirmPositions.map { draft.words[it] }))
        assertEquals(SetupState.DONE, c.setupView.value!!.steps.first { it.key == SetupRules.RECOVERY_KEY }.state)

        // Not connected: it comes later.
        val alone = core(transport = null)
        assertEquals(SetupState.LATER, alone.refreshSetup(device).steps.first { it.key == SetupRules.RECOVERY_KEY }.state)
    }
}
