package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.Shopping
import os.meka.core.domain.ShoppingRules
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A browser's WebCrypto P-256 key: raw r‖s signatures (P1363), as the family page makes them. */
class TestBrowserKey(private val now: () -> Long) {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicB64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    /** (time, nonce, signature) over this exact request. */
    fun sign(path: String, body: String, time: Long = now(), nonce: String = UUID.randomUUID().toString().replace("-", "")): Triple<String, String, String> {
        val message = listOf("MEKA1", "POST", path, time.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").run { initSign(pair.private); update(message.toByteArray()); sign() }
        return Triple(time.toString(), nonce, Base64.getEncoder().encodeToString(sig))
    }
}

/** Jeanette's page (family sharing with Jeanette, slice 2): invite, first open, signed requests, the shared list. */
class FamilyShareTest {
    private var nowMs = 1_791_540_000_000L // Sat 10 Oct 2026, 10:00 London
    private val ops = InMemoryServerOpStore()
    private val invites = InMemoryFamilyInviteStore()
    private val woken = mutableListOf<String>()
    private val family = FamilyShare(invites, ops, FamilyShare.scanning(ops), RequestVerifier { nowMs }, { nowMs }, onWritten = { woken += it })
    private val claimPath = "/family/v1/claim"
    private val json = Json

    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    private fun invite(name: String = "Jeanette"): Pair<String, String> {
        val r = family.invite("hh", """{"name":"$name"}""")
        assertEquals(200, r.status)
        val o = obj(r.body)
        return o.str("id")!! to o.str("token")!!
    }

    private fun claim(token: String, key: TestBrowserKey): FamilyReply {
        val body = """{"token":"$token","publicKey":"${key.publicB64}"}"""
        val (t, n, s) = key.sign(claimPath, body)
        return family.claim(claimPath, body, t, n, s)
    }

    private fun guest(id: String, key: TestBrowserKey, path: String, body: String): FamilyInvite? {
        val (t, n, s) = key.sign(path, body)
        return family.guest(id, "POST", path, body, t, n, s)
    }

    /** Meka's Fold after pulling everything the server holds. */
    private fun fold(): Shopping {
        val store = InMemoryReplicaStore()
        var n = 0
        val replica = Replica("hh", "fold", HlcClock("fold", { nowMs }), store, MekaSchema) { "fold${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return Shopping(replica, { "f${n++}" }, { nowMs }, LondonCalendar)
    }

    @Test
    fun theLinkIsForOneBrowserAndOnlyItsSignedRequestsGetIn() {
        val (id, token) = invite()
        assertEquals(64, token.length)
        val phone = TestBrowserKey { nowMs }
        val first = claim(token, phone)
        assertEquals(200, first.status)
        assertEquals("Jeanette", obj(first.body).str("name"))
        // A retried first open with the same key is fine; another browser with the link alone is refused.
        assertEquals(200, claim(token, phone).status)
        val other = TestBrowserKey { nowMs }
        assertEquals("""{"error":"used"}""", claim(token, other).body)
        assertEquals(403, claim(token, other).status)
        // A token nobody made, or a body signed by another key, gets nothing.
        assertEquals(401, claim("ab".repeat(32), other).status)
        val body = """{"token":"$token","publicKey":"${phone.publicB64}"}"""
        val (t, n, s) = other.sign(claimPath, body)
        assertEquals(401, family.claim(claimPath, body, t, n, s).status)

        val path = "/family/v1/shopping"
        assertNotNull(guest(id, phone, path, "{}"))
        assertNull(guest(id, other, path, "{}")) // the right id, the wrong key
        assertNull(guest("fam" + "0".repeat(20), phone, path, "{}"))
        // A replayed request is refused; so is one signed for another path.
        val (t2, n2, s2) = phone.sign(path, "{}")
        assertNotNull(family.guest(id, "POST", path, "{}", t2, n2, s2))
        assertNull(family.guest(id, "POST", path, "{}", t2, n2, s2))
        val (t3, n3, s3) = phone.sign("/family/v1/shopping/add", "{}")
        assertNull(family.guest(id, "POST", path, "{}", t3, n3, s3))
        assertNotNull(invites.byId(id)!!.lastSeenAtMs)

        // Revoked: her next request is refused at once, and the link can't be opened again.
        assertEquals(200, family.revoke("hh", """{"id":"$id"}""").status)
        assertNull(guest(id, phone, path, "{}"))
        assertEquals("""{"error":"revoked"}""", claim(token, phone).body)
        assertEquals(404, family.revoke("other", """{"id":"$id"}""").status)
    }

    @Test
    fun herAddsAndTicksReachMekasListsMarkedFromJeanette() {
        val (id, token) = invite()
        val phone = TestBrowserKey { nowMs }
        claim(token, phone)
        val jeanette = invites.byId(id)!!
        // Meka already has milk on the list (made on his Mac, pushed to the server).
        var n = 0
        val mac = Replica("hh", "mac", HlcClock("mac", { nowMs }), InMemoryReplicaStore(), MekaSchema) { "mac${n++}" }
        ShoppingRules.add("Milk", emptyMap(), "meka", nowMs) { "milk1" }.writes
            .flatMap { (item, fields) -> mac.commitLocal(EntityTypes.SHOPPING_ITEM, item, fields) }
            .forEach { ops.append(it) }

        nowMs += 60_000
        val added = obj(family.add(jeanette, """{"text":"milk, Bread, fish and chips"}""").body)
        assertEquals(listOf("Milk", "Bread", "fish and chips"), added["toBuy"]!!.jsonArray.map { it.jsonObject.str("title") })
        // Her own things carry no "From Jeanette" on her page.
        assertEquals(listOf(null, null, null), added["toBuy"]!!.jsonArray.map { it.jsonObject.str("meta") })
        assertEquals("3 to buy", added.str("line"))
        assertEquals(listOf("hh"), woken)

        // On Meka's Fold: milk is still one row, hers say who added them.
        var lists = fold().view()
        assertEquals(listOf("Milk", "Bread", "fish and chips"), lists.toBuy.map { it.title })
        assertEquals(listOf(null, "From Jeanette · today", "From Jeanette · today"), lists.toBuy.map { it.meta })
        assertTrue(ops.after("hh", 0, 1000).map { it.op }.filter { it.deviceId == "server" }.all { it.entityType == EntityTypes.SHOPPING_ITEM })

        // She ticks Meka's milk; he sees it under Got. She puts it back; it is back on his list.
        nowMs += 60_000
        val got = obj(family.got(jeanette, """{"id":"milk1"}""").body)
        assertEquals(listOf("Milk"), got["got"]!!.jsonArray.map { it.jsonObject.str("title") })
        assertEquals("Got today", got["got"]!!.jsonArray[0].jsonObject.str("meta"))
        lists = fold().view()
        assertEquals(listOf("Milk"), lists.got.map { it.title })
        nowMs += 60_000
        family.putBack(jeanette, """{"id":"milk1"}""")
        assertEquals(listOf("Milk", "Bread", "fish and chips"), fold().view().toBuy.map { it.title })

        // An id that isn't a shopping item writes nothing.
        val before = ops.size
        family.got(jeanette, """{"id":"some-task"}""")
        assertEquals(before, ops.size)
    }

    @Test
    fun theOwnerSeesInvitesButNeverTheirTokensOrKeys() {
        val (id, token) = invite("  Jeanette ")
        claim(token, TestBrowserKey { nowMs })
        invite("Gran")
        val listed = family.list("hh").body
        assertTrue(token !in listed)
        assertTrue("publicKey" !in listed)
        val rows = obj(listed)["invites"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("jeanette", "gran"), rows.map { it.str("name") }.toSet())
        assertEquals(FamilyInvite.JOINED, rows.single { it.str("id") == id }.str("state"))
        assertEquals(400, family.invite("hh", """{"name":"meka"}""").status)
        assertEquals(400, family.invite("hh", """{"name":"<b>"}""").status)
        assertEquals(400, family.invite("hh", """{}""").status)
        assertEquals(0, family.list("someone-else").let { obj(it.body)["invites"]!!.jsonArray.size })
    }

    @Test
    fun tooMuchAtOnceIsRefusedAndTheLondonDayIsUsed() {
        val (id, token) = invite()
        claim(token, TestBrowserKey { nowMs })
        val jeanette = invites.byId(id)!!
        assertEquals(400, family.add(jeanette, """{"text":"${(1..31).joinToString(",") { "thing$it" }}"}""").status)
        assertEquals(400, family.add(jeanette, """{"text":"${"x".repeat(1_001)}"}""").status)
        assertEquals(400, family.add(jeanette, """not json""").status)
        // 23:30 UTC on a summer evening is already tomorrow in London.
        val june = java.time.ZonedDateTime.of(2026, 6, 1, 23, 30, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(java.time.LocalDate.of(2026, 6, 2).toEpochDay(), LondonCalendar.epochDayOf(june))
        assertEquals(30, LondonCalendar.minuteOfDay(june))
        assertEquals(june, LondonCalendar.toEpochMs(LondonCalendar.epochDayOf(june), 30))
    }
}
