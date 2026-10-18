package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutYouDevice
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.domain.VaultKeyRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.RecoveryCodec
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Vault key through the facade (Vault slice 2a): made on the first device to sync, sealed for the Mac only on the
 * Fold's Allow, Not this one / Ask again, and opened by a recovered device from the recovery words. The crypto is a
 * stand-in with the same shape (a commutative "agreement", a keyed box that refuses a wrong key); the real ones are
 * tested on each platform.
 */
class VaultKeyFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    /** Stand-in crypto: agreement = SHA-256 of the two public keys in order; the box XORs with SHA-256(key) and tags. */
    private class FakeCrypto(private val pub: String?) : VaultCrypto {
        private var n = 0
        private fun shared(a: String, b: String) = Sha256.digest(listOf(a, b).sorted().joinToString("|").encodeToByteArray())
        override fun agreementPublicKey() = pub
        override fun agree(publicKey: String) = pub?.let { Base64.encode(shared(it, publicKey)) }
        override fun agreeOnce(publicKey: String): VaultAgreement {
            val eph = "eph-${pub}-${n++}"
            return VaultAgreement(eph, Base64.encode(shared(eph, publicKey)))
        }
        private fun stream(key: ByteArray) = Sha256.digest(key)
        override fun sealBox(key: String, plain: String): String {
            val k = Base64.decode(key); val p = Base64.decode(plain)
            val s = stream(k)
            val body = ByteArray(p.size) { (p[it].toInt() xor s[it % 32].toInt()).toByte() }
            return Base64.encode(Sha256.digest(k + p).copyOf(4) + body)
        }
        override fun openBox(key: String, sealed: String): String? {
            val k = Base64.decode(key); val b = Base64.decode(sealed)
            val s = stream(k)
            val p = ByteArray(b.size - 4) { (b[it + 4].toInt() xor s[it % 32].toInt()).toByte() }
            return if (Sha256.digest(k + p).copyOf(4).contentEquals(b.copyOf(4))) Base64.encode(p) else null
        }
    }

    private inner class Server(service: SyncService) : SyncTransport, RecoveryApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun recoveryStatus() = RecoveryCodec.Status(RecoveryCodec.NONE)
        override suspend fun setRecoveryKey(verifier: String) = RecoveryCodec.Status(RecoveryCodec.READY, createdAtMs = now, createdBy = "Fold")
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(device: String, kind: AboutYouDevice?, crypto: VaultCrypto?) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    ).also { it.aboutYouDevice = kind; it.vaultCrypto = crypto }

    @Test
    fun theFoldMakesTheKeyAndTheMacGetsItOnlyOnAllow() = runTest {
        val fold = core("android", AboutYouDevice.FOLD, FakeCrypto("pk-fold"))
        val mac = core("mac", AboutYouDevice.MAC, FakeCrypto("pk-mac"))
        fold.syncNow()
        val made = fold.vaultKeyView.value
        assertTrue(made.line.startsWith("Vault key ") && made.line.endsWith(" · on this Fold"), made.line)
        assertFalse(made.lit)

        mac.syncNow()
        val waiting = mac.vaultKeyView.value
        assertEquals("Waiting for your Fold to let this Mac open the Vault", waiting.line)
        assertTrue(waiting.lit)
        val code = VaultKeyRules.code("pk-mac")
        assertTrue(waiting.sub!!.endsWith("this Mac shows $code"))

        fold.syncNow()
        val ask = fold.vaultKeyView.value.requests.single()
        assertEquals("mac", ask.deviceId)
        assertEquals("Allow only if the Mac shows $code", ask.line)
        // Nothing is sealed for the Mac before Allow.
        mac.syncNow()
        assertTrue(mac.vaultKeyView.value.lit)

        assertTrue(fold.allowVaultDevice("mac"))
        assertTrue(fold.vaultKeyView.value.requests.isEmpty())
        fold.syncNow(); mac.syncNow()
        val opened = mac.vaultKeyView.value
        assertTrue(opened.line.endsWith(" · on this Mac and the Fold"), opened.line)
        assertEquals(made.line.substringBefore(" · "), opened.line.substringBefore(" · "))
        fold.syncNow()
        assertTrue(fold.vaultKeyView.value.line.endsWith(" · on this Fold and the Mac"))
        // A watch (no name, no crypto) never asks and shows nothing.
        val watch = core("watch01", null, null)
        watch.syncNow()
        assertFalse(watch.vaultKeyView.value.shown)
        fold.syncNow()
        assertTrue(fold.vaultKeyView.value.requests.isEmpty())
    }

    @Test
    fun notThisOneThenAskAgain() = runTest {
        val fold = core("android", AboutYouDevice.FOLD, FakeCrypto("pk-fold"))
        val mac = core("mac", AboutYouDevice.MAC, FakeCrypto("pk-mac"))
        fold.syncNow(); mac.syncNow(); fold.syncNow()
        now += 1_000
        assertTrue(fold.refuseVaultDevice("mac"))
        assertTrue(fold.vaultKeyView.value.requests.isEmpty())
        fold.syncNow(); mac.syncNow()
        val no = mac.vaultKeyView.value
        assertEquals("Your Fold said no to this Mac", no.line)
        assertTrue(no.canAskAgain)
        now += 1_000
        assertTrue(mac.askVaultAgain())
        mac.syncNow(); fold.syncNow()
        assertEquals("mac", fold.vaultKeyView.value.requests.single().deviceId)
    }

    @Test
    fun aMacThatCantHoldAKeySaysSoAndNeverAsks() = runTest {
        val fold = core("android", AboutYouDevice.FOLD, FakeCrypto("pk-fold"))
        val mac = core("mac", AboutYouDevice.MAC, FakeCrypto(null))
        fold.syncNow(); mac.syncNow(); fold.syncNow()
        assertEquals("This Mac can't hold the Vault key", mac.vaultKeyView.value.line)
        assertTrue(fold.vaultKeyView.value.requests.isEmpty())
    }

    @Test
    fun aNewRecoveryKeyOpensTheVaultOnARecoveredPhone() = runTest {
        val fold = core("android", AboutYouDevice.FOLD, FakeCrypto("pk-fold"))
        fold.syncNow()
        val keyLine = fold.vaultKeyView.value.line.substringBefore(" · ")
        assertEquals("Make a new recovery key (Settings → Recovery key) so it opens the Vault too", fold.vaultKeyView.value.sub)
        val draft = fold.makeRecoveryKey()
        val answers = draft.confirmPositions.map { draft.words[it] }
        assertTrue(fold.saveRecoveryKey(draft, answers))
        assertEquals("Your recovery key opens it too", fold.vaultKeyView.value.sub)
        fold.syncNow()

        // Both devices lost: a new phone recovers with the words and is handed the words' Vault key by the enrolment.
        val entropy = (RecoveryKeyRules.read(draft.words.joinToString(" ")) as RecoveryKeyRules.Reading.Ok).entropy
        val again = core("android2", AboutYouDevice.FOLD, FakeCrypto("pk-new"))
        again.rememberVaultRecovery(Base64.encode(VaultKeyRules.recoveryKey(entropy)))
        again.syncNow()
        val v = again.vaultKeyView.value
        assertEquals(keyLine, v.line.substringBefore(" · "))
        assertTrue(v.line.endsWith(" · on this Fold and your other Fold"), v.line)
        assertFalse(v.lit)

        // Wrong words open nothing.
        val wrong = core("android3", AboutYouDevice.FOLD, FakeCrypto("pk-other"))
        wrong.rememberVaultRecovery(Base64.encode(VaultKeyRules.recoveryKey(ByteArray(32))))
        wrong.syncNow()
        assertNotNull(wrong.vaultKeyView.value.line)
        assertTrue(wrong.vaultKeyView.value.lit)
    }
}
