package os.meka.backend.integrations

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Barça match day, slice 1: which UK channels show each Barça match, read from a public listings page
 * (live-footballontv.com's Barcelona page; no key, nothing about the household is sent — a plain GET). Unofficial, so
 * it is isolated here: a page it can't read leaves the fixtures as they were, with "UK TV not listed yet", and never
 * says a match isn't on TV unless the page clearly read and lists later dates.
 *
 * The page is a run of date headings ("Tuesday 20th October 2026"), then per match a kick-off time ("20:00"), the
 * teams ("PSG v Barcelona"), the competition and one element per channel ("TNT Sports 2", "HBO Max"). The HTML is
 * turned into lines (block tags break, inline tags don't) and walked; a channel line must start like a known UK
 * broadcaster, so a page footer is never taken for a channel.
 */
class UkTvListings internal constructor(
    private val fetch: (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val url: String = BARCELONA_URL,
) {
    constructor() : this(EspnTeamFixtures::httpGet)

    data class Listing(val kickOffMs: Long, val teams: String, val competition: String, val channels: List<String>)

    /**
     * The listings read, and the days the page covers: a match between them that isn't listed isn't on UK TV; one before
     * [firstDay] (played, so gone from the page) says nothing about TV.
     */
    data class Page(val listings: List<Listing>, val lastDay: LocalDate?, val firstDay: LocalDate? = null)

    @Volatile private var cached: Pair<Long, Page>? = null

    /** The page, read at most every [PERIOD_MS]; a failed read keeps the last good one, else null. */
    fun page(): Page? {
        val c = cached
        if (c != null && now() - c.first < PERIOD_MS) return c.second
        val fresh = runCatching { parse(fetch(url)) }.getOrNull()?.takeIf { it.listings.isNotEmpty() }
        if (fresh != null) { cached = now() to fresh; return fresh }
        return c?.second
    }

    /**
     * The lines a Barça fixture's notes start with: "Kick-off 20:00 UK time", then "On UK TV: TNT Sports 2 · HBO Max",
     * "Not on UK TV, per the listings" or "UK TV not listed yet".
     */
    fun notesFor(startMs: Long, timeKnown: Boolean, page: Page?): String {
        val kickOff = if (timeKnown) "Kick-off ${TIME.format(Instant.ofEpochMilli(startMs).atZone(UK))} UK time" else "Kick-off time not set yet"
        return listOfNotNull(kickOff, tvLine(startMs, page)).joinToString("\n")
    }

    internal fun tvLine(startMs: Long, page: Page?): String? {
        if (page == null) return NOT_LISTED_YET
        val day = Instant.ofEpochMilli(startMs).atZone(UK).toLocalDate()
        val match = page.listings
            .filter { Instant.ofEpochMilli(it.kickOffMs).atZone(UK).toLocalDate() == day }
            .minByOrNull { kotlin.math.abs(it.kickOffMs - startMs) }
        if (match != null) {
            val tbc = match.channels.any { it.equals("TBC", true) }
            val channels = match.channels.filterNot { it.equals("TBC", true) }.distinct()
            return when {
                channels.isEmpty() -> "UK TV: channel to be confirmed"
                tbc -> "$ON_TV " + channels.joinToString(" · ") + " · channel to be confirmed"
                else -> "$ON_TV " + channels.joinToString(" · ")
            }
        }
        val first = page.firstDay
        if (first != null && day.isBefore(first)) return null
        val last = page.lastDay
        return if (last != null && day.isBefore(last)) NOT_ON_TV else NOT_LISTED_YET
    }

    internal fun parse(html: String): Page {
        val lines = lines(html)
        val out = mutableListOf<Listing>()
        var day: LocalDate? = null
        var lastDay: LocalDate? = null
        var firstDay: LocalDate? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val date = dateOf(line)
            if (date != null) { day = date; lastDay = date; if (firstDay == null) firstDay = date; i++; continue }
            val time = TIME_LINE.matchEntire(line)
            val d = day
            if (time != null && d != null && i + 2 < lines.size && VERSUS.containsMatchIn(lines[i + 1])) {
                val at = ZonedDateTime.of(d, LocalTime.of(time.groupValues[1].toInt(), time.groupValues[2].toInt()), UK).toInstant().toEpochMilli()
                val teams = lines[i + 1]
                var j = i + 2
                val competition = if (isChannel(lines[j])) "" else lines[j++]
                val channels = mutableListOf<String>()
                while (j < lines.size && channels.size < MAX_CHANNELS) {
                    val c = splitChannels(lines[j]) ?: break
                    channels += c; j++
                }
                out += Listing(at, teams, competition, channels)
                i = j
                continue
            }
            i++
        }
        return Page(out, lastDay, firstDay)
    }

    companion object {
        const val BARCELONA_URL = "https://www.live-footballontv.com/barcelona-on-tv.html"
        const val PERIOD_MS = 6 * 3_600_000L
        const val ON_TV = "On UK TV:"
        const val NOT_ON_TV = "Not on UK TV, per the listings"
        const val NOT_LISTED_YET = "UK TV not listed yet"
        private const val MAX_CHANNELS = 6
        private val UK: ZoneId = ZoneId.of("Europe/London")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val TIME_LINE = Regex("""^(\d{1,2}):(\d{2})$""")
        private val VERSUS = Regex("""\s+v\s+""")
        private val DATE_LINE = Regex(
            """^(?:Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day\s+(\d{1,2})(?:st|nd|rd|th)?\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{4})$""",
            RegexOption.IGNORE_CASE,
        )
        private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

        /** UK broadcasters and streams a channel line starts with (longest first, so "TNT Sports 2TNT Sports 9" splits). */
        private val BROADCASTERS = listOf(
            "Amazon Prime Video", "Premier Sports Player", "Premier Sports", "TNT Sports Box Office", "TNT Sports", "Sky Sports",
            "Sky Showcase", "LaLigaTV", "LaLiga TV", "Disney+", "HBO Max", "discovery+", "Discovery+", "Prime Video", "DAZN",
            "BBC One", "BBC Two", "BBC iPlayer", "ITV1", "ITV4", "ITVX", "Channel 4", "Channel 5", "S4C", "Viaplay", "YouTube", "TBC",
        ).sortedByDescending { it.length }

        private val BLOCK = Regex("""(?is)<\s*/?\s*(?:div|p|li|ul|ol|tr|td|th|table|h[1-6]|br|section|article|header|footer|nav|main|time)\b[^>]*>""")
        private val DROP = Regex("""(?is)<(script|style|noscript|svg)\b.*?</\1\s*>""")
        private val TAG = Regex("""(?s)<[^>]*>""")

        internal fun lines(html: String): List<String> =
            html.replace(DROP, " ").replace(BLOCK, "\n").replace(TAG, "")
                .let(::decode)
                .lines().map { it.replace(Regex("""[ \t ]+"""), " ").trim() }.filter { it.isNotEmpty() }

        private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#43;", "+")

        internal fun dateOf(line: String): LocalDate? {
            val m = DATE_LINE.matchEntire(line) ?: return null
            val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
        }

        private fun isChannel(line: String) = splitChannels(line) != null

        /** "TNT Sports 2TNT Sports 9" → ["TNT Sports 2", "TNT Sports 9"]; null when the line isn't channels. */
        internal fun splitChannels(line: String): List<String>? {
            val out = mutableListOf<String>()
            var rest = line.trim()
            while (rest.isNotEmpty()) {
                val b = BROADCASTERS.firstOrNull { rest.startsWith(it, ignoreCase = true) } ?: return null
                // What follows belongs to the name ("TNT Sports 2", "Sky Sports Main Event") up to the next broadcaster.
                val end = BROADCASTERS.mapNotNull { other -> rest.indexOf(other, b.length).takeIf { it > 0 } }.minOrNull() ?: rest.length
                out += rest.substring(0, end).trim()
                rest = rest.substring(end).trimStart(' ', '·', ',', '/', '|')
            }
            return out.takeIf { it.isNotEmpty() }
        }
    }
}
