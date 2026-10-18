package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Vault key's rules (Vault slice 2a): ids, codes, who's asking and what each device's block says. */
class VaultKeyTest {
    private val key = ByteArray(32) { it.toByte() }
    private val id = VaultKeyRules.keyId(key)
    private val macKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmac"

    private fun member(device: String, name: String, asked: Long = 10, refused: Long? = null, holds: String? = null, pub: String = "pk-$device") =
        VaultMember(device, pub, name, asked, refused, holds)

    @Test
    fun idsAndCodesAreShortStableAndDifferPerKey() {
        assertEquals(8, id.length)
        assertEquals(id, id.uppercase())
        assertEquals(id, VaultKeyRules.keyId(key.copyOf()))
        assertNotEquals(id, VaultKeyRules.keyId(ByteArray(32)))
        assertEquals(id.take(4) + " " + id.drop(4), VaultKeyRules.display(id))
        val code = VaultKeyRules.code(macKey)
        assertTrue(Regex("^\\d{3} \\d{3}$").matches(code))
        assertEquals(code, VaultKeyRules.code(macKey))
        assertNotEquals(code, VaultKeyRules.code(macKey + "x"))
        // The wrap key is bound to the device and the key id.
        assertFalse(VaultKeyRules.wrapKey(key, "mac01", id).contentEquals(VaultKeyRules.wrapKey(key, "mac02", id)))
        assertEquals(32, VaultKeyRules.recoveryKey(key).size)
        assertEquals(id to "QUJD", VaultKeyRules.readLocal(VaultKeyRules.local(id, "QUJD")))
        assertEquals(null, VaultKeyRules.readLocal("nonsense"))
    }

    @Test
    fun theHolderSeesWhoAsksAndWhereTheKeyIs() {
        val fold = member("android01", "Fold", holds = id)
        val mac = member("mac01", "Mac", asked = 20, pub = macKey)
        val s = VaultState("android01", "Fold", true, "pk-android01", id, id, listOf(fold, mac), emptyList())
        val v = VaultKeyRules.view(s)
        assertEquals("Vault key ${VaultKeyRules.display(id)} · on this Fold", v.line)
        assertFalse(v.lit)
        assertEquals("Make a new recovery key (Settings → Recovery key) so it opens the Vault too", v.sub)
        val r = v.requests.single()
        assertEquals("mac01", r.deviceId)
        assertEquals("The Mac wants to open your Vault documents", r.title)
        assertEquals("Allow only if the Mac shows ${VaultKeyRules.code(macKey)}", r.line)

        // Sealed for the Mac and it holds it: no request, both named; the recovery seal noted.
        val wraps = listOf(
            VaultWrap("mac01", id, "eph", "box", "Fold", 30),
            VaultWrap(VaultKeyRules.RECOVERY, id, null, "box", "Fold", 31),
        )
        val after = VaultKeyRules.view(s.copy(members = listOf(fold, mac.copy(holdsKeyId = id)), wraps = wraps))
        assertEquals("Vault key ${VaultKeyRules.display(id)} · on this Fold and the Mac", after.line)
        assertEquals("Your recovery key opens it too", after.sub)
        assertTrue(after.requests.isEmpty())
        // Sealed but not opened yet: no request either.
        assertTrue(VaultKeyRules.requests(listOf(mac), wraps, id, "android01").isEmpty())
        // A Mac that asked again after the seal (a new key) asks again; a refused one doesn't, until it asks again.
        assertEquals(1, VaultKeyRules.requests(listOf(mac.copy(askedAtMs = 40)), wraps, id, "android01").size)
        assertTrue(VaultKeyRules.requests(listOf(mac.copy(refusedAtMs = 25)), emptyList(), id, "android01").isEmpty())
        assertEquals(1, VaultKeyRules.requests(listOf(mac.copy(refusedAtMs = 15)), emptyList(), id, "android01").size)
    }

    @Test
    fun aDeviceWithoutTheKeySaysWhatItWaitsFor() {
        val fold = member("android01", "Fold", holds = id)
        val mac = member("mac01", "Mac", asked = 20, pub = macKey)
        val s = VaultState("mac01", "Mac", true, macKey, null, id, listOf(fold, mac), emptyList())
        val v = VaultKeyRules.view(s)
        assertEquals("Waiting for your Fold to let this Mac open the Vault", v.line)
        assertTrue(v.lit)
        assertEquals("On the Fold: Ask → Settings → Vault → Allow · this Mac shows ${VaultKeyRules.code(macKey)}", v.sub)
        assertFalse(v.canAskAgain)

        val no = VaultKeyRules.view(s.copy(members = listOf(fold, mac.copy(refusedAtMs = 30))))
        assertEquals("Your Fold said no to this Mac", no.line)
        assertTrue(no.canAskAgain)

        assertEquals("Your Vault key is made the next time Navilife syncs", VaultKeyRules.view(s.copy(canonical = null)).line)
        assertEquals("This Mac can't hold the Vault key", VaultKeyRules.view(s.copy(available = false)).line)
        // The watch (no name) shows nothing.
        assertFalse(VaultKeyRules.view(s.copy(myName = null)).shown)
    }
}
