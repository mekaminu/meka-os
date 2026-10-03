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
) : FeedProvider {
    constructor() : this(::httpGet)

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
        }
        // Cup schedules are often empty; only fail the sync when nothing at all could be read.
        if (failures == competitions.size) error("no fixture feed could be read")
        return all.filter { it.startMs < toMs && it.endMs > fromMs }.distinctBy { it.id }
    }

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
            val title = "${barca(home)} v ${barca(away)}" + if (timeKnown) "" else " (kick-off TBC)"
            RemoteEvent(
                id = "espn-$id", title = title, startMs = start, endMs = start + MATCH_MS, allDay = false,
                location = comp?.get("venue")?.jsonObject?.str("fullName"), calendarName = competition,
            )
        }
    }

    private fun barca(name: String) = if (name.equals(teamName, ignoreCase = true)) "Barça" else name

    private fun parseInstant(s: String): Long? = runCatching {
        // ESPN uses "2026-10-04T19:00Z" (no seconds); java.time wants seconds.
        Instant.parse(if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(s)) s.dropLast(1) + ":00Z" else s).toEpochMilli()
    }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Kick-off to final whistle plus a margin, so the planner keeps the evening free. */
        const val MATCH_MS = 2 * 3_600_000L

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
