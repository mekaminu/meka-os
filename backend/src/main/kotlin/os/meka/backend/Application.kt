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
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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

        // One-time device enrolment (ADR-005 M0). Disabled unless MEKA_ENROL_TOKEN is configured.
        post("/v1/enrol") {
            val token = enrolToken?.takeIf { it.length >= 32 } ?: throw Unauthorised()
            val auth = call.request.header("Authorization") ?: throw Unauthorised()
            if (!auth.startsWith("Enrol ") || !Secrets.constantTimeEquals(auth.removePrefix("Enrol ").trim(), token)) throw Unauthorised()
            val req = WireCodec.decodeEnrolRequest(call.boundedBody())
            val secret = withContext(Dispatchers.IO) { devices.enrol(req.householdId, req.deviceId, req.name) }
            call.application.environment.log.info("device enrolled") // no identifiers in logs
            call.respondText(WireCodec.encodeEnrolResponse(secret), ContentType.Application.Json)
        }

        post("/v1/sync/push") {
            val who = call.device(devices)
            val req = WireCodec.decodePushRequest(call.boundedBody())
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val resp = withContext(Dispatchers.IO) { sync.push(req) } // blocking JDBC off the request threads
            call.respondText(WireCodec.encodePushResponse(resp), ContentType.Application.Json)
        }

        post("/v1/sync/pull") {
            val who = call.device(devices)
            val req = WireCodec.decodePullRequest(call.boundedBody())
            if (req.householdId != who.householdId || req.deviceId != who.deviceId) throw Forbidden()
            val resp = withContext(Dispatchers.IO) { sync.pull(req) }
            call.respondText(WireCodec.encodePullResponse(resp), ContentType.Application.Json)
        }

        // Long-poll for an open app: answers as soon as the household has ops after the cursor, else empty after
        // [waitMs]. Checks the database each [waitCheckMs] (an indexed query), so it stays correct with several tasks.
        post("/v1/sync/wait") {
            val who = call.device(devices)
            val req = WireCodec.decodePullRequest(call.boundedBody()).copy(limit = 1)
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

private fun ApplicationCall.device(devices: DeviceRegistry): DeviceIdentity {
    val auth = request.header("Authorization") ?: throw Unauthorised()
    if (!auth.startsWith("Bearer ")) throw Unauthorised()
    return devices.authenticate(auth.removePrefix("Bearer ").trim()) ?: throw Unauthorised()
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
            embeddedServer(Netty, port = port) { mekaSync(PostgresOpStore(ds), PostgresDeviceRegistry(ds), enrolToken) }.start(wait = true)
        }
    }
}

object Migrations {
    private val all = listOf(1 to "/db/V1__sync.sql")

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
