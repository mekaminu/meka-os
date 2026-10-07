package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import os.meka.backend.integrations.BbcNewsRss
import os.meka.backend.integrations.EspnTeamFixtures
import os.meka.backend.integrations.GoogleCalendar
import os.meka.backend.integrations.GovUkBankHolidays
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.KmsTokenCipher
import os.meka.backend.integrations.MicrosoftCalendar
import os.meka.backend.integrations.PostgresIntegrationStore
import os.meka.backend.integrations.SecretsManagerOAuthClients
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import os.meka.core.sync.ServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.wire.WireCodec
import os.meka.core.wire.WireFormatException
import javax.sql.DataSource

private const val MAX_BODY_BYTES = 2 * 1024 * 1024

class Unauthorised : RuntimeException()
class Forbidden : RuntimeException()

/** Wires the sync API. Pure function of its dependencies so tests run it against in-memory stores. */
fun Application.mekaSync(
    opStore: ServerOpStore,
    devices: DeviceRegistry,
    enrolToken: String? = null,
    /** Long-poll window. Under CloudFront's 30 s origin timeout and the ALB's 60 s idle timeout. */
    waitMs: Long = 20_000,
    waitCheckMs: Long = 1_000,
    /** Connected calendars (ADR-008); null disables the integration routes (tests, or an unconfigured server). */
    integrations: Integrations? = null,
    /** Runs work off the request (the first calendar sync right after connecting). */
    background: (() -> Unit) -> Unit = { Thread.ofVirtual().start(it) },
    verifier: RequestVerifier = RequestVerifier(),
    /** Published app builds (self-updating phone app); null disables the release routes. */
    releases: Releases? = null,
    /** Push wake-ups (build plan M1: push via Firebase); null disables the push route and wake-ups. */
    push: Push? = null,
) {
    val sync = SyncService(opStore)

    install(StatusPages) {
        exception<Unauthorised> { call, _ -> call.respondText("unauthorised", status = HttpStatusCode.Unauthorized) }
        exception<WireFormatException> { call, e -> call.respondText("bad request: ${e.message}", status = HttpStatusCode.BadRequest) }
        exception<Forbidden> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
        // Any other validation failure while decoding is the client's fault and must not be retried forever.
        exception<IllegalArgumentException> { call, _ -> call.respondText("bad request", status = HttpStatusCode.BadRequest) }
        exception<Throwable> { call, e ->
            // Never echo internals or payloads; log the class only (ADR-013: no personal data in telemetry).
            call.application.environment.log.error("unhandled ${e::class.simpleName}")
            call.respondText("internal error", status = HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        // Public pages the OAuth consent screens link to (Google requires a home page and privacy policy to publish).
        get("/") {
            call.respondText(resultPage("MEKA OS", "A private, single-household personal operating system. It is not a public service and has no sign-ups."), ContentType.Text.Html)
        }
        get("/privacy") { call.respondText(resultPage("MEKA OS privacy policy", PRIVACY_TEXT), ContentType.Text.Html) }

        // One-time device enrolment (ADR-005 M0). Disabled unless MEKA_ENROL_TOKEN is configured.
        post("/v1/enrol") {
            val token = enrolToken?.takeIf { it.length >= 32 } ?: throw Unauthorised()
            val auth = call.request.header("Authorization") ?: throw Unauthorised()
            if (!auth.startsWith("Enrol ") || !Secrets.constantTimeEquals(auth.removePrefix("Enrol ").trim(), token)) throw Unauthorised()
            val req = WireCodec.decodeEnrolRequest(call.boundedBody())
            when (val r = withContext(Dispatchers.IO) { devices.enrolWithCode(req.householdId, req.deviceId, req.name) }) {
                is EnrolOutcome.Enrolled -> {
                    call.application.environment.log.info("device enrolled") // no identifiers in logs
                    call.respondText(WireCodec.encodeEnrolResponse(r.secret), ContentType.Application.Json)
                }
                is EnrolOutcome.Refused -> {
                    call.application.environment.log.warn("enrolment refused: ${r.reason}")
                    call.respondText(r.reason, status = HttpStatusCode.Forbidden)
                }
            }
        }

        // Registers the device's hardware-bound signing key (ADR-005). The request must be signed with that very key
        // (proof of possession). After this, the device's unsigned requests are refused. Re-enrolment clears it.
        post("/v1/devices/key") {
            val body = call.boundedBody()
            val who = call.bearer(devices)
            val pub = WireCodec.decodeDeviceKey(body)
            if (!verifier.isP256(pub)) throw WireFormatException("not a P-256 key")
            val h = call.request.headers
            if (!verifier.verify(pub, "POST", call.request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])) throw Unauthorised()
            val stored = withContext(Dispatchers.IO) { devices.registerKey(who, pub) }
            if (!stored) throw Forbidden()
            call.application.environment.log.info("device key registered")
            call.respondText(WireCodec.encodeDeviceKey(pub), ContentType.Application.Json)
        }

        post("/v1/sync/push") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body)
            val req = WireCodec.decodePushRequest(body)
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val resp = withContext(Dispatchers.IO) { sync.push(req) } // blocking JDBC off the request threads
            // Wake the household's other devices so they pull now (coalesced; sent off the request).
            if (push != null && resp.acknowledged.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { push.changed(who) } }
            call.respondText(WireCodec.encodePushResponse(resp), ContentType.Application.Json)
        }

        post("/v1/sync/pull") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body)
            val req = WireCodec.decodePullRequest(body)
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val resp = withContext(Dispatchers.IO) { sync.pull(req) }
            call.respondText(WireCodec.encodePullResponse(resp), ContentType.Application.Json)
        }

        if (integrations != null) {
            // Starts connecting an account: returns the provider's sign-in URL for the app to open in a browser.
            post("/v1/integrations/{provider}/connect") {
                val who = call.device(devices, verifier, call.boundedBody(), requireKey = true)
                val provider = call.parameters["provider"].orEmpty()
                when (val r = withContext(Dispatchers.IO) { integrations.start(who.householdId, provider) }) {
                    is Integrations.StartResult.Url -> call.respondText(WireCodec.encodeConnectUrl(r.url), ContentType.Application.Json)
                    Integrations.StartResult.UnknownProvider -> call.respondText("unknown provider", status = HttpStatusCode.NotFound)
                    Integrations.StartResult.NotConfigured -> call.respondText("not configured", status = HttpStatusCode.Conflict)
                }
            }

            post("/v1/integrations/list") {
                val who = call.device(devices, verifier, call.boundedBody(), requireKey = true)
                val list = withContext(Dispatchers.IO) { integrations.accounts(who.householdId) }
                call.respondText(WireCodec.encodeAccounts(list), ContentType.Application.Json)
            }

            // The provider redirects the owner's browser here. Authenticated by the single-use state, not a device.
            get("/v1/oauth/{provider}/callback") {
                val provider = call.parameters["provider"].orEmpty()
                val q = call.request.queryParameters
                val result = withContext(Dispatchers.IO) {
                    runCatching { integrations.callback(provider, q["state"], q["code"], q["error"]) }
                        .getOrElse { Integrations.CallbackResult.Failed("The provider did not complete sign-in. Please try again.") }
                }
                val page = when (result) {
                    is Integrations.CallbackResult.Connected -> {
                        background { runCatching { integrations.syncAccount(result.accountId) } }
                        resultPage("Connected", "${providerName(result.provider)} (${result.email}) is connected. Your calendar will appear in MEKA OS within a minute. You can close this page.")
                    }
                    is Integrations.CallbackResult.Failed -> resultPage("Not connected", result.reason)
                }
                call.respondText(page, ContentType.Text.Html)
            }
        }

        if (releases != null) {
            // Self-updating phone app: the newest published build, its chunks, and publishing one (from the Mac).
            // Signed requests from keyed devices only.
            post("/v1/releases/latest") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val platform = WireCodec.decodePlatform(body)
                val latest = withContext(Dispatchers.IO) { releases.latest(who, platform) }
                call.respondText(WireCodec.encodeRelease(latest), ContentType.Application.Json)
            }

            post("/v1/releases/chunk") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val ref = WireCodec.decodeChunkRef(body)
                val bytes = withContext(Dispatchers.IO) { releases.chunk(who, ref) }
                    ?: return@post call.respondText("no such build", status = HttpStatusCode.NotFound)
                call.respondText(WireCodec.encodeChunkData(java.util.Base64.getEncoder().encodeToString(bytes)), ContentType.Application.Json)
            }

            post("/v1/releases/upload") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val chunk = WireCodec.decodeReleaseChunk(body)
                when (val r = withContext(Dispatchers.IO) { releases.upload(who, chunk) }) {
                    is Releases.Upload.Ack -> {
                        if (r.complete) call.application.environment.log.info("app build published") // no identifiers
                        call.respondText(WireCodec.encodeUploadAck(WireCodec.UploadAck(r.received, r.complete)), ContentType.Application.Json)
                    }
                    is Releases.Upload.Conflict -> call.respondText(r.reason, status = HttpStatusCode.Conflict)
                    is Releases.Upload.Rejected -> call.respondText(r.reason, status = HttpStatusCode.BadRequest)
                }
            }
        }

        if (push != null) {
            // A device's push address (an FCM token), or an empty token to stop waking it. Keyed devices only.
            post("/v1/push/token") {
                val body = call.boundedBody()
                val who = call.device(devices, verifier, body, requireKey = true)
                val t = WireCodec.decodePushToken(body)
                withContext(Dispatchers.IO) { push.register(who, t) }
                call.application.environment.log.info(if (t.token.isEmpty()) "push address removed" else "push address registered") // no identifiers
                call.respondText(WireCodec.encodePushToken(t.copy(token = "")), ContentType.Application.Json)
            }
        }

        // Long-poll for an open app: answers as soon as the household has ops after the cursor, else empty after
        // [waitMs]. Checks the database each [waitCheckMs] (an indexed query), so it stays correct with several tasks.
        post("/v1/sync/wait") {
            val body = call.boundedBody()
            val who = call.device(devices, verifier, body)
            val req = WireCodec.decodePullRequest(body).copy(limit = 1)
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val deadline = System.currentTimeMillis() + waitMs
            var page = withContext(Dispatchers.IO) { sync.pull(req) }
            while (page.ops.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(waitCheckMs)
                page = withContext(Dispatchers.IO) { sync.pull(req) }
            }
            call.respondText(WireCodec.encodePullResponse(page), ContentType.Application.Json)
        }
    }
}

private const val PRIVACY_TEXT =
    "MEKA OS is a private app used only by its owner's household. When you connect a Google or Microsoft account, " +
        "MEKA OS reads your calendar events (read-only) so they can appear in your own MEKA OS apps. Event details are " +
        "stored encrypted in the owner's own cloud account and on the owner's devices, are never sold or shared with " +
        "anyone, and are not used for advertising or to train AI models. Sign-in tokens are encrypted with a key only " +
        "this service can use. You can disconnect at any time from your Google or Microsoft account settings, after " +
        "which no further data is read. Use of information received from Google APIs adheres to the Google API Services " +
        "User Data Policy, including the Limited Use requirements."

private fun providerName(p: String) = when (p) { "google" -> "Google"; "microsoft" -> "Microsoft"; else -> p }

private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Plain, self-contained page shown in the browser after the provider's sign-in. */
private fun resultPage(title: String, message: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>MEKA OS · ${html(title)}</title>
<style>:root{color-scheme:light dark}body{font:17px/1.5 -apple-system,system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;padding:24px;box-sizing:border-box}
main{max-width:30rem}h1{font-size:1.5rem;margin:0 0 .5rem}p{margin:0;opacity:.8}</style></head>
<body><main><h1>${html(title)}</h1><p>${html(message)}</p></main></body></html>"""

private fun ApplicationCall.bearer(devices: DeviceRegistry): DeviceIdentity {
    val auth = request.header("Authorization") ?: throw Unauthorised()
    if (!auth.startsWith("Bearer ")) throw Unauthorised()
    return devices.authenticate(auth.removePrefix("Bearer ").trim()) ?: throw Unauthorised()
}

/**
 * Authenticates a device: its bearer secret, plus — once it has registered a hardware key — a valid signature over
 * this exact request. [requireKey] refuses devices that have no key yet (routes that expose personal data).
 */
private suspend fun ApplicationCall.device(devices: DeviceRegistry, verifier: RequestVerifier, body: String, requireKey: Boolean = false): DeviceIdentity {
    val who = bearer(devices)
    val key = withContext(Dispatchers.IO) { devices.publicKey(who) }
    if (key == null) {
        if (requireKey) throw Forbidden()
        return who
    }
    val h = request.headers
    if (!verifier.verify(key, request.httpMethod.value, request.path(), body, h["X-Meka-Time"], h["X-Meka-Nonce"], h["X-Meka-Signature"])) {
        throw Unauthorised()
    }
    return who
}

/** Reads at most MAX_BODY_BYTES + 1 bytes, so chunked bodies cannot exhaust memory. */
private suspend fun ApplicationCall.boundedBody(): String {
    val len = request.header("Content-Length")?.toLongOrNull()
    if (len != null && len > MAX_BODY_BYTES) throw WireFormatException("body too large")
    val bytes = receiveChannel().readRemaining((MAX_BODY_BYTES + 1).toLong()).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw WireFormatException("body too large")
    return bytes.decodeToString()
}

fun dataSourceFromEnv(): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = System.getenv("MEKA_DB_URL") ?: error("MEKA_DB_URL not set")
        username = System.getenv("MEKA_DB_USER")
        password = System.getenv("MEKA_DB_PASSWORD")
        maximumPoolSize = 5
    },
)

fun main(args: Array<String>) {
    val ds = dataSourceFromEnv()
    Migrations.apply(ds)
    when (args.firstOrNull()) {
        // Development enrolment (ADR-005). Prints the secret once; it is never stored in clear.
        "enrol-device" -> {
            val (hh, dev, name) = args.drop(1).let { a -> Triple(a[0], a[1], a.getOrElse(2) { a[1] }) }
            println(PostgresDeviceRegistry(ds).enrol(hh, dev, name))
        }
        else -> {
            val port = System.getenv("PORT")?.toInt() ?: 8080
            val enrolToken = System.getenv("MEKA_ENROL_TOKEN")
            val opStore = PostgresOpStore(ds)
            val push = pushFromEnv(ds)
            val integrations = integrationsFromEnv(opStore, onChanged = { hh -> push?.serverChanged(hh) })
            integrations?.let { startCalendarSync(it) }
            embeddedServer(Netty, port = port) {
                mekaSync(
                    opStore, PostgresDeviceRegistry(ds), enrolToken, integrations = integrations,
                    releases = Releases(PostgresReleaseStore(ds)), push = push,
                )
            }.start(wait = true)
        }
    }
}

/** Null unless the deployment provides a KMS key and a public URL (local dev runs without integrations). */
fun integrationsFromEnv(opStore: PostgresOpStore, onChanged: (householdId: String) -> Unit = {}): Integrations? {
    val key = System.getenv("MEKA_KMS_KEY_ID") ?: return null
    val publicUrl = System.getenv("MEKA_PUBLIC_URL") ?: return null
    val secrets = buildMap {
        System.getenv("MEKA_OAUTH_GOOGLE_SECRET")?.let { put("google", it) }
        System.getenv("MEKA_OAUTH_MICROSOFT_SECRET")?.let { put("microsoft", it) }
    }
    return Integrations(
        store = PostgresIntegrationStore(opStore), ops = opStore,
        providers = listOf(GoogleCalendar(), MicrosoftCalendar()).associateBy { it.id },
        clients = SecretsManagerOAuthClients(secrets), cipher = KmsTokenCipher(key), publicUrl = publicUrl,
        feeds = listOf(EspnTeamFixtures()).associateBy { it.id },
        news = listOf(BbcNewsRss()).associateBy { it.id },
        holidays = listOf(GovUkBankHolidays()).associateBy { it.id },
        onChanged = onChanged,
    )
}

/**
 * Push wake-ups through FCM. Null when the deployment names no service-account secret (local dev). With the secret
 * still `{}` the routes work and tokens are kept, but nothing is sent until the owner pastes the key.
 */
fun pushFromEnv(ds: DataSource): Push? {
    val secret = System.getenv("MEKA_FCM_SECRET")?.takeIf { it.isNotBlank() } ?: return null
    return Push(PostgresPushTokenStore(ds), FcmSender.fromSecret(secret))
}

/** Calendar mirror cadence: every 5 minutes, first run shortly after start. Failures are recorded per account. */
fun startCalendarSync(integrations: Integrations, periodMs: Long = 5 * 60_000L) {
    Thread.ofVirtual().name("calendar-sync").start {
        Thread.sleep(30_000)
        while (true) {
            runCatching { integrations.syncAll() }
            Thread.sleep(periodMs)
        }
    }
}

object Migrations {
    private val all = listOf(
        1 to "/db/V1__sync.sql", 2 to "/db/V2__integrations.sql", 3 to "/db/V3__device_keys.sql", 4 to "/db/V4__event_mirror_end.sql",
        5 to "/db/V5__app_release.sql", 6 to "/db/V6__push_token.sql",
    )

    fun apply(ds: DataSource) = ds.connection.use { c ->
        c.autoCommit = false
        c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())") }
        c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(4242)") } // one migrator at a time
        val applied = c.createStatement().use { st -> st.executeQuery("SELECT version FROM schema_migration").use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } } }
        for ((v, path) in all) if (v !in applied) {
            val sql = Migrations::class.java.getResource(path)!!.readText()
            c.createStatement().use { it.execute(sql) }
            c.prepareStatement("INSERT INTO schema_migration(version) VALUES (?)").use { it.setInt(1, v); it.executeUpdate() }
        }
        c.commit()
    }
}
