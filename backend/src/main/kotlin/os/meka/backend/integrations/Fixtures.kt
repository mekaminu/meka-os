package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * A public feed mirrored like a calendar, without any sign-in (ADR-008 SportsProvider/NewsProvider family).
 * Each household follows a feed through an account row whose [label] is shown in the app.
 */
interface FeedProvider {
    val id: String
    val label: String
    fun events(fromMs: Long, toMs: Long): List<RemoteEvent>
}

/**
 * FC Barcelona fixtures from ESPN's public site API (no key). Unofficial, so it is isolated behind [FeedProvider]:
 * a format change shows up as a sync error on the Calendars screen, never as wrong data, and the provider can be
 * swapped (e.g. for football-data.org) without touching anything else.
 */
class EspnTeamFixtures internal constructor(
    private val fetch: (String) -> String,
    private val teamId: String = "83", // FC Barcelona
    private val teamName: String = "Barcelona",
    /** Barça match day, slice 1: kick-off in UK time and the UK channels, written into each match's notes. */
    private val tv: UkTvListings? = null,
    /**
     * Barça match day, slice 2: the clock for live scores. Null (tests of the schedule alone) reads no live scores; the
     * server passes the wall clock.
     */
    private val now: (() -> Long)? = null,
) : FeedProvider {
    constructor() : this(::httpGet)
    constructor(tv: UkTvListings) : this(::httpGet, tv = tv, now = System::currentTimeMillis)

    /** Every match seen in a schedule (id → league slug and kick-off), so a played match is still followed. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Pair<String, RemoteEvent>>()
    /** Final scores, read once and kept (a played match's score doesn't change). */
    private val finals = java.util.concurrent.ConcurrentHashMap<String, LiveScore>()

    override val id = "fixtures"
    override val label = "FC Barcelona"

    /** League slug → how the competition is shown. A team's schedule is published per competition. */
    private val competitions = linkedMapOf(
        "esp.1" to "LaLiga",
        "uefa.champions" to "Champions League",
        "esp.copa_del_rey" to "Copa del Rey",
        "esp.super_cup" to "Supercopa",
    )

    override fun events(fromMs: Long, toMs: Long): List<RemoteEvent> {
        var failures = 0
        val all = competitions.flatMap { (league, name) ->
            runCatching { parse(fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/teams/$teamId/schedule?fixture=true"), name) }
                .getOrElse { failures++; emptyList() }
                .onEach { seen[it.id] = league to it }
        }
        // Cup schedules are often empty; only fail the sync when nothing at all could be read.
        if (failures == competitions.size) error("no fixture feed could be read")
        // A match the schedule has dropped since kick-off is still shown (with its score) for the window it overlaps.
        val clock = now?.invoke()
        val kept = if (clock == null) emptyList() else seen.values.map { it.second }
            .filter { s -> all.none { it.id == s.id } && s.startMs <= clock && s.startMs > clock - LIVE_BACK_MS }
        val shown = (all + kept).filter { it.startMs < toMs && it.endMs > fromMs }.distinctBy { it.id }
        val scores = if (clock == null) emptyMap() else liveScores(shown, clock)
        // One read of the listings for all of them; a page that can't be read says "UK TV not listed yet".
        val page = tv?.let { listings -> runCatching { listings.page() }.getOrNull() }
        return shown.map { e ->
            val base = tv?.notesFor(e.startMs, !e.title.endsWith(TBC), page)
            val live = when {
                clock == null || e.startMs > clock || e.startMs <= clock - LIVE_BACK_MS -> null
                e.id in scores -> scores.getValue(e.id).lines(::barca)
                clock < e.startMs + LIVE_MS -> listOf(SCORE_UNAVAILABLE)
                else -> null
            }
            val notes = (live.orEmpty() + listOfNotNull(base)).joinToString("\n").ifEmpty { null }
            if (notes == null) e else e.copy(description = notes)
        }
    }

    /**
     * Barça match day, slice 2: the score of each match from 10 minutes before kick-off to [LIVE_BACK_MS] after it, read
     * from ESPN's public scoreboard for that competition and day (no key). A final score is read once and kept; a
     * scoreboard that can't be read leaves that match out (the notes say "Live score unavailable" while it's on).
     */
    internal fun liveScores(matches: List<RemoteEvent>, clock: Long): Map<String, LiveScore> {
        val out = HashMap<String, LiveScore>()
        for (m in matches) {
            if (m.startMs > clock + PRE_MS || m.startMs <= clock - LIVE_BACK_MS || m.title.endsWith(TBC)) continue
            val kept = finals[m.id]
            if (kept != null) { out[m.id] = kept; continue }
            val league = seen[m.id]?.first ?: continue
            val day = DAY.format(Instant.ofEpochMilli(m.startMs).atZone(java.time.ZoneOffset.UTC))
            val score = runCatching {
                parseScores(fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/scoreboard?dates=$day"))[m.id]
            }.getOrNull() ?: continue
            if (score.state == LiveScore.State.FULL_TIME) finals[m.id] = score
            if (score.state != LiveScore.State.NOT_STARTED) out[m.id] = score
        }
        return out
    }

    /** Scores in a scoreboard document, by our event id ("espn-…"), for the matches Barça play in. */
    internal fun parseScores(body: String): Map<String, LiveScore> {
        val root = Json.parseToJsonElement(body).jsonObject
        val out = HashMap<String, LiveScore>()
        for (el in root["events"]?.jsonArray.orEmpty()) {
            val e = el.jsonObject
            val id = e.str("id") ?: continue
            val comp = e["competitions"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            val sides = comp["competitors"]?.jsonArray.orEmpty().map { it.jsonObject }
            if (sides.none { it.str("id") == teamId || it["team"]?.jsonObject?.str("id") == teamId }) continue
            val type = comp["status"]?.jsonObject?.get("type")?.jsonObject ?: e["status"]?.jsonObject?.get("type")?.jsonObject
            val name = type?.str("name").orEmpty()
            val state = when (type?.str("state")) {
                "in" -> LiveScore.State.LIVE
                "post" -> LiveScore.State.FULL_TIME
                else -> LiveScore.State.NOT_STARTED
            }
            fun side(where: String) = sides.firstOrNull { it.str("homeAway") == where }
            val home = side("home") ?: continue
            val away = side("away") ?: continue
            fun teamName(s: JsonObject) = s["team"]?.jsonObject?.let { it.str("shortDisplayName") ?: it.str("displayName") }
            fun goals(s: JsonObject): Int? = when (val sc = s["score"]) {
                is JsonPrimitive -> sc.contentOrNull?.toIntOrNull()
                is JsonObject -> sc.str("displayValue")?.toIntOrNull() ?: sc.str("value")?.toDoubleOrNull()?.toInt()
                else -> null
            }
            val homeName = teamName(home) ?: continue
            val awayName = teamName(away) ?: continue
            val homeId = home.str("id") ?: home["team"]?.jsonObject?.str("id")
            val awayId = away.str("id") ?: away["team"]?.jsonObject?.str("id")
            val clockText = when {
                name == "STATUS_HALFTIME" -> "half time"
                else -> comp["status"]?.jsonObject?.str("displayClock")?.takeIf { it.isNotBlank() && it != "0'" }
                    ?: type?.str("shortDetail")
            }
            val scorers = comp["details"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { d ->
                val scoring = (d["scoringPlay"] as? JsonPrimitive)?.booleanOrNull == true
                val shootout = (d["shootout"] as? JsonPrimitive)?.booleanOrNull == true
                if (!scoring || shootout) return@mapNotNull null
                val teamOf = d["team"]?.jsonObject?.str("id")
                val who = d["athletesInvolved"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.let { a -> a.str("shortName")?.let(::surname) ?: a.str("displayName") } ?: "Goal"
                val og = (d["ownGoal"] as? JsonPrimitive)?.booleanOrNull == true
                val pen = (d["penaltyKick"] as? JsonPrimitive)?.booleanOrNull == true
                val minute = d["clock"]?.jsonObject?.str("displayValue").orEmpty()
                LiveScore.Goal(
                    minute = minute, who = who + (if (og) " og" else "") + (if (pen) " pen" else ""),
                    team = when (teamOf) { homeId -> homeName; awayId -> awayName; else -> null },
                )
            }
            out["espn-$id"] = LiveScore(
                state = state, home = homeName, away = awayName,
                homeGoals = goals(home) ?: if (state == LiveScore.State.NOT_STARTED) 0 else continue,
                awayGoals = goals(away) ?: if (state == LiveScore.State.NOT_STARTED) 0 else continue,
                clock = clockText.takeIf { state == LiveScore.State.LIVE },
                extra = when (name) { "STATUS_FINAL_AET" -> "after extra time"; "STATUS_FINAL_PEN" -> "after penalties"; else -> null },
                goals = scorers,
            )
        }
        return out
    }

    /** "R. Lewandowski" → "Lewandowski", "F. de Jong" → "de Jong"; a name with no initial stays as it is. */
    private fun surname(short: String): String = short.trim().let { if (Regex("^\\p{L}{1,2}\\. ").containsMatchIn(it)) it.substringAfter(". ").trim() else it }

    internal fun parse(body: String, competition: String): List<RemoteEvent> {
        val root = Json.parseToJsonElement(body).jsonObject
        return root["events"]?.jsonArray.orEmpty().mapNotNull { el ->
            val e = el.jsonObject
            val id = e.str("id") ?: return@mapNotNull null
            val comp = e["competitions"]?.jsonArray?.firstOrNull()?.jsonObject
            val status = comp?.get("status")?.jsonObject?.get("type")?.jsonObject?.str("name").orEmpty()
            if (status.contains("POSTPONED") || status.contains("CANCELED") || status.contains("CANCELLED")) return@mapNotNull null
            val start = (comp?.str("date") ?: e.str("date"))?.let { parseInstant(it) } ?: return@mapNotNull null
            val sides = comp?.get("competitors")?.jsonArray.orEmpty().map { it.jsonObject }
            fun side(where: String) = sides.firstOrNull { it.str("homeAway") == where }?.get("team")?.jsonObject
                ?.let { it.str("shortDisplayName") ?: it.str("displayName") }
            val home = side("home") ?: return@mapNotNull null
            val away = side("away") ?: return@mapNotNull null
            val timeKnown = e["timeValid"]?.jsonPrimitive?.booleanOrNull ?: true
            val title = "${barca(home)} v ${barca(away)}" + if (timeKnown) "" else TBC
            RemoteEvent(
                id = "espn-$id", title = title, startMs = start, endMs = start + MATCH_MS, allDay = false,
                location = comp?.get("venue")?.jsonObject?.str("fullName"), calendarName = competition,
            )
        }
    }

    internal fun barca(name: String) = if (name.equals(teamName, ignoreCase = true)) "Barça" else name

    private fun parseInstant(s: String): Long? = runCatching {
        // ESPN uses "2026-10-04T19:00Z" (no seconds); java.time wants seconds.
        Instant.parse(if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(s)) s.dropLast(1) + ":00Z" else s).toEpochMilli()
    }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Kick-off to final whistle plus a margin, so the planner keeps the evening free. */
        const val MATCH_MS = 2 * 3_600_000L
        /** A match is followed for its score from this long before kick-off… */
        const val PRE_MS = 10 * 60_000L
        /** …to this long after it (the full-time score stays in the notes through the day's window). */
        const val LIVE_BACK_MS = 24 * 3_600_000L
        /** While a match should be on, a scoreboard that can't be read says so. */
        const val LIVE_MS = 150 * 60_000L
        const val SCORE_UNAVAILABLE = "Live score unavailable"
        private val DAY = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        /** The title's ending when the kick-off time isn't set yet. */
        const val TBC = " (kick-off TBC)"

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        fun httpGet(url: String): String {
            val resp = client.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(resp.statusCode() == 200) { "feed HTTP ${resp.statusCode()}" }
            return resp.body()
        }
    }
}

/**
 * Barça match day, slice 2: one match's score from the scoreboard. Written into the match's notes as the lines the apps
 * read back (core `BarcaMatchRules.score`): "Live: Barça 2–1 Getafe · 67'" or "Full time: Barça 2–1 Getafe", then
 * "Goals: 23' Lewandowski (Barça) · 40' Mayoral (Getafe)".
 */
data class LiveScore(
    val state: State, val home: String, val away: String, val homeGoals: Int, val awayGoals: Int,
    val clock: String? = null, val extra: String? = null, val goals: List<Goal> = emptyList(),
) {
    enum class State { NOT_STARTED, LIVE, FULL_TIME }
    data class Goal(val minute: String, val who: String, val team: String?)

    fun lines(name: (String) -> String): List<String> {
        val score = "${name(home)} $homeGoals–$awayGoals ${name(away)}"
        val first = when (state) {
            State.LIVE -> "Live: $score" + (clock?.let { " · $it" } ?: "")
            State.FULL_TIME -> "Full time: $score" + (extra?.let { " · $it" } ?: "")
            State.NOT_STARTED -> return emptyList()
        }
        val goalLine = goals.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Goals: ") { g ->
            listOf(g.minute, g.who).filter { it.isNotBlank() }.joinToString(" ") + (g.team?.let { " (${name(it)})" } ?: "")
        }
        return listOfNotNull(first, goalLine)
    }
}
