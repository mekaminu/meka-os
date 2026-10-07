package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64

/**
 * The Firebase service account the owner pasted into Secrets Manager (`meka-os-dev/fcm/service-account`): the JSON
 * key file from the Firebase console. Only the four fields push needs are read; `{}` (the placeholder) means push off.
 */
data class FcmServiceAccount(val projectId: String, val clientEmail: String, val privateKey: PrivateKey) {
    companion object {
        const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        private val projectPattern = Regex("^[a-z][a-z0-9-]{4,61}[a-z0-9]$")

        /** Null for the placeholder, a different kind of key file, or anything malformed. */
        fun parse(json: String?): FcmServiceAccount? = runCatching {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content?.trim().orEmpty()
            if (s("type") != "service_account") return null
            val project = s("project_id").takeIf { projectPattern.matches(it) } ?: return null
            val email = s("client_email").takeIf { it.endsWith(".iam.gserviceaccount.com") && '@' in it } ?: return null
            // The key file names Google's token endpoint; anything else is refused rather than sent a signed assertion.
            if (s("token_uri").isNotEmpty() && s("token_uri") != TOKEN_URI) return null
            FcmServiceAccount(project, email, pkcs8(s("private_key")) ?: return null)
        }.getOrNull()

        private fun pkcs8(pem: String): PrivateKey? {
            if ("BEGIN PRIVATE KEY" !in pem) return null
            val b64 = pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----").filterNot { it.isWhitespace() }
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
        }
    }
}

/** The OAuth 2.0 JWT-bearer assertion Google exchanges for an access token (RFC 7523), signed RS256. */
object GoogleJwt {
    const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun assertion(sa: FcmServiceAccount, nowSec: Long, scope: String = FCM_SCOPE): String {
        val header = buildJsonObject { put("alg", "RS256"); put("typ", "JWT") }
        val claims = buildJsonObject {
            put("iss", sa.clientEmail); put("scope", scope); put("aud", FcmServiceAccount.TOKEN_URI)
            put("iat", nowSec); put("exp", nowSec + 3600)
        }
        val signingInput = b64.encodeToString(header.toString().toByteArray()) + "." + b64.encodeToString(claims.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(sa.privateKey); update(signingInput.toByteArray()); sign()
        }
        return signingInput + "." + b64.encodeToString(sig)
    }
}

/** A minimal HTTP POST, so the sender is testable without the network. */
fun interface HttpPost {
    /** Returns the status and body. Throws on network errors. */
    fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String>

    companion object {
        fun jdk(): HttpPost {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            return HttpPost { url, headers, body ->
                val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(body))
                headers.forEach { (k, v) -> req.header(k, v) }
                val r = client.send(req.build(), HttpResponse.BodyHandlers.ofString())
                r.statusCode() to r.body().take(4_000)
            }
        }
    }
}

/**
 * Firebase Cloud Messaging HTTP v1 (ADR-004, ADR-007). The service account is read at use time (cached for five
 * minutes), so pasting the key in Secrets Manager turns push on without a redeploy; the access token is cached until
 * five minutes before it expires. Messages are data-only `{"t":"sync"}` at normal priority with a collapse key, so
 * several wake-ups waiting for a dozing phone arrive as one; none shows anything by itself.
 */
class FcmSender(
    private val account: () -> FcmServiceAccount?,
    private val http: HttpPost = HttpPost.jdk(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PushSender {
    private var access: Pair<String, Long>? = null // token, expires at (ms)

    override fun wake(address: PushAddress): SendResult {
        if (address.service != SERVICE) return SendResult.FAILED
        val sa = account() ?: return SendResult.OFF
        val token = accessToken(sa) ?: return SendResult.FAILED
        val (status, body) = runCatching {
            http.post(
                "https://fcm.googleapis.com/v1/projects/${sa.projectId}/messages:send",
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json; charset=UTF-8"),
                message(address.token).toString(),
            )
        }.getOrElse { return SendResult.FAILED }
        return when {
            status in 200..299 -> SendResult.SENT
            // The token was unregistered (app uninstalled or its data cleared) or isn't a token at all.
            status == 404 || (status == 400 && ("UNREGISTERED" in body || "registration token" in body)) -> SendResult.GONE
            status == 401 -> { synchronized(this) { access = null }; SendResult.FAILED }
            else -> SendResult.FAILED
        }
    }

    @Synchronized
    private fun accessToken(sa: FcmServiceAccount): String? {
        access?.let { (t, exp) -> if (nowMs() < exp - 5 * 60_000) return t }
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + GoogleJwt.assertion(sa, nowMs() / 1000)
        val (status, body) = runCatching {
            http.post(FcmServiceAccount.TOKEN_URI, mapOf("Content-Type" to "application/x-www-form-urlencoded"), form)
        }.getOrElse { return null }
        if (status !in 200..299) return null
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val t = o["access_token"]?.jsonPrimitive?.content ?: return null
        val ttl = o["expires_in"]?.jsonPrimitive?.long ?: 3600
        access = t to nowMs() + ttl * 1000
        return t
    }

    companion object {
        const val SERVICE = "fcm"

        fun message(token: String): JsonObject = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("data") { put("t", "sync") }
                putJsonObject("android") {
                    put("priority", "normal")
                    put("collapse_key", "sync")
                    put("ttl", "3600s")
                }
            }
        }

        /** Reads the service account from Secrets Manager by ARN, cached for five minutes; null when unset or `{}`. */
        fun fromSecret(secretId: String): FcmSender {
            val sm = SecretsManagerClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, FcmServiceAccount?>? = null
            val lock = Any()
            return FcmSender(account = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }
                    if (hit != null) hit.second else {
                        val v = runCatching { FcmServiceAccount.parse(sm.getSecretValue { it.secretId(secretId) }.secretString()) }.getOrNull()
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            })
        }
    }
}
