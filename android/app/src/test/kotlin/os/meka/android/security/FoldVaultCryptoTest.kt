package os.meka.android.security

import os.meka.core.domain.VaultKeyRules
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The Fold's Vault crypto with software keys standing in for the Keystore's (Vault slice 2a). */
class FoldVaultCryptoTest {
    private class SoftwareKey : FoldVaultCrypto.AgreementKey {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override fun publicKey(): PublicKey = pair.public
        override fun agree(other: PublicKey): ByteArray =
            KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(other, true); generateSecret() }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test
    fun aKeySealedForTheMacOpensOnlyOnTheMac() {
        val fold = FoldVaultCrypto(SoftwareKey())
        val mac = FoldVaultCrypto(SoftwareKey())
        val other = FoldVaultCrypto(SoftwareKey())
        val macPub = assertNotNull(mac.agreementPublicKey())
        val vaultKey = ByteArray(32) { (it * 7).toByte() }
        val id = VaultKeyRules.keyId(vaultKey)

        // The Fold seals with a one-off key; the Mac's agreement with that one-off key gives the same secret.
        val once = assertNotNull(fold.agreeOnce(macPub))
        val shared = Base64.getDecoder().decode(once.shared)
        assertEquals(32, shared.size)
        assertEquals(once.shared, mac.agree(once.publicKey))
        val wrapKey = b64(VaultKeyRules.wrapKey(shared, "mac01", id))
        val sealed = assertNotNull(fold.sealBox(wrapKey, b64(vaultKey)))
        assertEquals(12 + 32 + 16, Base64.getDecoder().decode(sealed).size)

        val macShared = Base64.getDecoder().decode(mac.agree(once.publicKey))
        assertEquals(b64(vaultKey), mac.openBox(b64(VaultKeyRules.wrapKey(macShared, "mac01", id)), sealed))
        // Another device's agreement gives another key, which the box refuses; so does a changed box.
        val otherShared = Base64.getDecoder().decode(other.agree(once.publicKey))
        assertNull(other.openBox(b64(VaultKeyRules.wrapKey(otherShared, "mac01", id)), sealed))
        val bytes = Base64.getDecoder().decode(sealed).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(mac.openBox(wrapKey, b64(bytes)))
        // Rubbish in, nothing out.
        assertNull(mac.agree("not a key"))
        assertNull(fold.sealBox("c2hvcnQ=", b64(vaultKey)))
    }
}
