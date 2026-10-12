package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.ApprovalConfirmation
import os.meka.core.domain.ApprovalDecision
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import java.security.PrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kestrel's trading approvals, MEKA's side (build plan V1): the signed request and status routes. */
class ApprovalDeskTest {
    private val devices = InMemoryDeviceRegistry().also { it.enrol("hh", "fold") }
    private val keys = ReleasePublisher.generateKeyPair()
    private val kestrelKey = ReleasePublisher.privateKey(keys.first)
    private val ops = InMemoryServerOpStore()
    private var nowMs = 1_760_000_000_000L
    private val woken = mutableListOf<String>()
    private val desk = ApprovalDesk(ops, ApprovalDesk.scanning(ops), now = { nowMs }, onWritten = { woken += it })

    private fun ApplicationTestBuilder.server(publicKey: String? = keys.second, registry: DeviceRegistry = devices) = application {
        mekaSync(ops, registry, approvals = ApprovalRoutes(desk, listOf(ApprovalDesk.kestrel { publicKey })))
    }

    private fun signed(key: PrivateKey, path: String, body: String, name: String = "kestrel"): Map<String, String> =
        ReleasePublisher.headers(key, path, body) + ("Authorization" to "${ApprovalDesk.AUTH_SCHEME} $name")

    private suspend fun ApplicationTestBuilder.ask(path: String, body: String, key: PrivateKey = kestrelKey, name: String = "kestrel") =
        client.post(path) { signed(key, path, body, name).forEach { (k, v) -> header(k, v) }; setBody(body) }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private val trade = """{"ref":"k-1","kind":"trade","title":"Buy 0.05 BTC at market","lines":["About £2,410","Stop loss 5%"],"expiresAtMs":${1_760_000_000_000L + 30 * 60_000}}"""

    /** Meka's answer, written as a device would (the app's [ApprovalRules.decide], after the fingerprint). */
    private fun answer(id: String, approve: Boolean) {
        val a = ApprovalRules.parse(id, ApprovalDesk.scanning(ops)("hh")[id]!!.mapValues { it.value.value })
        val d = ApprovalRules.decide(a, approve, ApprovalConfirmation.BIOMETRIC, nowMs, "key-$id", "fold") as ApprovalDecision.Write
        val clock = HlcClock("fold", { nowMs })
        for ((f, v) in d.fields) ops.append(Op("fold$f$id", "hh", EntityTypes.APPROVAL, id, f, v, clock.now(), emptyList(), "fold"))
    }

    @Test
    fun kestrelAsksOnceTheCardIsWrittenAndItReadsMekasAnswer() = testApplication {
        server()
        val r = ask(ApprovalDesk.REQUEST_PATH, trade)
        assertEquals(HttpStatusCode.OK, r.status)
        val first = json(r.bodyAsText())
        val id = first["id"]!!.jsonPrimitive.content
        assertEquals(ApprovalRules.entityId("Kestrel", "k-1"), id)
        assertEquals("pending", first["state"]!!.jsonPrimitive.content)
        assertEquals(listOf("hh"), woken)
        // The request is an approval the apps read: who asked is always "Kestrel", whatever the body says.
        val a = ApprovalRules.parse(id, ApprovalDesk.scanning(ops)("hh")[id]!!.mapValues { it.value.value })!!
        assertEquals("Kestrel", a.source)
        assertEquals("Buy 0.05 BTC at market", a.title)
        assertEquals(listOf("About £2,410", "Stop loss 5%"), a.lines)

        // Sent again (a retry): the same card, nothing new written, nobody woken twice.
        val before = ops.after("hh", 0, Int.MAX_VALUE).size
        assertEquals(id, json(ask(ApprovalDesk.REQUEST_PATH, trade).bodyAsText())["id"]!!.jsonPrimitive.content)
        assertEquals(before, ops.after("hh", 0, Int.MAX_VALUE).size)
        assertEquals(1, woken.size)

        // No decision key while it waits; after Meka's fingerprint-confirmed yes, the answer and its key.
        val waiting = json(ask(ApprovalDesk.STATUS_PATH, """{"ref":"k-1"}""").bodyAsText())
        assertNull(waiting["decisionKey"])
        answer(id, approve = true)
        val done = json(ask(ApprovalDesk.STATUS_PATH, """{"ref":"k-1"}""").bodyAsText())
        assertEquals("approved", done["state"]!!.jsonPrimitive.content)
        assertEquals("biometric", done["confirmedBy"]!!.jsonPrimitive.content)
        assertEquals("key-$id", done["decisionKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnansweredRequestExpiresAndAnUnknownRefIsNotFound() = testApplication {
        server()
        ask(ApprovalDesk.REQUEST_PATH, trade)
        nowMs += 31 * 60_000
        assertEquals("expired", json(ask(ApprovalDesk.STATUS_PATH, """{"ref":"k-1"}""").bodyAsText())["state"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, ask(ApprovalDesk.STATUS_PATH, """{"ref":"k-2"}""").status)
    }

    @Test
    fun kestrelMayAskForTradesOnlyAndNotFloodNeedsYou() = testApplication {
        server()
        val pay = """{"ref":"p-1","kind":"payment","title":"Pay £50"}"""
        assertEquals(HttpStatusCode.Forbidden, ask(ApprovalDesk.REQUEST_PATH, pay).status)
        assertEquals(HttpStatusCode.BadRequest, ask(ApprovalDesk.REQUEST_PATH, """{"ref":"bad ref!","kind":"trade","title":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, ask(ApprovalDesk.REQUEST_PATH, """{"ref":"k-9","kind":"trade","title":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, ask(ApprovalDesk.REQUEST_PATH, "not json").status)
        repeat(ApprovalDesk.MAX_WAITING) { i ->
            assertEquals(HttpStatusCode.OK, ask(ApprovalDesk.REQUEST_PATH, """{"ref":"k-$i","kind":"trade","title":"Trade $i"}""").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, ask(ApprovalDesk.REQUEST_PATH, """{"ref":"k-99","kind":"trade","title":"One more"}""").status)
        // A declined one no longer counts.
        answer(ApprovalRules.entityId("Kestrel", "k-0"), approve = false)
        assertEquals(HttpStatusCode.OK, ask(ApprovalDesk.REQUEST_PATH, """{"ref":"k-99","kind":"trade","title":"One more"}""").status)
    }

    @Test
    fun anotherKeyAReplayAnotherNameOrNoKeyIsRefused() = testApplication {
        server()
        val stranger = ReleasePublisher.privateKey(ReleasePublisher.generateKeyPair().first)
        assertEquals(HttpStatusCode.Unauthorized, ask(ApprovalDesk.REQUEST_PATH, trade, key = stranger).status)
        assertEquals(HttpStatusCode.Unauthorized, ask(ApprovalDesk.REQUEST_PATH, trade, name = "merlin").status)
        val once = signed(kestrelKey, ApprovalDesk.REQUEST_PATH, trade)
        suspend fun with(h: Map<String, String>, path: String = ApprovalDesk.REQUEST_PATH) =
            client.post(path) { h.forEach { (k, v) -> header(k, v) }; setBody(trade) }.status
        assertEquals(HttpStatusCode.OK, with(once))
        assertEquals(HttpStatusCode.Unauthorized, with(once)) // the nonce was used
        // Signed for the other route.
        assertEquals(HttpStatusCode.Unauthorized, with(signed(kestrelKey, ApprovalDesk.STATUS_PATH, trade)))
        assertTrue(ops.after("hh", 0, Int.MAX_VALUE).isNotEmpty())
    }

    @Test
    fun withNoKeySetNothingIsFiled() = testApplication {
        server(publicKey = null)
        assertEquals(HttpStatusCode.Forbidden, ask(ApprovalDesk.REQUEST_PATH, trade).status)
        assertTrue(ops.after("hh", 0, Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun anAskerMayCallNothingElse() = testApplication {
        server()
        for (path in listOf("/v1/sync/push", "/v1/sync/pull", "/v1/sync/wait", "/v1/devices/key", "/v1/health/household")) {
            val r = ask(path, "{}")
            assertEquals(HttpStatusCode.Forbidden, r.status, path)
        }
        assertFalse(ApprovalDesk.validRef("é"))
        assertTrue(ApprovalDesk.validRef("2026-10-12T21:40:00.k1"))
    }
}
