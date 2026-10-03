package os.meka.backend

import os.meka.backend.integrations.EspnTeamFixtures
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.CalendarEvents
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixturesTest {
    private val now = 1_790_985_600_000L // 2026-10-03T00:00Z

    /** Shape of ESPN's team schedule document (fields we read only). */
    private fun schedule(vararg events: String) = """{"team":{"id":"83"},"events":[${events.joinToString(",")}]}"""
    private fun match(id: String, date: String, home: String, away: String, status: String = "STATUS_SCHEDULED", timeValid: Boolean = true) = """
        {"id":"$id","date":"$date","timeValid":$timeValid,"competitions":[{"date":"$date",
          "venue":{"fullName":"Estadi Olímpic Lluís Companys"},
          "status":{"type":{"name":"$status"}},
          "competitors":[{"homeAway":"home","team":{"displayName":"$home","shortDisplayName":"$home"}},
                         {"homeAway":"away","team":{"displayName":"$away","shortDisplayName":"$away"}}]}]}"""

    @Test
    fun parsesFixturesAcrossCompetitionsAndSkipsPostponed() {
        val pages = mapOf(
            "esp.1" to schedule(
                match("1", "2026-10-04T19:00Z", "Barcelona", "Real Madrid"),
                match("2", "2026-10-18T14:00Z", "Sevilla", "Barcelona", timeValid = false),
                match("3", "2026-10-25T19:00Z", "Barcelona", "Girona", status = "STATUS_POSTPONED"),
            ),
            "uefa.champions" to schedule(match("9", "2026-10-07T19:00Z", "Barcelona", "Inter Milan")),
        )
        val feed = EspnTeamFixtures({ url -> pages.entries.firstOrNull { url.contains("/${it.key}/") }?.value ?: error("404") })
        val events = feed.events(now, now + 30L * 86_400_000).sortedBy { it.startMs }
        assertEquals(listOf("Barça v Real Madrid", "Barça v Inter Milan", "Sevilla v Barça (kick-off TBC)"), events.map { it.title })
        assertEquals(listOf("LaLiga", "Champions League", "LaLiga"), events.map { it.calendarName })
        assertEquals(1_791_140_400_000L, events[0].startMs) // 2026-10-04T19:00Z
        assertEquals(EspnTeamFixtures.MATCH_MS, events[0].endMs - events[0].startMs)
    }

    @Test
    fun everyHouseholdFollowsTheFeedAndItsMatchesReachDevices() {
        val feed = EspnTeamFixtures({ url ->
            if (url.contains("/esp.1/")) schedule(match("1", "2026-10-04T19:00Z", "Barcelona", "Real Madrid")) else error("404")
        })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val noCipher = object : TokenCipher {
            override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
            override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
        }
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, feeds = mapOf(feed.id to feed))
        integrations.syncAll(); integrations.syncAll()
        assertEquals(listOf("FC Barcelona"), store.accounts("home").map { it.email }) // added once, not twice

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        r.applyRemoteBatch(ops.after("home", 0, 1000).map { it.op })
        val e = CalendarEvents(r).all().single()
        assertEquals("Barça v Real Madrid", e.title)
        assertEquals("fixtures", e.provider)
        assertTrue(e.location!!.startsWith("Estadi"))
    }
}
