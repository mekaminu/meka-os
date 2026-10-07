package os.meka.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.backend.integrations.Integrations
import os.meka.core.domain.ActivityRules
import os.meka.core.domain.AppUpdateRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import os.meka.core.wire.WireCodec
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * The release-only publisher (build plan: hands-free phone updates). GitHub builds the phone app after a green CI run
 * and publishes it with a P-256 key whose private half lives in Secrets Manager, readable only by the GitHub deploy
 * role. It is not a household device: it has no bearer secret, it is known to the server only by its public key, and
 * it may call nothing but `/v1/releases/latest` (to see what is published) and `/v1/releases/upload`; every other
 * route answers 403. Its builds are recorded as published by [ID] ("GitHub build"). The phone still checks the whole
 * file's SHA-256, Android still refuses a build not signed with the installed app's key, and nothing installs
 * without Meka's tap.
 */
object ReleasePublisher {
    const val ID = "github-build"
    const val LABEL = "GitHub build"
    const val AUTH_SCHEME = "Publisher"
    /** The only routes the publisher may call. */
    val ROUTES = setOf("/v1/releases/latest", "/v1/releases/upload")

    /** A fresh key pair: (PKCS#8 private key, X.509 public key), both standard base64. */
    fun generateKeyPair(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val b64 = Base64.getEncoder()
        return b64.encodeToString(pair.private.encoded) to b64.encodeToString(pair.public.encoded)
    }

    fun privateKey(pkcs8B64: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8B64.trim())))

    /** The headers of a signed publisher request, exactly as [RequestVerifier] checks them. */
    fun headers(key: PrivateKey, path: String, body: String, timeMs: Long = System.currentTimeMillis(), nonce: String = newNonce()): Map<String, String> {
        val canonical = listOf("MEKA1", "POST", path, timeMs.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(canonical.toByteArray()); sign() }
        return mapOf(
            "Authorization" to "$AUTH_SCHEME $ID",
            "X-Meka-Time" to timeMs.toString(),
            "X-Meka-Nonce" to nonce,
            "X-Meka-Signature" to Base64.getEncoder().encodeToString(sig),
        )
    }

    private fun newNonce() = UUID.randomUUID().toString().replace("-", "")
}

/**
 * Lists the GitHub build's publishes in Activity ("GitHub build published build 412", build plan: hands-free phone
 * updates). When a build from [ReleasePublisher] is complete, the server writes one `agent_action` entry into the
 * household's synced data as server-authored ops (like the mirrored calendars; ADR-008 addendum), so every device shows
 * it with no new route. Builds a household device published (the Mac's manual fallback) aren't listed: the Mac did
 * that itself. Op ids are fixed per build and field, so a build is recorded once however often it is reported.
 */
class ReleaseActivity(
    private val ops: ServerOpStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Called after an entry was written, so push can wake the household's devices. */
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)

    /** Returns whether an entry was written. */
    fun record(who: DeviceIdentity, release: WireCodec.AppRelease): Boolean {
        if (who.deviceId != ReleasePublisher.ID) return false
        val hh = who.householdId
        val id = ActivityRules.releaseId(release.platform, release.versionCode)
        val build = AppUpdateRules.Build(release.versionCode, release.versionName, release.sizeBytes)
        val fields = ActivityRules.releaseFields(ReleasePublisher.LABEL, "release:${ReleasePublisher.ID}", build, now())
        val wrote = ops.transaction {
            var appended = false
            for ((field, value) in fields) {
                val opId = opId(id, field)
                if (ops.find(hh, opId) != null) continue
                ops.append(
                    Op(
                        opId = opId, householdId = hh, entityType = EntityTypes.AGENT_ACTION, entityId = id, field = field,
                        value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = emptyList(), deviceId = Integrations.SERVER_DEVICE,
                    ),
                )
                appended = true
            }
            appended
        }
        if (wrote) runCatching { onWritten(hh) }
        return wrote
    }

    companion object {
        fun opId(entryId: String, field: String) = "srvrel$entryId${field.lowercase().filter(Char::isLetterOrDigit)}"
    }
}

/** Where the server finds the publisher's public key; null while none is set (then every publisher request is refused). */
fun interface PublisherKeySource {
    fun publicKey(): String?
}

/**
 * Reads `{"public_key": "<X.509 base64>"}` from Secrets Manager at use time (cached for a minute). The GitHub publish
 * job writes it the first time it makes the key pair, so no redeploy is needed. The CDK placeholder is `{}`.
 */
class SecretsManagerPublisherKey(private val secretId: String) : PublisherKeySource {
    private val sm = SecretsManagerClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
    private var cached: Pair<Long, String?>? = null

    @Synchronized
    override fun publicKey(): String? {
        cached?.let { (at, v) -> if (System.currentTimeMillis() - at < 60_000) return v }
        val v = runCatching {
            Json.parseToJsonElement(sm.getSecretValue { it.secretId(secretId) }.secretString()).jsonObject["public_key"]
                ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        cached = System.currentTimeMillis() to v
        return v
    }
}

/** The publisher's side: sees what is published and uploads a build chunk by chunk. */
class ReleasePublisherClient(private val key: PrivateKey, private val post: Post) {
    /** One HTTP POST: (status, body). */
    fun interface Post {
        suspend fun post(path: String, headers: Map<String, String>, body: String): Pair<Int, String>
    }

    sealed class Outcome {
        data class Published(val release: WireCodec.AppRelease) : Outcome()
        /** Not newer than what is published, or the same build number with other contents (e.g. a Mac build). */
        data class NotNewer(val reason: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private suspend fun send(path: String, body: String): Pair<Int, String> = post.post(path, ReleasePublisher.headers(key, path, body), body)

    /** The newest published build for [platform], or null when none is. Throws when the server refuses. */
    suspend fun latest(platform: String): WireCodec.AppRelease? {
        val (status, body) = send("/v1/releases/latest", WireCodec.encodePlatform(platform))
        if (status != 200) throw IllegalStateException("HTTP $status from /v1/releases/latest")
        return WireCodec.decodeRelease(body)
    }

    suspend fun publish(platform: String, bytes: ByteArray, versionCode: Long, versionName: String): Outcome {
        if (bytes.isEmpty() || bytes.size > WireCodec.RELEASE_MAX_BYTES) return Outcome.Failed("the file is empty or too large")
        val size = bytes.size.toLong()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release = WireCodec.AppRelease(platform, versionCode, versionName, sha, size, WireCodec.releaseChunkCount(size))
        var complete = false
        for (i in 0 until release.chunkCount) {
            val from = i * WireCodec.RELEASE_CHUNK_BYTES
            val part = bytes.copyOfRange(from, from + WireCodec.releaseChunkSize(size, i))
            val body = WireCodec.encodeReleaseChunk(WireCodec.ReleaseChunk(release, i, Base64.getEncoder().encodeToString(part)))
            var attempt = 0
            while (true) {
                val (status, text) = runCatching { send("/v1/releases/upload", body) }.getOrElse { 0 to (it.message ?: "network error") }
                when {
                    status == 200 -> { complete = WireCodec.decodeUploadAck(text).complete; break }
                    status == 409 -> return Outcome.NotNewer(text.take(200))
                    (status == 0 || status >= 500) && ++attempt < ATTEMPTS -> continue // what arrived is kept
                    else -> return Outcome.Failed("HTTP $status from /v1/releases/upload: ${text.take(200)}")
                }
            }
        }
        return if (complete) Outcome.Published(release) else Outcome.Failed("the server didn't confirm the build")
    }

    companion object {
        private const val ATTEMPTS = 3

        /** The real transport: HTTPS to [baseUrl] (the stack's SyncUrl). */
        fun http(baseUrl: String): Post {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
            val base = baseUrl.trimEnd('/')
            return Post { path, headers, body ->
                val req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                resp.statusCode() to resp.body()
            }
        }
    }
}

/**
 * Command line for the GitHub publish job (`backend <command> …`); none of these touch the database.
 *  - `publisher-keygen`: prints `{"private_key": …, "public_key": …}` for the job to store.
 *  - `publisher-latest <url>`: prints the newest published phone build number (0 when none).
 *  - `publisher-publish <url> <apk> <output-metadata.json>`: publishes the APK; exit 0 when published or not newer.
 * The private key comes from the environment (`MEKA_PUBLISHER_PRIVATE_KEY`), never an argument, and is never printed.
 */
object ReleasePublisherCli {
    val COMMANDS = setOf("publisher-keygen", "publisher-latest", "publisher-publish")

    fun run(args: List<String>): Int {
        if (args.first() == "publisher-keygen") {
            val (priv, pub) = ReleasePublisher.generateKeyPair()
            println("""{"private_key":"$priv","public_key":"$pub"}""")
            return 0
        }
        val pkcs8 = System.getenv("MEKA_PUBLISHER_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
            ?: return fail("MEKA_PUBLISHER_PRIVATE_KEY is not set")
        val url = args.getOrNull(1) ?: return fail("usage: ${args.first()} <server url> …")
        val client = ReleasePublisherClient(ReleasePublisher.privateKey(pkcs8), ReleasePublisherClient.http(url))
        return runBlocking {
            when (args.first()) {
                "publisher-latest" -> { println(client.latest(PLATFORM)?.versionCode ?: 0); 0 }
                else -> {
                    val apk = File(args.getOrNull(2) ?: return@runBlocking fail("usage: publisher-publish <url> <apk> <metadata>"))
                    val meta = File(args.getOrNull(3) ?: return@runBlocking fail("usage: publisher-publish <url> <apk> <metadata>"))
                        .takeIf { it.isFile }?.readText()?.let { WireCodec.decodeApkMetadata(it) }
                        ?: return@runBlocking fail("no readable output-metadata.json")
                    if (meta.applicationId != APP_ID) return@runBlocking fail("not the MEKA app (${meta.applicationId.take(60)})")
                    when (val r = client.publish(PLATFORM, apk.readBytes(), meta.versionCode, meta.versionName)) {
                        is ReleasePublisherClient.Outcome.Published -> { println("Published build ${r.release.versionCode} (${r.release.sizeBytes} bytes)"); 0 }
                        is ReleasePublisherClient.Outcome.NotNewer -> { println("::notice title=Phone update not published::${r.reason}"); 0 }
                        is ReleasePublisherClient.Outcome.Failed -> fail(r.reason)
                    }
                }
            }
        }
    }

    private const val PLATFORM = "android"
    private const val APP_ID = "os.meka.android"

    private fun fail(why: String): Int { System.err.println("::error title=Phone update::$why"); return 1 }
}
