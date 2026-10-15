package os.meka.backend

import os.meka.backend.integrations.EspnTeamFixtures
import os.meka.backend.integrations.UkTvListings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Barça match day, slice 1: the UK channels for each match, read from a public listings page (invented page below). */
class TvListingsTest {
    private val page = """
        <html><head><style>.x{color:red}</style><script>var a = "20:00";</script></head><body>
        <nav><a href="/">Live Football On TV</a></nav>
        <div class="fixture-date">Sunday 18th October 2026</div>
        <div class="fixture"><div class="fixture__time">15:15</div>
          <div class="fixture__teams">Sevilla v <span>Barcelona</span></div>
          <div class="fixture__competition">LaLiga</div>
          <div class="fixture__channel"><span class="channel-pill">Premier Sports 1</span><span class="channel-pill">LaLigaTV</span></div></div>
        <div class="fixture-date">Tuesday 20th October 2026</div>
        <div class="fixture"><div class="fixture__time">20:00</div>
          <div class="fixture__teams">PSG v Barcelona</div>
          <div class="fixture__competition">UEFA Champions League League Phase</div>
          <div class="fixture__channel">TNT Sports 2TNT Sports 9HBO Max</div></div>
        <div class="fixture-date">Wednesday 25th November 2026</div>
        <div class="fixture"><div class="fixture__time">17:45</div>
          <div class="fixture__teams">Sabah FC v Barcelona</div>
          <div class="fixture__competition">UEFA Champions League League Phase</div>
          <div class="fixture__channel">HBO MaxTNT Sports TBC</div></div>
        <footer><p>Contact us</p><p>Privacy &amp; cookies</p></footer>
        </body></html>
    """.trimIndent()

    private val now = 1_792_022_400_000L // 2026-10-15T00:00Z
    private fun utc(s: String) = java.time.Instant.parse(s).toEpochMilli()

    @Test
    fun readsEachMatchWithItsChannelsAndNeverTheFooter() {
        val tv = UkTvListings({ page }, { now })
        val p = tv.page()!!
        assertEquals(listOf("Sevilla v Barcelona", "PSG v Barcelona", "Sabah FC v Barcelona"), p.listings.map { it.teams })
        assertEquals(listOf("Premier Sports 1", "LaLigaTV"), p.listings[0].channels)
        assertEquals(listOf("TNT Sports 2", "TNT Sports 9", "HBO Max"), p.listings[1].channels)
        assertEquals(listOf("HBO Max", "TNT Sports", "TBC"), p.listings[2].channels)
        assertEquals("UEFA Champions League League Phase", p.listings[1].competition)
        assertEquals(utc("2026-10-18T14:15:00Z"), p.listings[0].kickOffMs) // 15:15 in BST
        assertEquals(java.time.LocalDate.of(2026, 11, 25), p.lastDay)
    }

    @Test
    fun notesSayKickOffInUkTimeAndWhereToWatch() {
        val tv = UkTvListings({ page }, { now })
        val p = tv.page()
        assertEquals("Kick-off 20:00 UK time\nOn UK TV: TNT Sports 2 · TNT Sports 9 · HBO Max", tv.notesFor(utc("2026-10-20T19:00:00Z"), true, p))
        assertEquals("Kick-off 17:45 UK time\nOn UK TV: HBO Max · TNT Sports · channel to be confirmed", tv.notesFor(utc("2026-11-25T17:45:00Z"), true, p))
        // A match on a day the page covers but doesn't list isn't on UK TV; one after its last day isn't listed yet.
        assertEquals("Kick-off 20:00 UK time\nNot on UK TV, per the listings", tv.notesFor(utc("2026-11-01T20:00:00Z"), true, p))
        assertEquals("Kick-off 20:00 UK time\nUK TV not listed yet", tv.notesFor(utc("2026-12-06T20:00:00Z"), true, p))
        assertEquals("Kick-off time not set yet\nUK TV not listed yet", tv.notesFor(utc("2026-12-06T20:00:00Z"), false, null))
        // A match already played (before the page's first day) keeps only its kick-off.
        assertEquals("Kick-off 20:00 UK time", tv.notesFor(utc("2026-10-04T19:00:00Z"), true, p))
    }

    @Test
    fun aPageThatCantBeReadKeepsTheLastGoodOneAndIsReadAtMostEverySixHours() {
        var t = now
        var reads = 0
        var body: String? = page
        val tv = UkTvListings({ reads++; body ?: error("down") }, { t })
        tv.page(); tv.page()
        assertEquals(1, reads)
        t += UkTvListings.PERIOD_MS; body = null
        assertEquals(3, tv.page()!!.listings.size) // kept
        assertEquals(2, reads)
        assertNull(UkTvListings({ error("down") }, { now }).page())
        assertNull(UkTvListings({ "<html>new layout</html>" }, { now }).page())
    }

    @Test
    fun theFixturesFeedWritesTheNotesIntoEachMatch() {
        val espn = """{"events":[{"id":"7","date":"2026-10-20T19:00Z","timeValid":true,"competitions":[{"date":"2026-10-20T19:00Z",
            "status":{"type":{"name":"STATUS_SCHEDULED"}},
            "competitors":[{"homeAway":"home","team":{"shortDisplayName":"PSG"}},{"homeAway":"away","team":{"shortDisplayName":"Barcelona"}}]}]}]}"""
        val tv = UkTvListings({ page }, { now })
        val feed = EspnTeamFixtures({ url -> if (url.contains("/uefa.champions/")) espn else error("404") }, tv = tv)
        val e = feed.events(now, now + 30L * 86_400_000).single()
        assertEquals("PSG v Barça", e.title)
        assertEquals("Kick-off 20:00 UK time\nOn UK TV: TNT Sports 2 · TNT Sports 9 · HBO Max", e.description)
    }

    @Test
    fun splitsRunTogetherChannels() {
        assertEquals(listOf("Amazon Prime Video"), UkTvListings.splitChannels("Amazon Prime Video"))
        assertEquals(listOf("Sky Sports Main Event", "Disney+"), UkTvListings.splitChannels("Sky Sports Main EventDisney+"))
        assertNull(UkTvListings.splitChannels("Contact us"))
        assertNull(UkTvListings.splitChannels("LaLiga"))
    }
}
