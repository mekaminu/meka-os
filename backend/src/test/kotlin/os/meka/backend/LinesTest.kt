package os.meka.backend

import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TflLineStatus
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LineState
import os.meka.core.domain.LineStatusStore
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.PlacesRules
import os.meka.core.domain.RouteRules
import os.meka.core.domain.WorkHours
import os.meka.core.domain.WorkSchedule
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Places item 4: TfL's status for Thameslink, Great Northern and the Elizabeth line, mirrored for the commute. */
class LinesTest {
    // Friday 9 October 2026, 07:30 BST (06:30Z).
    private var now = 1_791_527_400_000L
    private val bst = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)

    private fun status(sev: Int, isNow: Boolean? = null) = buildString {
        append("""{"${'$'}type":"Tfl.Api.Presentation.Entities.LineStatus, Tfl.Api.Presentation.Entities","id":0,""")
        append(""""statusSeverity":$sev,"statusSeverityDescription":"Whatever TfL says","reason":"Signal failure at <b>X</b>",""")
        append(""""created":"0001-01-01T00:00:00","validityPeriods":[""")
        if (isNow != null) append("""{"fromDate":"2026-10-09T05:00:00Z","toDate":"2026-10-09T23:00:00Z","isNow":$isNow}""")
        append("]}")
    }

    /** The shape of TfL's `Line/{ids}/Status` answer (fields we read), as saved from a real response. */
    private fun sample(vararg lines: Pair<String, List<String>>) = lines.joinToString(",", "[", "]") { (id, statuses) ->
        """{"${'$'}type":"Tfl.Api.Presentation.Entities.Line, Tfl.Api.Presentation.Entities","id":"$id","name":"$id",
           "modeName":"national-rail","disruptions":[],"created":"2026-10-01T00:00:00Z","modified":"2026-10-01T00:00:00Z",
           "lineStatuses":[${statuses.joinToString(",")}],"routeSections":[],"serviceTypes":[]}"""
    }

    private fun allGood() = sample(
        "thameslink" to listOf(status(10)), "great-northern" to listOf(status(10)), "elizabeth" to listOf(status(10)),
    )

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    @Test
    fun readsTheWorstStatusInForceForEachLineOnTheRoute() {
        var asked = ""
        val source = TflLineStatus({ url -> asked = url; allGood() })
        assertEquals(3, source.statuses().size)
        assertEquals("https://api.tfl.gov.uk/Line/thameslink,great-northern,elizabeth/Status", asked)
        assertEquals("TfL · Thameslink, Great Northern, Elizabeth line", source.label)
        assertEquals("lines", source.id)

        val body = sample(
            // Minor delays now and a part closure later tonight: the delays count, not the planned works.
            "thameslink" to listOf(status(9, isNow = true), status(5, isNow = false)),
            "great-northern" to listOf(status(10)),
            // Two in force: the worse one.
            "elizabeth" to listOf(status(9, isNow = true), status(6, isNow = true)),
            // A line nobody asked about is ignored.
            "victoria" to listOf(status(2)),
        )
        assertEquals(
            listOf(LineState("thameslink", 9), LineState("great-northern", 10), LineState("elizabeth", 6)),
            TflLineStatus({ body }).statuses(),
        )
        // A line whose only status is later is running as normal.
        assertEquals(listOf(LineState("elizabeth", 10)), TflLineStatus({ sample("elizabeth" to listOf(status(4, isNow = false))) }).statuses())
        // Unreadable or empty is a fault, never "all good".
        assertFailsWith<Exception> { TflLineStatus({ "<html>down</html>" }).statuses() }
        assertFailsWith<Exception> { TflLineStatus({ "[]" }).statuses() }
        assertFailsWith<Exception> { TflLineStatus({ """{"message":"rate limited"}""" }).statuses() }
    }

    @Test
    fun theStatusReachesDevicesAndWakesThemOnlyWhenALineChanges() {
        var fetches = 0
        var body = allGood()
        val source = TflLineStatus({ fetches++; body })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val woken = mutableListOf<String>()
        val integrations = Integrations(
            store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            lines = mapOf(source.id to source), onChanged = { woken += it },
        )
        integrations.syncAll()
        assertEquals(listOf("TfL · Thameslink, Great Northern, Elizabeth line"), store.accounts("home").map { it.email })
        assertEquals(listOf("home"), woken)

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        fun opCount() = ops.after("home", 0, 10_000).size
        pull()
        val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, bst)
        assertEquals("Thameslink · Elizabeth line · good service", RouteRules.todayLine(LineStatusStore(r).snapshot(), office, now, bst)!!.text)
        assertEquals(2, opCount()) // lines, checked

        // Within the period: not asked again.
        now += 2 * 60_000L
        integrations.syncAll()
        assertEquals(1, fetches)

        // The next poll, nothing changed and still in the same half hour: asked, nothing written, nobody woken.
        now += 5 * 60_000L
        integrations.syncAll()
        assertEquals(2, fetches)
        assertEquals(2, opCount())
        assertEquals(1, woken.size)

        // Thameslink falls over: one op for the lines, the devices woken; Today names the fallback.
        body = sample("thameslink" to listOf(status(6, isNow = true)), "great-northern" to listOf(status(10)), "elizabeth" to listOf(status(10)))
        now += 5 * 60_000L
        integrations.syncAll()
        assertEquals(3, opCount())
        assertEquals(2, woken.size)
        pull()
        assertEquals("Thameslink severe delays · Elizabeth line good service — Great Northern to King's Cross is running",
            RouteRules.todayLine(LineStatusStore(r).snapshot(), office, now, bst)!!.text)
        assertTrue(r.conflicts(EntityTypes.CONTEXT_MODE).isEmpty())

        // Into the next half hour with nothing new: only the checked time moves, and it wakes nobody.
        now += 20 * 60_000L
        integrations.syncAll()
        assertEquals(4, opCount())
        assertEquals(2, woken.size)

        // Overnight it rests: 23:30 BST is not polled.
        val before = fetches
        now = 1_791_585_000_000L // Fri 9 Oct 23:30 BST
        integrations.syncAll()
        assertEquals(before, fetches)

        // A failure keeps the last status and is tried again at the next poll.
        now = 1_791_613_800_000L // Sat 10 Oct 07:30 BST
        body = "<html>down</html>"
        integrations.syncAll()
        assertEquals("error", store.accounts("home").single().status)
        integrations.syncAll()
        assertEquals(before + 2, fetches)
    }
}
