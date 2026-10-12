package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.ApprovalConfirmation
import os.meka.core.domain.ApprovalDecision
import os.meka.core.domain.ApprovalKeyFields
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.ApprovalSignature
import os.meka.core.sync.fv
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
    private val desk = ApprovalDesk(
        ops, ApprovalDesk.scanning(ops), now = { nowMs }, readKeys = ApprovalDesk.scanning(ops, EntityTypes.APPROVAL_KEY), onWritten = { woken += it },
    )
    /** The Fold's approval key (fingerprint-only on the phone; software here). */
    private val foldApprovalKey = TestDeviceKey()
    private var seq = 0
    private fun write(device: String, type: String, id: String, fields: Map<String, os.meka.core.sync.FieldValue>) {
        val clock = HlcClock(device, { nowMs })
        for ((f, v) in fields) ops.append(Op("$device${seq++}", "hh", type, id, f, v, clock.now(), emptyList(), device))
    }
    private fun registerKey(device: String = "fold", writtenBy: String = device, key: String = foldApprovalKey.publicB64) =
        write(writtenBy, EntityTypes.APPROVAL_KEY, device, mapOf(ApprovalKeyFields.PUBLIC_KEY to key.fv(), ApprovalKeyFields.LABEL to "Fold".fv(), ApprovalKeyFields.SINCE to nowMs.fv()))

    private fun ApplicationTestBuilder.server(publicKey: String? = keys.second, registry: DeviceRegistry = devices) = application {
        mekaSync(ops, registry, approvals = ApprovalRoutes(desk, listOf(ApprovalDesk.kestrel { publicKey })))
    }

    private fun signed(key: PrivateKey, path: String, body: String, name: String = "kestrel"): Map<String, String> =
        ReleasePublisher.headers(key, path, body) + ("Authorization" to "${ApprovalDesk.AUTH_SCHEME} $name")

    private suspend fun ApplicationTestBuilder.ask(path: String, body: String, key: PrivateKey = kestrelKey, name: String = "kestrel") =
        client.post(path) { signed(key, path, body, name).forEach { (k, v) -> header(k, v) }; setBody(body) }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private val trade = """{"ref":"k-1","kind":"trade","title":"Buy 0.05 BTC at market","lines":["About £2,410","Stop loss 5%"],"expiresAtMs":${1_760_000_000_000L + 30 * 60_000}}"""

    /**
     * Meka's answer, written as a device would (the app's [ApprovalRules.prepare], the fingerprint-unlocked key signing
     * its text, then [ApprovalRules.decide]). [signature] replaces the real one for the tampering tests.
     */
    private fun answer(id: String, approve: Boolean, signature: String? = null, device: String = "fold") {
        val a = ApprovalRules.parse(id, ApprovalDesk.scanning(ops)("hh")[id]!!.mapValues { it.value.value })
        val prep = ApprovalRules.prepare(a, id, nowMs, "key-$id", device)
        val signed = ApprovalSignature(foldApprovalKey.publicB64, signature ?: foldApprovalKey.sign(prep.message!!))
        val d = ApprovalRules.decide(a, approve, ApprovalConfirmation.BIOMETRIC, nowMs, "key-$id", device, signed) as ApprovalDecision.Write
        write(device, EntityTypes.APPROVAL, id, d.fields)
    }

    private fun verifies(pub: String, message: String, sig: String): Boolean {
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pub)))
        return java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message.toByteArray()); verify(java.util.Base64.getDecoder().decode(sig)) }
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
        registerKey()
        answer(id, approve = true)
        val done = json(ask(ApprovalDesk.STATUS_PATH, """{"ref":"k-1"}""").bodyAsText())
        assertEquals("approved", done["state"]!!.jsonPrimitive.content)
        assertEquals("biometric", done["confirmedBy"]!!.jsonPrimitive.content)
        assertEquals("key-$id", done["decisionKey"]!!.jsonPrimitive.content)
        assertEquals("Buy 0.05 BTC at market", done["title"]!!.jsonPrimitive.content)

        // Slice 2: the yes carries the Fold's signature, which Kestrel checks itself with the key it pinned.
        val message = done["answerMessage"]!!.jsonPrimitive.content
        val pub = done["answerPublicKey"]!!.jsonPrimitive.content
        assertEquals(foldApprovalKey.publicB64, pub)
        assertEquals("fold", done["answerDevice"]!!.jsonPrimitive.content)
        assertTrue(verifies(pub, message, done["answerSignature"]!!.jsonPrimitive.content))
        // Kestrel rebuilds the text from what it sent and what the status says.
        val shown = ApprovalRules.shownHash("Buy 0.05 BTC at market", listOf("About £2,410", "Stop loss 5%"))
        val expires = 1_760_000_000_000L + 30 * 60_000
        assertEquals(
            listOf("MEKA-APPROVAL1", id, "approved", "trade", "Kestrel", "key-$id", done["decidedAtMs"]!!.jsonPrimitive.content, "$expires", "fold", shown).joinToString("\n"),
            message,
        )
        // The keys route lists the Fold's key for Kestrel to pin.
        val listed = json(ask(ApprovalDesk.KEYS_PATH, "{}").bodyAsText())["keys"]!!.jsonArray.single().jsonObject
        assertEquals("fold", listed["device"]!!.jsonPrimitive.content)
        assertEquals(foldApprovalKey.publicB64, listed["publicKey"]!!.jsonPrimitive.content)
        assertEquals("Fold", listed["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun aYesWithoutAValidSignatureByTheAnsweringDevicesOwnKeyCarriesNoSignature() = testApplication {
        server()
        fun ref(n: Int) = """{"ref":"s-$n","kind":"trade","title":"Trade $n"}"""
        suspend fun status(n: Int) = json(ask(ApprovalDesk.STATUS_PATH, """{"ref":"s-$n"}""").bodyAsText())
        repeat(4) { ask(ApprovalDesk.REQUEST_PATH, ref(it)) }
        // No key registered yet: approved, but nothing Kestrel can check (so it won't trade).
        answer(ApprovalRules.entityId("Kestrel", "s-0"), approve = true)
        assertEquals("approved", status(0)["state"]!!.jsonPrimitive.content)
        assertNull(status(0)["answerSignature"])
        // A key "registered" for the Fold by another device (the server, or the Mac) doesn't count.
        registerKey(writtenBy = "mac")
        assertNull(status(0)["answerSignature"])
        nowMs += 1_000
        registerKey()
        assertTrue(status(0)["answerSignature"] != null)
        // A signature over something else.
        answer(ApprovalRules.entityId("Kestrel", "s-1"), approve = true, signature = foldApprovalKey.sign("MEKA-APPROVAL1\nsomething else"))
        assertNull(status(1)["answerSignature"])
        // Signed with the Fold's key but written by the Mac, claiming to be the Fold: the ops' author is the Mac.
        val id2 = ApprovalRules.entityId("Kestrel", "s-2")
        val a2 = ApprovalRules.parse(id2, ApprovalDesk.scanning(ops)("hh")[id2]!!.mapValues { it.value.value })
        val prep = ApprovalRules.prepare(a2, id2, nowMs, "k", "fold")
        val d = ApprovalRules.decide(a2, true, ApprovalConfirmation.BIOMETRIC, nowMs, "k", "fold", ApprovalSignature(foldApprovalKey.publicB64, foldApprovalKey.sign(prep.message!!))) as ApprovalDecision.Write
        write("mac", EntityTypes.APPROVAL, id2, d.fields)
        assertNull(status(2)["answerSignature"])
        // A decline is never signed.
        answer(ApprovalRules.entityId("Kestrel", "s-3"), approve = false)
        assertEquals("declined", status(3)["state"]!!.jsonPrimitive.content)
        assertNull(status(3)["answerSignature"])
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
