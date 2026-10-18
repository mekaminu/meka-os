package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutYouDevice
import os.meka.core.domain.VaultFileRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Vault's encrypted copies through the facade (Vault slice 2b): sealed on the Fold with the household's key, only
 * sealed bytes on the server, opened on the Mac once it holds the key (and kept for offline), added offline and sent
 * after the next sync, replaced and removed on every device and the server. The crypto is a stand-in with the same
 * shape as [VaultKeyFacadeTest]'s; the real ones are tested on each platform.
 */
class VaultFileFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private class FakeCrypto(private val pub: String?) : VaultCrypto {
        private var n = 0
        private fun shared(a: String, b: String) = Sha256.digest(listOf(a, b).sorted().joinToString("|").encodeToByteArray())
        override fun agreementPublicKey() = pub
        override fun agree(publicKey: String) = pub?.let { Base64.encode(shared(it, publicKey)) }
        override fun agreeOnce(publicKey: String): VaultAgreement {
            val eph = "eph-$pub-${n++}"
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

    /** The sync service and an in-memory copy store; [offline] makes every copy call fail like a lost signal. */
    private inner class Server(service: SyncService) : SyncTransport, VaultFileApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val copies = mutableMapOf<String, String>()
        var offline = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        private fun reach() { if (offline) throw TransportException("network error: offline") }
        override suspend fun vaultFilePut(id: String, sealed: String): Boolean { reach(); copies[id] = sealed; return true }
        override suspend fun vaultFileGet(id: String): String? { reach(); return copies[id] }
        override suspend fun vaultFileDelete(id: String): Boolean { reach(); copies.remove(id); return true }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(device: String, kind: AboutYouDevice?, crypto: VaultCrypto?) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    ).also { it.aboutYouDevice = kind; it.vaultCrypto = crypto }

    private val photo = ByteArray(3_000) { (it * 7).toByte() }
    private val photo64 = Base64.encode(photo)

    /** The Fold makes the key, the Mac asks, the Fold allows: both hold it. */
    private suspend fun pair(): Pair<MekaCore, MekaCore> {
        val fold = core("android", AboutYouDevice.FOLD, FakeCrypto("pk-fold"))
        val mac = core("mac", AboutYouDevice.MAC, FakeCrypto("pk-mac"))
        fold.syncNow(); mac.syncNow(); fold.syncNow()
        assertTrue(fold.allowVaultDevice("mac"))
        fold.syncNow(); mac.syncNow(); fold.syncNow()
        // Slice 4: copies open after the fingerprint (VaultLockFacadeTest covers the lock itself).
        fold.unlockVault(); mac.unlockVault()
        return fold to mac
    }

    @Test
    fun aCopyIsSealedOnTheFoldAndOpensOnTheMac() = runTest {
        val (fold, mac) = pair()
        assertEquals(VaultFileRules.GONE, fold.addVaultCopy("nope", "image/jpeg", photo64).line)
        fold.addDocument("Passport expires 3 Mar 2029")
        val id = fold.documentsView.value.rows.single().id
        // Not a photo or PDF, nothing kept.
        val heic = fold.addVaultCopy(id, "image/heic", photo64, "IMG.heic")
        assertTrue(heic.refused); assertEquals(VaultFileRules.NOT_TYPE, heic.line)
        val said = fold.addVaultCopy(id, "image/jpeg", photo64)
        assertFalse(said.refused, said.line)
        assertEquals("Copy added · photo, 3 KB, locked with your Vault key · only your devices can open it", said.line)
        // The server holds one sealed box, never the photo.
        val box = server.copies.values.single()
        assertFalse(Base64.decode(box).toList().windowed(photo.size).any { it == photo.toList() })
        assertTrue(fold.documentsView.value.rows.single().copyLine!!.startsWith("Photo · 3 KB · encrypted · added on this Fold"))

        fold.syncNow(); mac.syncNow()
        val row = mac.documentsView.value.rows.single()
        assertTrue(row.copyLine!!.contains("added on the Fold"), row.copyLine)
        val opened = mac.openVaultCopy(id)
        assertNull(opened.problem)
        assertEquals(VaultFileRules.JPEG, opened.type)
        assertEquals("Passport", opened.title)
        assertTrue(Base64.decode(opened.base64!!).contentEquals(photo))
        // Kept sealed on the Mac: it opens with no signal.
        server.offline = true
        assertTrue(Base64.decode(mac.openVaultCopy(id).base64!!).contentEquals(photo))
    }

    @Test
    fun withoutTheKeyNothingIsKeptOrOpened() = runTest {
        val fold = core("android", AboutYouDevice.FOLD, FakeCrypto("pk-fold"))
        val mac = core("mac", AboutYouDevice.MAC, FakeCrypto("pk-mac"))
        fold.syncNow(); mac.syncNow()
        fold.unlockVault(); mac.unlockVault()
        mac.addDocument("Home insurance 4 Jan 2027")
        val id = mac.documentsView.value.rows.single().id
        val said = mac.addVaultCopy(id, "application/pdf", photo64, "policy.pdf")
        assertTrue(said.refused)
        assertEquals(VaultFileRules.NO_KEY, said.line)
        assertTrue(server.copies.isEmpty())
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.addVaultCopy(id, "application/pdf", photo64, "policy.pdf").line.startsWith("Copy added · pdf, "))
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.documentsView.value.rows.single().copyIsPdf)
        assertEquals(VaultFileRules.NO_KEY, mac.openVaultCopy(id).problem)
        // Too big: refused before anything is sealed.
        val big = Base64.encode(ByteArray((VaultFileRules.MAX_FILE_BYTES + 1).toInt()))
        assertEquals(VaultFileRules.TOO_BIG, fold.addVaultCopy(id, "application/pdf", big).line)
    }

    @Test
    fun offlineItGoesUpAfterTheNextSyncAndTheMacWaitsForIt() = runTest {
        val (fold, mac) = pair()
        fold.addDocument("Railcard 05/2027")
        val id = fold.documentsView.value.rows.single().id
        server.offline = true
        val said = fold.addVaultCopy(id, "image/png", photo64)
        assertFalse(said.refused)
        assertTrue(said.line.endsWith("it goes up when this Fold is next online"), said.line)
        assertTrue(server.copies.isEmpty())
        // The Fold opens its own copy offline.
        assertTrue(Base64.decode(fold.openVaultCopy(id).base64!!).contentEquals(photo))
        // The document syncs first (sync is up, the copy store is down): the Mac says where the copy is.
        fold.syncNow()
        server.offline = false
        mac.syncNow()
        assertEquals(VaultFileRules.notUploaded("Fold"), mac.openVaultCopy(id).problem)
        fold.syncNow()
        assertEquals(1, server.copies.size)
        // The Mac's next round fetches it ahead, sealed, so it opens offline.
        mac.syncNow()
        server.offline = true
        assertTrue(Base64.decode(mac.openVaultCopy(id).base64!!).contentEquals(photo))
    }

    @Test
    fun replacingOrRemovingDeletesTheOldCopyEverywhere() = runTest {
        val (fold, mac) = pair()
        fold.addDocument("Passport expires 3 Mar 2029")
        val id = fold.documentsView.value.rows.single().id
        fold.addVaultCopy(id, "image/jpeg", photo64)
        val first = server.copies.keys.single()
        val again = fold.addVaultCopy(id, "image/jpeg", Base64.encode(photo.reversedArray()))
        assertTrue(again.line.startsWith("Copy replaced"), again.line)
        assertEquals(1, server.copies.size)
        assertFalse(first in server.copies)
        fold.syncNow(); mac.syncNow()
        assertTrue(Base64.decode(mac.openVaultCopy(id).base64!!).contentEquals(photo.reversedArray()))

        assertEquals(VaultFileRules.REMOVED, mac.removeVaultCopy(id).line)
        assertTrue(server.copies.isEmpty())
        mac.syncNow(); fold.syncNow()
        assertFalse(fold.documentsView.value.rows.single().hasCopy)
        assertEquals(VaultFileRules.GONE, fold.openVaultCopy(id).problem)

        // Removing the document takes its copy off the server too.
        fold.addVaultCopy(id, "image/jpeg", photo64)
        assertNotNull(server.copies.values.singleOrNull())
        assertTrue(fold.removeDocument(id))
        assertTrue(server.copies.isEmpty())
    }
}
