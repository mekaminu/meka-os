package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.backend.integrations.DvlaCarLookup
import os.meka.backend.integrations.DvlaKeys
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.CarFields
import os.meka.core.sync.FieldValue
import os.meka.core.domain.CarCheckRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car on autopilot, slice 2: the DVLA's answer for the stored registration, written for the devices. */
class DvlaTest {
    private val hour = 3_600_000L
    private val bst = LocalCalendar.fixedOffset(hour)
    private val mon = CivilDate.toEpochDay(2026, 10, 12)
    private var now = bst.toEpochMs(mon, 10 * 60)

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    private val ves = """{"registrationNumber":"AB12CDE","taxStatus":"Taxed","taxDueDate":"2026-12-01","motStatus":"Valid",
        "motExpiryDate":"2026-11-02","make":"FORD","colour":"BLUE"}"""
    private val mot = """{"registration":"AB12CDE","make":"FORD","model":"FIESTA","motTests":[
        {"completedDate":"2024-10-30T09:00:00.000Z","testResult":"PASSED","expiryDate":"2025-11-02","defects":[]},
        {"completedDate":"2025-11-03T10:15:00.000Z","testResult":"PASSED","expiryDate":"2026-11-02",
         "defects":[{"text":"Tyre worn close to legal limit","type":"ADVISORY"},{"text":"Brake pipe corroded","type":"ADVISORY"},{"text":"x","type":"MINOR"}]}]}"""

    private val keysJson = """{"ves_api_key":"ves-key-12345","mot_client_id":"client-123","mot_client_secret":"secret-123",
        "mot_api_key":"mot-key-12345","mot_token_url":"https://login.example/oauth2/v2.0/token"}"""

    private inner class FakeHttp : DvlaCarLookup.Http {
        val calls = mutableListOf<String>()
        var vesStatus = 200
        var motStatus = 200
        var tokens = 0
        override fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
            assertEquals(DvlaCarLookup.VES_URL, url)
            assertEquals("ves-key-12345", headers["x-api-key"])
            // Only the registration is sent.
            assertEquals(mapOf("registrationNumber" to "AB12CDE"), Json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content })
            calls += "ves"
            return vesStatus to (if (vesStatus == 200) ves else "{}")
        }
        override fun postForm(url: String, form: Map<String, String>): Pair<Int, String> {
            assertEquals("https://login.example/oauth2/v2.0/token", url)
            assertEquals("client_credentials", form["grant_type"]); assertEquals(DvlaCarLookup.MOT_SCOPE, form["scope"])
            tokens++
            return 200 to """{"access_token":"tok$tokens","expires_in":3599}"""
        }
        override fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
            assertEquals(DvlaCarLookup.MOT_URL + "AB12CDE", url)
            assertEquals("mot-key-12345", headers["X-API-Key"]); assertTrue(headers["Authorization"]!!.startsWith("Bearer tok"))
            calls += "mot"
            return motStatus to (if (motStatus == 200) mot else "{}")
        }
    }

    @Test
    fun theAnswersReadAsTheDvlaWritesThem() {
        assertNull(DvlaKeys.parse("{}"))
        assertNull(DvlaKeys.parse("""{"mot_client_id":"client-123"}"""))
        assertEquals("ves-key-12345", DvlaKeys.parse("""{"ves_api_key":"ves-key-12345"}""")?.vesApiKey)
        assertEquals("https://login.example/oauth2/v2.0/token", DvlaKeys.parse(keysJson)?.mot?.tokenUrl)

        assertEquals(CivilDate.toEpochDay(2026, 11, 2), DvlaCarLookup.day("2026-11-02"))
        assertEquals(CivilDate.toEpochDay(2025, 11, 3), DvlaCarLookup.day("2025-11-03T10:15:00.000Z"))
        assertNull(DvlaCarLookup.day("2026-02-30"))
        assertNull(DvlaCarLookup.day("02/11/2026"))

        val m = DvlaCarLookup.parseMot("AB12CDE", mot)!!
        assertEquals(CivilDate.toEpochDay(2026, 11, 2), m.motDueDay)
        assertEquals(CivilDate.toEpochDay(2025, 11, 3), m.lastTestDay)
        assertEquals(true, m.lastPassed)
        assertEquals(2, m.advisories)
        // A failed retest after the pass doesn't move the due date; a new car's first MOT comes from motTestDueDate.
        val failed = DvlaCarLookup.parseMot("AB12CDE", mot.replace("]}]}", "]},{\"completedDate\":\"2026-10-01T09:00:00Z\",\"testResult\":\"FAILED\",\"defects\":[]}]}"))!!
        assertEquals(CivilDate.toEpochDay(2026, 11, 2), failed.motDueDay)
        assertEquals(false, failed.lastPassed)
        assertEquals(CivilDate.toEpochDay(2027, 3, 1), DvlaCarLookup.parseMot("NEW1", """{"make":"KIA","motTestDueDate":"2027-03-01"}""")!!.motDueDay)

        val v = DvlaCarLookup.parseVes("AB12CDE", ves)!!
        assertEquals("Taxed", v.taxStatus)
        assertEquals(CivilDate.toEpochDay(2026, 12, 1), v.taxDueDay)
        assertNull(DvlaCarLookup.parseVes("AB12CDE", "<html>"))
    }

    @Test
    fun theRegistrationIsLookedUpOnceADayAndOnChangeAndWithoutKeysNothingIsSent() {
        val ops = InMemoryServerOpStore()
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        var keys: DvlaKeys? = null
        val http = FakeHttp()
        val dvla = DvlaCarLookup({ keys }, http)
        val woken = mutableListOf<String>()
        val integrations = Integrations(
            store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            cars = mapOf(dvla.id to dvla), calendar = bst, onChanged = { woken += it },
        )
        fun account() = store.accounts("home").single { it.provider == CarCheckRules.PROVIDER }

        // Meka types the registration on the Fold; it reaches the server.
        val fold = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "f" + System.nanoTime() }
        fun type(reg: String) = ops.transaction {
            fold.commitLocal(EntityTypes.CONTEXT_MODE, "car", mapOf(CarFields.REGISTRATION to FieldValue.Text(reg))).forEach { ops.append(it) }
        }
        type("AB12CDE")
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); fold.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }

        // No keys: the feed is off and nothing is asked.
        integrations.syncAll()
        assertEquals(CarCheckRules.STATUS_OFF, account().status)
        assertEquals(emptyList(), http.calls)

        // The keys land: one look (both halves), written for the devices, which wake.
        keys = DvlaKeys.parse(keysJson)
        integrations.syncAll()
        assertEquals(listOf("ves", "mot"), http.calls)
        assertEquals("ok", account().status)
        assertEquals(listOf("home"), woken)
        pull()
        val check = CarCheckRules.read(fold.entity(EntityTypes.CONTEXT_MODE, CarCheckRules.ENTITY_ID)?.fields)!!
        assertEquals(CivilDate.toEpochDay(2026, 11, 2), check.motDueDay)
        assertEquals(CivilDate.toEpochDay(2026, 12, 1), check.taxDueDay)
        assertEquals("FORD", check.make)
        assertEquals(2, check.advisories)

        // Not again within the day.
        now += 2 * hour
        integrations.syncAll()
        assertEquals(2, http.calls.size)
        // A day on: asked again; nothing changed, so only the checked time moves and nobody is woken.
        now += 24 * hour
        integrations.syncAll()
        assertEquals(4, http.calls.size)
        assertEquals(1, woken.size)
        assertEquals(2, http.tokens) // the MOT token ran out overnight and was asked for again

        // A new registration is looked up at the next pass; the DVLA not knowing it is an answer, not an error.
        type("XY99ZZZ")
        http.vesStatus = 404; http.motStatus = 404
        now += 11 * 60_000L
        // The fake checks the URL, so answer for the new plate.
        val other = DvlaCarLookup({ keys }, object : DvlaCarLookup.Http by http {
            override fun postJson(url: String, headers: Map<String, String>, body: String) = 404 to "{}"
            override fun get(url: String, headers: Map<String, String>) = 404 to "{}"
        })
        val second = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, cars = mapOf(other.id to other), calendar = bst, onChanged = { woken += it })
        second.syncAll()
        pull()
        val gone = CarCheckRules.read(fold.entity(EntityTypes.CONTEXT_MODE, CarCheckRules.ENTITY_ID)?.fields)!!
        assertEquals("XY99ZZZ", gone.registration)
        assertFalse(gone.found)
        assertEquals("ok", account().status)
        assertEquals(2, woken.size)
        assertTrue(fold.conflicts(EntityTypes.CONTEXT_MODE).isEmpty())

        // The DVLA failing is the feed's error, tried again after an hour, not every pass.
        var fails = 0
        val failing = DvlaCarLookup({ keys }, object : DvlaCarLookup.Http by http {
            override fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String> { fails++; return 500 to "{}" }
        })
        val third = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, cars = mapOf(failing.id to failing), calendar = bst)
        now += 25 * hour
        third.syncAll()
        assertEquals("error", account().status)
        now += 10 * 60_000L
        third.syncAll()
        assertEquals(1, fails)
        now += hour
        third.syncAll()
        assertEquals(2, fails)
    }
}
