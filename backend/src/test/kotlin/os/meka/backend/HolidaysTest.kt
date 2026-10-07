package os.meka.backend

import os.meka.backend.integrations.GovUkBankHolidays
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.BankHolidayStore
import os.meka.core.domain.CivilDate
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidaysTest {
    private var now = 1_791_270_000_000L // 2026-10-06T07:00Z

    /** Shape of https://www.gov.uk/bank-holidays.json (fields we read only). */
    private fun json(vararg ew: Pair<String, String>) = """
        {"england-and-wales":{"division":"england-and-wales","events":[
          ${ew.joinToString(",") { (d, t) -> """{"title":"$t","date":"$d","notes":"","bunting":true}""" }}
        ]},
        "scotland":{"division":"scotland","events":[{"title":"St Andrew’s Day","date":"2026-11-30","notes":"","bunting":true}]}}"""

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    @Test
    fun parsesEnglandAndWalesOnly() {
        val list = GovUkBankHolidays({ url -> assertEquals(GovUkBankHolidays.URL, url); json("2026-12-25" to "Christmas Day", "2026-02-31" to "Bad", "2026-12-28" to "Boxing Day") }).holidays()
        assertEquals(listOf("Christmas Day", "Boxing Day"), list.map { it.title })
        assertEquals(CivilDate.toEpochDay(2026, 12, 25), list.first().epochDay)
        assertFailsWith<Exception> { GovUkBankHolidays({ """{"scotland":{"events":[]}}""" }).holidays() }
        assertFailsWith<Exception> { GovUkBankHolidays({ json() }).holidays() } // an empty list is a fault, not "no holidays"
    }

    @Test
    fun theListReachesDevicesOnceAWeekAndOnlyWhenItChanged() {
        var fetches = 0
        var body = json("2025-01-01" to "New Year’s Day", "2026-12-25" to "Christmas Day", "2024-12-25" to "Christmas Day")
        val source = GovUkBankHolidays({ fetches++; body })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val woken = mutableListOf<String>()
        val integrations = Integrations(
            store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            holidays = mapOf(source.id to source), onChanged = { woken += it },
        )
        integrations.syncAll()
        assertEquals(listOf("GOV.UK · England and Wales"), store.accounts("home").map { it.email })
        assertEquals(listOf("home"), woken)

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        pull()
        val cal = BankHolidayStore(r).calendar()
        assertEquals("Christmas Day", cal.title(CivilDate.toEpochDay(2026, 12, 25)))
        assertEquals("New Year’s Day", cal.title(CivilDate.toEpochDay(2025, 1, 1))) // last year kept
        assertNull(cal.title(CivilDate.toEpochDay(2024, 12, 25))) // older ones dropped
        val opsAfterFirst = ops.after("home", 0, 10_000).size

        // Within the week: not fetched again.
        now += 3 * 24 * 3_600_000L
        integrations.syncAll()
        assertEquals(1, fetches)

        // A week on, unchanged: fetched, nothing written, nobody woken.
        now += 5 * 24 * 3_600_000L
        integrations.syncAll()
        assertEquals(2, fetches)
        assertEquals(opsAfterFirst, ops.after("home", 0, 10_000).size)
        assertEquals(1, woken.size)

        // A week on, a new day added: one op, chained on the last (no conflict on the devices), and a wake.
        body = json("2026-12-25" to "Christmas Day", "2027-01-01" to "New Year’s Day", "2025-01-01" to "New Year’s Day")
        now += 8 * 24 * 3_600_000L
        integrations.syncAll()
        assertEquals(opsAfterFirst + 1, ops.after("home", 0, 10_000).size)
        pull()
        assertTrue(BankHolidayStore(r).calendar().isHoliday(CivilDate.toEpochDay(2027, 1, 1)))
        assertTrue(r.conflicts(os.meka.core.domain.EntityTypes.CONTEXT_MODE).isEmpty())
        assertEquals(2, woken.size)

        // The source failing leaves the list as it was and is retried at the next poll.
        body = "<html>down</html>"
        now += 8 * 24 * 3_600_000L
        integrations.syncAll()
        assertEquals("error", store.accounts("home").single().status)
        assertTrue(BankHolidayStore(r).calendar().isHoliday(CivilDate.toEpochDay(2027, 1, 1)))
        val before = fetches
        integrations.syncAll()
        assertEquals(before + 1, fetches)
    }
}
