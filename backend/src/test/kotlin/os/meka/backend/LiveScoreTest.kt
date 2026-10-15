package os.meka.backend

import os.meka.backend.integrations.EspnTeamFixtures
import os.meka.backend.integrations.LiveScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Barça match day, slice 2: the live score and the result, read from ESPN's public scoreboard (invented documents). */
class LiveScoreTest {
    private fun utc(s: String) = java.time.Instant.parse(s).toEpochMilli()

    private val schedule = """{"events":[{"id":"7","date":"2026-10-20T19:00Z","timeValid":true,"competitions":[{"date":"2026-10-20T19:00Z",
        "status":{"type":{"name":"STATUS_SCHEDULED","state":"pre"}},
        "competitors":[{"id":"83","homeAway":"home","team":{"id":"83","shortDisplayName":"Barcelona"}},
                       {"id":"2922","homeAway":"away","team":{"id":"2922","shortDisplayName":"Getafe"}}]}]}]}"""

    private fun scoreboard(state: String, name: String, home: String, away: String, clock: String, goals: String = "") =
        """{"events":[
        {"id":"9","date":"2026-10-20T19:00Z","competitions":[{"competitors":[
          {"id":"86","homeAway":"home","score":"3","team":{"id":"86","shortDisplayName":"Real Madrid"}},
          {"id":"1","homeAway":"away","score":"0","team":{"id":"1","shortDisplayName":"Elche"}}],
          "status":{"displayClock":"60'","type":{"name":"STATUS_SECOND_HALF","state":"in"}}}]},
        {"id":"7","date":"2026-10-20T19:00Z","competitions":[{"competitors":[
          {"id":"83","homeAway":"home","score":"$home","team":{"id":"83","shortDisplayName":"Barcelona"}},
          {"id":"2922","homeAway":"away","score":"$away","team":{"id":"2922","shortDisplayName":"Getafe"}}],
          "status":{"displayClock":"$clock","type":{"name":"$name","state":"$state","shortDetail":"$clock"}},
          "details":[$goals]}]}]}"""

    private val goals = """
        {"type":{"text":"Goal"},"scoringPlay":true,"clock":{"displayValue":"23'"},"team":{"id":"83"},
         "athletesInvolved":[{"displayName":"Robert Lewandowski","shortName":"R. Lewandowski"}]},
        {"type":{"text":"Yellow Card"},"scoringPlay":false,"clock":{"displayValue":"30'"},"team":{"id":"2922"},
         "athletesInvolved":[{"displayName":"Somebody"}]},
        {"type":{"text":"Penalty - Scored"},"scoringPlay":true,"penaltyKick":true,"clock":{"displayValue":"40'"},"team":{"id":"2922"},
         "athletesInvolved":[{"displayName":"Borja Mayoral","shortName":"B. Mayoral"}]},
        {"type":{"text":"Goal"},"scoringPlay":true,"clock":{"displayValue":"51'"},"team":{"id":"83"},
         "athletesInvolved":[{"displayName":"Frenkie de Jong","shortName":"F. de Jong"}]}"""

    @Test
    fun aLiveMatchCarriesItsScoreAndGoalsInItsNotes() {
        var board = scoreboard("in", "STATUS_SECOND_HALF", "2", "1", "67'", goals)
        val clock = utc("2026-10-20T20:10:00Z")
        val asked = mutableListOf<String>()
        val feed = EspnTeamFixtures({ url ->
            asked += url
            when {
                url.contains("/esp.1/teams/") -> schedule
                url.contains("/esp.1/scoreboard?dates=20261020") -> board
                else -> error("404")
            }
        }, now = { clock })
        val e = feed.events(clock - 86_400_000, clock + 86_400_000).single()
        assertEquals(
            "Live: Barça 2–1 Getafe · 67'\nGoals: 23' Lewandowski (Barça) · 40' Mayoral pen (Getafe) · 51' de Jong (Barça)",
            e.description,
        )
        // Full time is read once and kept; the schedule dropping the played match keeps it in the window.
        board = scoreboard("post", "STATUS_FULL_TIME", "2", "1", "FT", goals)
        assertTrue(feed.events(clock - 86_400_000, clock + 86_400_000).single().description!!.startsWith("Full time: Barça 2–1 Getafe\n"))
        val reads = asked.count { it.contains("scoreboard") }
        board = "broken"
        val again = feed.events(clock - 86_400_000, clock + 86_400_000).single()
        assertTrue(again.description!!.startsWith("Full time: Barça 2–1 Getafe"))
        assertEquals(reads, asked.count { it.contains("scoreboard") })
    }

    @Test
    fun aScoreboardThatCantBeReadSaysSoOnlyWhileTheMatchIsOn() {
        val before = utc("2026-10-20T18:00:00Z")
        val during = utc("2026-10-20T19:30:00Z")
        var clock = before
        val feed = EspnTeamFixtures({ url -> if (url.contains("/esp.1/teams/")) schedule else error("404") }, now = { clock })
        assertEquals(null, feed.events(before, before + 86_400_000).single().description)
        clock = during
        assertEquals("Live score unavailable", feed.events(before, before + 86_400_000).single().description)
    }

    @Test
    fun scoreLinesForEachState() {
        val name = { s: String -> if (s == "Barcelona") "Barça" else s }
        val g = listOf(LiveScore.Goal("12'", "Dembélé", "PSG"))
        assertEquals(listOf("Live: PSG 1–0 Barça · half time", "Goals: 12' Dembélé (PSG)"),
            LiveScore(LiveScore.State.LIVE, "PSG", "Barcelona", 1, 0, clock = "half time", goals = g).lines(name))
        assertEquals(listOf("Full time: PSG 1–1 Barça · after extra time"),
            LiveScore(LiveScore.State.FULL_TIME, "PSG", "Barcelona", 1, 1, extra = "after extra time").lines(name))
        assertTrue(LiveScore(LiveScore.State.NOT_STARTED, "PSG", "Barcelona", 0, 0).lines(name).isEmpty())
        val parsed = EspnTeamFixtures({ error("no") }).parseScores(scoreboard("in", "STATUS_HALFTIME", "0", "0", "45'+2'"))
        assertEquals(setOf("espn-7"), parsed.keys)
        assertEquals("half time", parsed.getValue("espn-7").clock)
    }
}
