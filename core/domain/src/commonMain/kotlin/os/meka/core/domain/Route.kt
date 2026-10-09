package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * Meka's route to work (build plan "Places, location weather, per-day work hours and trains", item 4; Meka 2026-10-09
 * 12:54): Thameslink Biggleswade → Farringdon, then the Elizabeth line to Canary Wharf; when Thameslink has problems,
 * Great Northern to King's Cross instead. Until the National Rail live departures key arrives (Needs Meka #16), the
 * lines' status comes from TfL's public line status (free, no key, nothing about Meka sent: the server asks for three
 * line names). The server mirrors it into one server-written `context_mode` entity, [LineStatusStore.ENTITY_ID]
 * (ADR-008 addendum), only when it changed.
 *
 * Numbers only: TfL's status codes, never its words, so every line the apps show or Ask hears is written here.
 */
object LineStatusFields {
    /** "elizabeth=10;great-northern=10;thameslink=9": each line's TfL status severity (the worst one in force now). */
    const val LINES = "lines"
    /** When the server last read TfL, rounded down to [RouteRules.CHECKED_STEP_MS] (so it writes at most twice an hour). */
    const val CHECKED = "checked"
}

/** One line's status: TfL's line id and its status severity code. */
data class LineState(val id: String, val severity: Int)

data class LineStatusSnapshot(val lines: List<LineState>, val checkedMs: Long?) {
    fun severityOf(id: String): Int? = lines.firstOrNull { it.id == id }?.severity

    companion object {
        val EMPTY = LineStatusSnapshot(emptyList(), null)
    }
}

/** The compact text the server writes and the devices read. Unreadable parts are skipped (it came from the network). */
object LineStatusCodec {
    const val MAX_LINES = 12
    private const val MAX_ID = 40
    private val ID = Regex("[a-z0-9-]{1,$MAX_ID}")

    fun encode(lines: List<LineState>): String =
        lines.filter { ID.matches(it.id) && it.severity in 0..99 }.distinctBy { it.id }.sortedBy { it.id }.take(MAX_LINES)
            .joinToString(";") { "${it.id}=${it.severity}" }

    fun decode(s: String?): List<LineState> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val id = part.substring(0, eq).trim()
            val sev = part.substring(eq + 1).trim().toIntOrNull() ?: return@mapNotNull null
            if (!ID.matches(id) || sev !in 0..99) return@mapNotNull null
            LineState(id, sev)
        }.distinctBy { it.id }.take(MAX_LINES)
    }
}

/** How bad a status is. */
enum class LineLevel { GOOD, MINOR, MAJOR }

/** What Today shows on an office day's commute: the line and whether it needs a look (lit in the accent). */
data class RouteLine(val text: String, val lit: Boolean, val spoken: String)

/** Non-AI, pure. */
object RouteRules {
    const val THAMESLINK = "thameslink"
    const val GREAT_NORTHERN = "great-northern"
    const val ELIZABETH = "elizabeth"
    /** The lines the server asks TfL about: the direct route, its fallback, and the onward leg. */
    val LINES = listOf(THAMESLINK, GREAT_NORTHERN, ELIZABETH)

    /** The morning's line shows from 06:00 until work starts. */
    const val MORNING_FROM_MIN = 6 * 60
    /** The evening's from 45 minutes before work ends until 90 minutes after (the journey home). */
    const val EVENING_LEAD_MS = 45 * 60_000L
    const val EVENING_AFTER_MS = 90 * 60_000L
    /** A status older than this isn't shown or told (the server stopped reading it). */
    const val FRESH_MS = 60 * 60_000L
    const val CHECKED_STEP_MS = 30 * 60_000L

    fun name(id: String): String = when (id) {
        THAMESLINK -> "Thameslink"
        GREAT_NORTHERN -> "Great Northern"
        ELIZABETH -> "Elizabeth line"
        else -> id.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }

    /** TfL's status severities (Line/Meta/Severity) in plain words. */
    fun words(severity: Int): String = when (severity) {
        0 -> "special service"
        1 -> "closed"
        2 -> "suspended"
        3 -> "part suspended"
        4 -> "planned closure"
        5 -> "part closure"
        6 -> "severe delays"
        7 -> "reduced service"
        8 -> "bus service"
        9 -> "minor delays"
        10 -> "good service"
        11 -> "part closed"
        12 -> "exit only"
        13 -> "no step-free access"
        14 -> "change of frequency"
        15 -> "diverted"
        16 -> "not running"
        17 -> "issues reported"
        18 -> "no issues"
        19 -> "information"
        20 -> "service closed"
        else -> "status unknown"
    }

    /** Good: trains run as normal (station-level notes count as good). Major: no train, or one you can't rely on. */
    fun level(severity: Int): LineLevel = when (severity) {
        10, 12, 13, 18, 19 -> LineLevel.GOOD
        0, 7, 9, 14, 15, 17 -> LineLevel.MINOR
        else -> LineLevel.MAJOR
    }

    /** Of several statuses in force on one line, the one that matters most (worst level; then TfL's order). */
    fun worst(severities: List<Int>): Int? =
        severities.filter { it in 0..99 }.sortedWith(compareByDescending<Int> { level(it).ordinal }.thenBy { it }).firstOrNull()

    fun fresh(s: LineStatusSnapshot, nowMs: Long): Boolean {
        val checked = s.checkedMs ?: return false
        // A step's rounding plus the freshness window.
        return s.lines.isNotEmpty() && nowMs - checked < FRESH_MS + CHECKED_STEP_MS
    }

    enum class Commute { MORNING, EVENING }

    /** Which commute [nowMs] is in on an office day: from 06:00 until work starts, or around the end of work. */
    fun commute(office: OfficeWindow?, nowMs: Long, cal: LocalCalendar): Commute? {
        office ?: return null
        val morningFrom = cal.toEpochMs(cal.epochDayOf(office.startMs), MORNING_FROM_MIN)
        return when {
            nowMs >= morningFrom && nowMs < office.startMs -> Commute.MORNING
            nowMs >= office.endMs - EVENING_LEAD_MS && nowMs < office.endMs + EVENING_AFTER_MS -> Commute.EVENING
            else -> null
        }
    }

    private fun part(id: String, sev: Int) = "${name(id)} ${words(sev)}"

    /**
     * Today's line on an office day's commute. All running: "Thameslink · Elizabeth line · good service". Otherwise
     * each leg in the order travelled, lit: "Thameslink severe delays · Elizabeth line good service — Great Northern
     * to King's Cross is running" (the fallback named only when it is running and Thameslink isn't). Null outside the
     * commute, without a fresh status, or when TfL said nothing about the route.
     */
    fun todayLine(s: LineStatusSnapshot, office: OfficeWindow?, nowMs: Long, cal: LocalCalendar): RouteLine? {
        val commute = commute(office, nowMs, cal) ?: return null
        if (!fresh(s, nowMs)) return null
        val legs = (if (commute == Commute.MORNING) listOf(THAMESLINK, ELIZABETH) else listOf(ELIZABETH, THAMESLINK))
            .mapNotNull { id -> s.severityOf(id)?.let { id to it } }
        if (legs.isEmpty()) return null
        if (legs.all { level(it.second) == LineLevel.GOOD }) {
            val text = legs.joinToString(" · ") { name(it.first) } + " · good service"
            return RouteLine(text, lit = false, spoken = "Trains: " + text.replace(" · ", ", "))
        }
        val tl = s.severityOf(THAMESLINK)
        val gn = s.severityOf(GREAT_NORTHERN)
        val fallback = if (tl != null && level(tl) != LineLevel.GOOD && gn != null && level(gn) == LineLevel.GOOD) {
            if (commute == Commute.MORNING) " — Great Northern to King's Cross is running" else " — Great Northern from King's Cross is running"
        } else ""
        val text = legs.joinToString(" · ") { (id, sev) -> part(id, sev) } + fallback
        return RouteLine(text, lit = true, spoken = "Trains: " + text.replace(" · ", ", ").replace(" — ", ". "))
    }

    /**
     * The status as Ask MEKA hears it ("are the trains OK?"): one line, any time it is fresh. "train lines on Meka's
     * route (TfL status, checked 08:30): Thameslink good service; Great Northern good service; Elizabeth line minor
     * delays". Empty when stale or unknown.
     */
    fun askLines(s: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar): List<String> {
        if (!fresh(s, nowMs)) return emptyList()
        val known = LINES.mapNotNull { id -> s.severityOf(id)?.let { part(id, it) } }
        if (known.isEmpty()) return emptyList()
        val at = LocalClock.formatMinute(cal.minuteOfDay(s.checkedMs!!))
        return listOf("train lines on Meka's route (TfL status, checked $at): " + known.joinToString("; "))
    }

    /** The server's checked time, rounded down so a quiet line writes at most twice an hour. */
    fun checkedStep(nowMs: Long): Long = nowMs.floorDiv(CHECKED_STEP_MS) * CHECKED_STEP_MS
}

/** Reads the mirrored line status. The apps never write it. */
class LineStatusStore(private val replica: Replica) {
    fun snapshot(): LineStatusSnapshot {
        val e = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID) ?: return LineStatusSnapshot.EMPTY
        return LineStatusSnapshot(
            lines = LineStatusCodec.decode(e[LineStatusFields.LINES]?.textOrNull),
            checkedMs = (e[LineStatusFields.CHECKED] as? FieldValue.Int64)?.value,
        )
    }

    companion object {
        const val ENTITY_ID = "line_status"
    }
}
