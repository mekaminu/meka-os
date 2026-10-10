package os.meka.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.sync.InMemoryServerOpStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The family page's routes (family sharing with Jeanette, slice 2), end to end through Ktor. */
class FamilyRoutesTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val ops = InMemoryServerOpStore()
    private val verifier = RequestVerifier()
    private val family = FamilyShare(InMemoryFamilyInviteStore(), ops, FamilyShare.scanning(ops), verifier)

    private fun field(body: String, k: String) = Json.parseToJsonElement(body).jsonObject[k]!!.jsonPrimitive.content

    @Test
    fun thePageIsServedWithAStrictPolicyAndNoData() = testApplication {
        application { mekaSync(ops, devices, verifier = verifier, family = family) }
        val page = client.get("/family")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertTrue(page.bodyAsText().contains("/family/app.js"))
        assertEquals(HttpStatusCode.OK, client.get("/family/app.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/family/app.css").status)
    }

    @Test
    fun mekaInvitesHerBrowserJoinsAndHerAddReachesTheList() = testApplication {
        application { mekaSync(ops, devices, verifier = verifier, family = family) }
        // Meka, from his Fold: make the invite.
        val create = "/v1/family/invites/create"
        val made = client.post(create) { with(foldKey) { signed(foldSecret, create, """{"name":"Jeanette"}""") } }
        assertEquals(HttpStatusCode.OK, made.status)
        val text = made.bodyAsText()
        val id = field(text, "id")
        val token = field(text, "token")
        assertEquals("/family#$token", field(text, "path"))
        // Without the Fold's signature nothing is made.
        assertEquals(HttpStatusCode.Unauthorized, client.post(create) { setBody("""{"name":"Jeanette"}""") }.status)

        // Her browser: first open.
        val phone = TestBrowserKey(System::currentTimeMillis)
        val claimBody = """{"token":"$token","publicKey":"${phone.publicB64}"}"""
        val (t, n, s) = phone.sign("/family/v1/claim", claimBody)
        val joined = client.post("/family/v1/claim") {
            header("X-Meka-Time", t); header("X-Meka-Nonce", n); header("X-Meka-Signature", s); setBody(claimBody)
        }
        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals("Jeanette", field(joined.bodyAsText(), "name"))

        // Her add, signed with her key.
        val add = "/family/v1/shopping/add"
        val addBody = """{"text":"bread, eggs"}"""
        val (t2, n2, s2) = phone.sign(add, addBody)
        val after = client.post(add) {
            header("Authorization", "Guest $id"); header("X-Meka-Time", t2); header("X-Meka-Nonce", n2); header("X-Meka-Signature", s2)
            setBody(addBody)
        }
        assertEquals(HttpStatusCode.OK, after.status)
        assertEquals("no-store", after.headers["Cache-Control"])
        val rows = Json.parseToJsonElement(after.bodyAsText()).jsonObject["toBuy"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
        assertEquals(listOf("bread", "eggs"), rows)
        assertEquals(8, ops.size) // two items × title, got, addedAtMs, by

        // Unsigned, or with only the invite id, nothing.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/family/v1/shopping") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/family/v1/shopping") { header("Authorization", "Guest $id"); setBody("{}") }.status)

        // Meka lists and revokes; her next request is refused.
        val list = "/v1/family/invites/list"
        assertTrue(client.post(list) { with(foldKey) { signed(foldSecret, list, "{}") } }.bodyAsText().contains("\"joined\""))
        val revoke = "/v1/family/invites/revoke"
        assertEquals(HttpStatusCode.OK, client.post(revoke) { with(foldKey) { signed(foldSecret, revoke, """{"id":"$id"}""") } }.status)
        val read = "/family/v1/shopping"
        val (t3, n3, s3) = phone.sign(read, "{}")
        val refused = client.post(read) {
            header("Authorization", "Guest $id"); header("X-Meka-Time", t3); header("X-Meka-Nonce", n3); header("X-Meka-Signature", s3)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
    }

    @Test
    fun withoutTheFamilyPageTheRoutesAreNotThere() = testApplication {
        application { mekaSync(ops, devices) }
        assertEquals(HttpStatusCode.NotFound, client.get("/family").status)
    }
}
