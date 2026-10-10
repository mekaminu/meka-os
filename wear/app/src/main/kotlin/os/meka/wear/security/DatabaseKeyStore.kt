package os.meka.wear.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the 256-bit SQLCipher key wrapped by a non-exportable Android Keystore AES-GCM key (ADR-002).
 * Only the wrapped blob is stored in app-private prefs; the raw key exists in memory only while opening the DB.
 *
 * The same scheme as the phone's: the wrapping key needs no user authentication, so MEKA opens on the wrist offline
 * after a restart; the watch's own screen lock guards it.
 */
class DatabaseKeyStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("meka.keys", Context.MODE_PRIVATE)

    fun getOrCreateKey(): ByteArray {
        prefs.getString(PREF_WRAPPED, null)?.let { return unwrap(it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // commit(), not apply(): the key must be durable before any data is encrypted with it.
        check(prefs.edit().putString(PREF_WRAPPED, wrap(raw)).commit()) { "could not persist database key" }
        return raw
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val out = c.iv + c.doFinal(raw)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(blob: String): ByteArray {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv)) }
        return c.doFinal(bytes, 12, bytes.size - 12)
    }

    private companion object {
        const val ALIAS = "meka.db.wrap.v1"
        const val PREF_WRAPPED = "db_key_wrapped_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
