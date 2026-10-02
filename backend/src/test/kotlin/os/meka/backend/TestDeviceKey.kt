package os.meka.backend

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/** A software P-256 key standing in for a device's hardware key, signing exactly as the apps do. */
class TestDeviceKey(private val now: () -> Long = System::currentTimeMillis) {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicB64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun sign(message: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message.toByteArray()); sign() },
    )

    /** Adds bearer + signature headers and the body. Returns the nonce so tests can replay it. */
    fun HttpRequestBuilder.signed(secret: String, path: String, body: String, nonce: String = UUID.randomUUID().toString().replace("-", ""), time: Long = now()): String {
        header("Authorization", "Bearer $secret")
        header("X-Meka-Time", time.toString())
        header("X-Meka-Nonce", nonce)
        header("X-Meka-Signature", sign(listOf("MEKA1", "POST", path, time.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")))
        setBody(body)
        return nonce
    }
}
