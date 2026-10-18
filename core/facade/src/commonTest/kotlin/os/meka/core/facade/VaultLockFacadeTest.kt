package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutYouDevice
import os.meka.core.domain.VaultLockRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Vault, slice 4, through the facade: a copy opens only after [MekaCore.unlockVault] (the apps call it after the
 * fingerprint's yes), locks again on [MekaCore.lockVault] and after five minutes, and a locked open reads nothing:
 * not this device's sealed copy, not the server. Names and expiry dates stay readable throughout.
 */
class VaultLockFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    /** A stand-in box: the key's first byte XORed in, with the key's length as a check. */
    private class FakeCrypto : VaultCrypto {
        override fun agreementPublicKey() = "pk-fold"
        override fun agree(publicKey: String) = Base64.encode(ByteArray(32) { 1 })
        override fun agreeOnce(publicKey: String) = VaultAgreement("eph", Base64.encode(ByteArray(32) { 1 }))
        override fun sealBox(key: String, plain: String): String {
            val k = Base64.decode(key); val p = Base64.decode(plain)
            return Base64.encode(byteArrayOf(k.size.toByte()) + ByteArray(p.size) { (p[it].toInt() xor k[0].toInt()).toByte() })
        }
        override fun openBox(key: String, sealed: String): String? {
            val k = Base64.decode(key); val b = Base64.decode(sealed)
            if (b.isEmpty() || b[0].toInt() != k.size) return null
            return Base64.encode(ByteArray(b.size - 1) { (b[it + 1].toInt() xor k[0].toInt()).toByte() })
        }
    }

    private inner class Server(service: SyncService) : SyncTransport, VaultFileApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val copies = mutableMapOf<String, String>()
        var gets = 0
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun vaultFilePut(id: String, sealed: String): Boolean { copies[id] = sealed; return true }
        override suspend fun vaultFileGet(id: String): String? { gets++; return copies[id] }
        override suspend fun vaultFileDelete(id: String): Boolean { copies.remove(id); return true }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private val photo = ByteArray(2_000) { (it * 3).toByte() }

    private suspend fun fold(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    ).also { it.aboutYouDevice = AboutYouDevice.FOLD; it.vaultCrypto = FakeCrypto(); it.syncNow() }

    @Test
    fun aCopyOpensOnlyAfterTheFingerprint() = runTest {
        val fold = fold()
        fold.addDocument("Passport expires 3 Mar 2029")
        val id = fold.documentsView.value.rows.single().id
        // Adding a copy needs no fingerprint: it shows nothing.
        assertFalse(fold.addVaultCopy(id, "image/jpeg", Base64.encode(photo)).refused)
        assertTrue(fold.vaultCopiesLocked())

        val locked = fold.openVaultCopy(id)
        assertTrue(locked.locked)
        assertNull(locked.base64)
        assertEquals(VaultLockRules.LOCKED, locked.problem)
        assertEquals("Passport", locked.title)
        // The row and its name stay readable while the copy is locked.
        assertTrue(fold.documentsView.value.rows.single().hasCopy)
        assertEquals(VaultLockRules.NOTE, VaultLockRules.note(fold.documentsView.value.rows))

        fold.unlockVault()
        assertFalse(fold.vaultCopiesLocked())
        val opened = fold.openVaultCopy(id)
        assertFalse(opened.locked)
        assertTrue(Base64.decode(opened.base64!!).contentEquals(photo))

        // Leaving the Vault locks it again.
        fold.lockVault()
        assertTrue(fold.openVaultCopy(id).locked)
    }

    @Test
    fun anUnlockEndsAfterFiveMinutesAndALockedOpenFetchesNothing() = runTest {
        val fold = fold()
        fold.addDocument("Driving licence 12 Aug 2031")
        val id = fold.documentsView.value.rows.single().id
        fold.addVaultCopy(id, "image/png", Base64.encode(photo))
        fold.unlockVault()
        now += VaultLockRules.WINDOW_MS - 1
        assertFalse(fold.openVaultCopy(id).locked)
        now += 1
        assertTrue(fold.vaultCopiesLocked())
        val before = server.gets
        assertTrue(fold.openVaultCopy(id).locked)
        assertEquals(before, server.gets)
    }
}
