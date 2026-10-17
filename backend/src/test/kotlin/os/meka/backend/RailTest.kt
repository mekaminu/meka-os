package os.meka.backend

import os.meka.backend.integrations.DarwinBoard
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.RailKeyRefused
import os.meka.backend.integrations.RailStations
import os.meka.backend.integrations.TflLineStatus
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.Household
import os.meka.core.domain.HouseholdNames
import os.meka.core.domain.LineStatusStore
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.PlacesRules
import os.meka.core.domain.RouteRules
import os.meka.core.domain.TrainBoard
import os.meka.core.domain.TrainDeparture
import os.meka.core.domain.WorkHours
import os.meka.core.domain.WorkSchedule
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live trains (Needs Meka #16, 2026-10-17): National Rail's Live Departure Board through the Rail Data Marketplace.
 * Stations here are public ones picked for the test (rule 13: never his own).
 */
class RailTest {
    // Friday 9 October 2026, 07:30 BST (06:30Z).
    private var now = 1_791_527_400_000L
    private val bst = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val route = "South Rail to London Bridge, then Metro line; or North Rail to Blackfriars"

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private fun service(std: String, etd: String, platform: String? = "3", arrive: Pair<String, String>? = "LBG" to "08:05", cancelled: Boolean = false) =
        """{"std":"$std","etd":"$etd",${platform?.let { "\"platform\":\"$it\"," } ?: ""}"operator":"South Rail","isCancelled":$cancelled,
           "subsequentCallingPoints":[{"callingPoint":[{"locationName":"Somewhere","crs":"ECR","st":"07:50","et":"On time"},
           ${arrive?.let { "{\"locationName\":\"There\",\"crs\":\"${it.first}\",\"st\":\"${it.second}\",\"et\":\"On time\"}" } ?: "{\"crs\":\"ZZZ\",\"st\":\"09:00\"}"}]}]}"""

    private fun board(vararg services: String) =
        """{"generatedAt":"2026-10-09T07:30:00+01:00","locationName":"Brighton","crs":"BTN","filtercrs":"LBG","trainServices":[${services.joinToString(",")}]}"""

    @Test
    fun readsTheBoardTimesPlatformsAndArrival() {
        val read = DarwinBoard.parse(board(
            service("07:42", "On time"),
            service("07:54", "07:59", platform = "10A"),
            service("08:06", "Cancelled", cancelled = true),
            service("08:12", "Delayed", platform = null, arrive = null),
            service("bad", "On time"),
        ), "LBG")!!
        assertEquals(5, read.services)
        assertEquals(
            listOf(
                TrainDeparture(462, 462, platform = "3", arrMin = 485),
                TrainDeparture(474, 479, platform = "10A", arrMin = 485),
                TrainDeparture(486, null, cancelled = true, platform = "3", arrMin = 485),
                TrainDeparture(492, null, delayed = true),
            ),
            read.departures,
        )
        // No trains is an empty board, not a fault; something that isn't a board is null.
        assertEquals(0, DarwinBoard.parse("""{"generatedAt":"x","trainServices":null}""", null)!!.departures.size)
        assertNull(DarwinBoard.parse("<html>gateway</html>", null))
        assertNull(DarwinBoard.parse("""{"message":"hello"}""", null))
        assertEquals(432, DarwinBoard.minute("07:12"))
        assertNull(DarwinBoard.minute("Delayed"))
        assertNull(DarwinBoard.minute("25:00"))
    }

    @Test
    fun findsTheEndpointThatAnswersAndKeepsTheKeyInTheHeader() {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val rail = DarwinBoard({ "k-123456789" to null }) { url, headers ->
            asked += url to headers
            if (url.contains("dep1_1/") && url.contains("GetDepBoardWithDetails")) 200 to board(service("07:42", "On time")) else 404 to "{}"
        }
        assertTrue(rail.configured())
        val read = rail.departures("BTN", "LBG")
        assertEquals(1, read.departures.size)
        assertEquals(3, asked.size) // dep1_2 with details, dep1_2 plain, then dep1_1 with details
        assertEquals("1010-live-departure-board-dep1_1 · GetDepBoardWithDetails", rail.endpoint)
        assertTrue(asked.all { it.second == mapOf("x-apikey" to "k-123456789") && !it.first.contains("k-123456789") })
        assertTrue(asked.last().first.endsWith("/GetDepBoardWithDetails/BTN?numRows=10&filterCrs=LBG&filterType=to&timeOffset=0&timeWindow=120"))
        // The working one is asked first next time.
        rail.departures("BTN", null)
        assertEquals(4, asked.size)
        assertTrue(asked.last().first.contains("dep1_1/") && !asked.last().first.contains("filterCrs"))
        assertFailsWith<IllegalArgumentException> { rail.departures("btn; drop", null) }

        // The secret's own base is tried first.
        val own = mutableListOf<String>()
        DarwinBoard({ "k-123456789" to "https://api1.raildata.org.uk/own-product/LDBWS/api/20220120" }) { url, _ ->
            own += url; 200 to board()
        }.departures("BTN", null)
        assertTrue(own.single().startsWith("https://api1.raildata.org.uk/own-product/LDBWS/api/20220120/GetDepBoardWithDetails/BTN"))

        // Refused everywhere: the key; slow down: a plain fault (tried again later); no key: not configured.
        assertFailsWith<RailKeyRefused> { DarwinBoard({ "k-123456789" to null }) { _, _ -> 401 to "" }.departures("BTN", null) }
        assertFailsWith<IllegalStateException> { DarwinBoard({ "k-123456789" to null }) { _, _ -> 429 to "" }.departures("BTN", null) }
        assertTrue(!DarwinBoard({ null }) { _, _ -> 200 to "" }.configured())
    }

    @Test
    fun theSecretHoldsTheKeyAndMaybeTheBase() {
        assertEquals("abcdefgh123" to null, DarwinBoard.parseSecret("""{"api_key":"abcdefgh123"}"""))
        assertEquals("abcdefgh123" to "https://x.example/p", DarwinBoard.parseSecret("""{"api_key":"abcdefgh123","base_url":"https://x.example/p/"}"""))
        assertEquals("abcdefgh123" to null, DarwinBoard.parseSecret("""{"api_key":"abcdefgh123","base_url":"http://plain.example"}"""))
        assertNull(DarwinBoard.parseSecret("{}"))
        assertNull(DarwinBoard.parseSecret(null))
    }

    @Test
    fun stationsAreFoundByNameAsPeopleTypeThem() {
        assertEquals("BTN", RailStations.crs("Brighton"))
        assertEquals("LBG", RailStations.crs("london bridge"))
        assertEquals("KGX", RailStations.crs("King's Cross"))
        assertEquals("KGX", RailStations.crs("Kings Cross"))
        assertEquals("STP", RailStations.crs("St Pancras"))
        assertEquals("EUS", RailStations.crs("eus"))
        assertNull(RailStations.crs("Oakfield Junction"))
        assertNull(RailStations.crs(""))
        // A name two stations share finds neither; the full name wins over a shortened one.
        val idx = RailStations.index(listOf("Newport (South Wales)" to "NWP", "Newport (Essex)" to "NPT", "Bridge" to "BRG", "London Bridge" to "LBG"))
        assertNull(idx["newport"])
        assertEquals("NWP", idx[RailStations.norm("Newport (South Wales)")])
        assertEquals("BRG", idx["bridge"])
        assertEquals("LBG", idx["london bridge"])
    }

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    private fun profile(ops: InMemoryServerOpStore, fields: Map<String, FieldValue>) {
        val mirror = Replica("home", "mirror", HlcClock("mirror", { now }), InMemoryReplicaStore(), MekaSchema) { "m" + System.nanoTime() }
        ops.transaction { mirror.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, fields).forEach { ops.append(it) } }
    }

    @Test
    fun theBoardReachesTodayWithTheTflStatus() {
        Household.useRoute(route)
        var asked = mutableListOf<String>()
        var answer: () -> Pair<Int, String> = { 200 to board(service("07:42", "On time"), service("07:54", "08:01"), service("08:06", "Cancelled", cancelled = true)) }
        // Slice 4: the fallback's own board (to Blackfriars) answers with its one train.
        val fallbackAnswer = 200 to board(service("07:48", "On time", platform = "1", arrive = "BFR" to "08:40"))
        val rail = DarwinBoard({ "k-123456789" to null }) { url, _ -> asked += url; if (url.contains("filterCrs=BFR") && answer().first == 200) fallbackAnswer else answer() }
        val tfl = TflLineStatus({ """[{"id":"south-rail","lineStatuses":[{"statusSeverity":10}]},{"id":"metro","lineStatuses":[{"statusSeverity":10}]},{"id":"north-rail","lineStatuses":[{"statusSeverity":10}]}]""" })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val woken = mutableListOf<String>()
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            lines = mapOf(tfl.id to tfl), onChanged = { woken += it }, rail = rail)
        profile(ops, mapOf(AboutYouFields.ROUTE to FieldValue.Text(route)))
        integrations.syncAll()
        // A route but no home station: no board asked for, and the report says why.
        assertTrue(asked.isEmpty())
        assertTrue(integrations.trainsReport("home").endsWith("no home station in About you"))
        asked.clear() // the report's probe

        profile(ops, mapOf(AboutYouFields.HOME_STATION to FieldValue.Text("Brighton")))
        Household.set(Household.names.copy(homeStation = "Brighton"))
        now += 5 * 60_000L
        integrations.syncAll()
        assertTrue(asked.first().contains("/BTN?") && asked.first().contains("filterCrs=LBG"))
        // The 08:06 is cancelled, so the fallback line's board is read too, from home to its station.
        assertEquals(2, asked.size)
        assertTrue(asked.last().contains("/BTN?") && asked.last().contains("filterCrs=BFR"))

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        val snap = LineStatusStore(r).snapshot()
        assertEquals(TrainBoard.AM, snap.board.dir)
        assertEquals(TrainBoard.OK, snap.board.state)
        val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, bst)
        val line = RouteRules.todayLine(snap, office, now, bst)!!
        assertEquals("South Rail · 07:42 on time · 07:54 7 min late · 08:06 cancelled — or North Rail to Blackfriars · 07:48 on time", line.text)
        assertEquals(listOf(TrainDeparture(7 * 60 + 48, 7 * 60 + 48, platform = "1", arrMin = 8 * 60 + 40)), snap.board.fallback)
        assertTrue(line.lit)
        assertEquals("Live · checked just now", RouteRules.healthRow(snap.board, now, bst)!!.line)
        assertTrue(RouteRules.askLines(snap, now, bst).last()
            .startsWith("next trains from Brighton to London Bridge (live departures, checked 07:35): 07:42 on time, platform 3, arrives 08:05"))
        assertTrue(woken.isNotEmpty())

        // The probe reads a public terminus, counts only.
        val report = integrations.trainsReport("home")
        assertTrue(report.startsWith("probe OK · 3 services · via 1010-live-departure-board-dep1_2 · GetDepBoardWithDetails · board OK · 3 services · 3 kept · morning · fallback 1 kept"), report)
        assertTrue(asked.last().contains("/EUS?"))

        // A refused key is the board's state; the last departures stay (and age out on the devices).
        answer = { 403 to "" }
        now += 5 * 60_000L
        integrations.syncAll()
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertEquals(TrainBoard.KEY, LineStatusStore(r).snapshot().board.state)
        assertEquals(3, LineStatusStore(r).snapshot().board.departures.size)

        // A home station National Rail doesn't know: Health says to check the name.
        answer = { 200 to board() }
        profile(ops, mapOf(AboutYouFields.HOME_STATION to FieldValue.Text("Oakfield Parkway")))
        now += 5 * 60_000L
        val before = asked.size
        integrations.syncAll()
        assertEquals(before, asked.size)
        val s2 = Replica("home", "mac", HlcClock("mac", { now }), InMemoryReplicaStore(), MekaSchema) { "e" + System.nanoTime() }
        s2.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertEquals(TrainBoard.NO_STATION, LineStatusStore(s2).snapshot().board.state)
    }

    @Test
    fun allGoodOnTheFirstLineReadsNoFallbackBoard() {
        Household.useRoute(route)
        Household.set(Household.names.copy(homeStation = "Brighton"))
        val asked = mutableListOf<String>()
        val rail = DarwinBoard({ "k-123456789" to null }) { url, _ -> asked += url; 200 to board(service("07:42", "On time"), service("07:54", "07:55")) }
        val tfl = TflLineStatus({ """[{"id":"south-rail","lineStatuses":[{"statusSeverity":10}]},{"id":"metro","lineStatuses":[{"statusSeverity":10}]},{"id":"north-rail","lineStatuses":[{"statusSeverity":10}]}]""" })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            lines = mapOf(tfl.id to tfl), onChanged = { }, rail = rail)
        profile(ops, mapOf(AboutYouFields.ROUTE to FieldValue.Text(route), AboutYouFields.HOME_STATION to FieldValue.Text("Brighton")))
        integrations.syncAll()
        assertEquals(1, asked.size)
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        assertTrue(LineStatusStore(r).snapshot().board.fallback.isEmpty())
        assertEquals(2, LineStatusStore(r).snapshot().board.departures.size)
    }
}
