package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient

/** Encrypts integration refresh tokens before they are stored (ADR-004: MEKA-specific KMS key). */
interface TokenCipher {
    fun encrypt(plain: ByteArray, context: Map<String, String>): ByteArray
    fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray
}

/**
 * KMS direct encryption (tokens are far below KMS's 4 KB limit). The encryption context binds each ciphertext to its
 * household and provider, so a ciphertext copied to another row will not decrypt.
 */
class KmsTokenCipher(private val keyId: String) : TokenCipher {
    private val kms = KmsClient.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun encrypt(plain: ByteArray, context: Map<String, String>): ByteArray =
        kms.encrypt { it.keyId(keyId).plaintext(SdkBytes.fromByteArray(plain)).encryptionContext(context) }.ciphertextBlob().asByteArray()

    override fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray =
        kms.decrypt { it.keyId(keyId).ciphertextBlob(SdkBytes.fromByteArray(cipher)).encryptionContext(context) }.plaintext().asByteArray()
}

/** Where provider OAuth app credentials come from. Null when the owner has not set them up yet. */
fun interface OAuthClientSource {
    fun get(provider: String): OAuthClient?
}

/**
 * Reads `{"client_id","client_secret"}` from Secrets Manager at use time (cached for a minute), so pasting the
 * credentials in the console takes effect without a redeploy. The CDK placeholder has an empty client_id.
 */
class SecretsManagerOAuthClients(private val secretIds: Map<String, String>) : OAuthClientSource {
    private val sm = SecretsManagerClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
    private val cache = HashMap<String, Pair<Long, OAuthClient?>>()

    @Synchronized
    override fun get(provider: String): OAuthClient? {
        val id = secretIds[provider] ?: return null
        cache[provider]?.let { (at, v) -> if (System.currentTimeMillis() - at < 60_000) return v }
        val v = runCatching {
            val o = Json.parseToJsonElement(sm.getSecretValue { it.secretId(id) }.secretString()).jsonObject
            val cid = o["client_id"]?.jsonPrimitive?.content.orEmpty().trim()
            val sec = o["client_secret"]?.jsonPrimitive?.content.orEmpty().trim()
            if (cid.isEmpty() || sec.isEmpty()) null else OAuthClient(cid, sec)
        }.getOrNull()
        cache[provider] = System.currentTimeMillis() to v
        return v
    }
}
