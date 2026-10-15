package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * The commute's train lines (build plan "Places, location weather, per-day work hours and trains", item 4; Meka
 * 2026-10-09 12:54): the line from home, the station where he changes, the onward line, and the line to take instead
 * when the first one has problems. Rule 13, release 2 slice 6 (2026-10-15): the route is About you's **Train route**
 * ([CommuteRoute], read through [Household.names]), never the code; with none typed no line is asked about or shown.
 * Until the National Rail live departures key arrives (Needs Meka #16), the lines' status comes from TfL's public line
 * status (free, no key, nothing about Meka sent: the server asks for the route's line names). The server mirrors it
 * into one server-written `context_mode` entity, [LineStatusStore.ENTITY_ID] (ADR-008 addendum), only when it changed.
 *
 * Numbers only: TfL's status codes, never its words, so every line the apps show or Ask hears is written here.
 */
object LineStatusFields {
    /** "metro=10;north-rail=10;south-rail=9": each line's TfL status severity (the worst one in force now). */
    const val LINES = "lines"
    /** When the server last read TfL, rounded down to [RouteRules.CHECKED_STEP_MS] (so it writes at most twice an hour). */
    const val CHECKED = "checked"
    /**
     * The commute counts everywhere, slice 7: TfL's planned status for the next morning's commute (06:00–10:00 London
     * on [PLANNED_DAY]), in the same "id=severity;…" text as [LINES]. The server reads it in the evening.
     */
    const val PLANNED = "plannedLines"
    /** The day [PLANNED] is for (epoch day). */
    const val PLANNED_DAY = "plannedDay"
    /** When the server read [PLANNED], rounded down like [CHECKED]. */
    const val PLANNED_CHECKED = "plannedChecked"
}

/** One line's status: TfL's line id and its status severity code. */
data class LineState(val id: String, val severity: Int)

data class LineStatusSnapshot(
    val lines: List<LineState>,
    val checkedMs: Long?,
    /** The next morning's planned status ([LineStatusFields.PLANNED]); empty when the server hasn't read one. */
    val planned: List<LineState> = emptyList(),
    val plannedDay: Long? = null,
    val plannedCheckedMs: Long? = null,
) {
    fun severityOf(id: String): Int? = lines.firstOrNull { it.id == id }?.severity
    fun plannedSeverityOf(id: String): Int? = planned.firstOrNull { it.id == id }?.severity

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

/**
 * The commute's train route as About you keeps it (release 2 slice 6): "South Rail to Oakfield Junction, then Metro;
 * or North Rail to Riverside". Line names as he typed them (said and shown as typed); TfL's line ids come from them
 * ([RouteRules.lineId]: "Metro line" → "metro", "Hammersmith & City" → "hammersmith-city").
 */
data class CommuteRoute(
    /** The line from home ("South Rail"). */
    val first: String,
    /** Where he changes to [onward] ("Oakfield Junction"); null for none. */
    val change: String? = null,
    /** The onward line ("Metro"); null for a one-train journey. */
    val onward: String? = null,
    /** The line to take when [first] has problems ("North Rail"); null for none. */
    val fallback: String? = null,
    /** Where [fallback] goes ("Riverside"); null when not said. */
    val fallbackTo: String? = null,
) {
    val firstId: String get() = RouteRules.lineId(first)
    val onwardId: String? get() = onward?.let(RouteRules::lineId)
    val fallbackId: String? get() = fallback?.let(RouteRules::lineId)

    /** The lines the server asks TfL about: the first line, its fallback, the onward leg (distinct). */
    val ids: List<String> get() = listOfNotNull(firstId, fallbackId, onwardId).distinct()

    /** The legs in the order travelled: to work [first] then [onward]; home the other way round. */
    fun legs(morning: Boolean): List<String> =
        listOfNotNull(firstId, onwardId).distinct().let { if (morning) it else it.reversed() }

    /** The name as typed for [id], or null when it isn't on this route. */
    fun nameOf(id: String): String? = when (id) {
        firstId -> first
        onwardId -> onward
        fallbackId -> fallback
        else -> null
    }

    /** As kept and shown: "South Rail to Oakfield Junction, then Metro; or North Rail to Riverside". */
    val text: String
        get() = buildString {
            append(first)
            change?.let { append(" to ").append(it) }
            onward?.let { append(if (change != null) ", then " else " then ").append(it) }
            fallback?.let { f -> append("; or ").append(f); fallbackTo?.let { append(" to ").append(it) } }
        }
}

/** How bad a status is. */
enum class LineLevel { GOOD, MINOR, MAJOR }

/** What Today shows on an office day's commute: the line and whether it needs a look (lit in the accent). */
data class RouteLine(val text: String, val lit: Boolean, val spoken: String)

/** Non-AI, pure. */
object RouteRules {
    /** The route the rules read: About you's Train route ([Household.names]); null with none typed. */
    fun route(): CommuteRoute? = Household.names.route

    /** The lines the server asks TfL about for [r] (none without a route). */
    fun lines(r: CommuteRoute? = route()): List<String> = r?.ids.orEmpty()

    /** Setup's Train lines step, and Ask, with no route in About you. */
    const val NO_ROUTE = "Add your train route in About you and Navilife follows its lines' status from TfL"

    /** Setup's step with a route: "South Rail, North Rail and Metro from TfL". */
    fun setupLine(r: CommuteRoute): String {
        val names = r.ids.map { name(it, r) }
        val joined = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "$joined from TfL"
    }

    const val MAX_LINE = 30
    const val MAX_STATION = 40

    /**
     * TfL's id for a line name as typed: lower case, a trailing " line" dropped, "&" and every other run of
     * non-letters a single hyphen ("Metro line" → "metro", "Hammersmith & City" → "hammersmith-city",
     * "North Rail" → "north-rail"). Empty for a name with no letters or digits.
     */
    fun lineId(name: String): String {
        var t = name.trim().lowercase()
        if (t.endsWith(" line")) t = t.dropLast(5)
        val out = StringBuilder()
        for (c in t) {
            if (c in 'a'..'z' || c in '0'..'9') out.append(c)
            else if (out.isNotEmpty() && out.last() != '-') out.append('-')
        }
        return out.toString().trimEnd('-')
    }

    private fun lineName(t: String): String? {
        val n = t.trim()
        if (n.isEmpty() || n.length > MAX_LINE || !n[0].isLetter()) return null
        if (n.any { !(it.isLetterOrDigit() || it == ' ' || it == '&' || it == '-' || it == '\'' || it == '.') }) return null
        return n.takeIf { lineId(it).isNotEmpty() }
    }

    private fun station(t: String): String? {
        val n = t.trim()
        if (n.isEmpty() || n.length > MAX_STATION) return null
        if (n.any { !(it.isLetter() || it == ' ' || it == '&' || it == '-' || it == '\'' || it == '’' || it == '.' || it == '(' || it == ')') }) return null
        return n.replace('’', '\'')
    }

    /** "A to B" → (A, B); "A" → (A, null). Null when either part can't be read. */
    private fun leg(t: String, stationOk: (String) -> String?): Pair<String, String?>? {
        val i = t.lowercase().indexOf(" to ")
        if (i < 0) return lineName(t)?.let { it to null }
        val line = lineName(t.substring(0, i)) ?: return null
        val to = stationOk(t.substring(i + 4)) ?: return null
        return line to to
    }

    /**
     * Reads the Train route as typed: "South Rail to Oakfield Junction, then Metro; or North Rail to Riverside" (the
     * change station and the onward line optional; "or …" after a semicolon optional; "instead" or "else" also start
     * the fallback). Null when it can't be read. Spaces collapsed; ", then" and " then " both work.
     */
    fun parse(typed: String?): CommuteRoute? {
        val t = typed?.trim()?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }?.joinToString(" ") ?: return null
        if (t.isEmpty() || t.length > 160) return null
        val parts = t.split(';')
        if (parts.size > 2) return null
        val main = parts[0].trim()
        val lower = main.lowercase()
        val thenAt = listOf(", then ", " then ").map { lower.indexOf(it) to it.length }.filter { it.first >= 0 }.minByOrNull { it.first }
        val firstPart = if (thenAt != null) main.substring(0, thenAt.first) else main
        val onward = thenAt?.let { lineName(main.substring(it.first + it.second)) ?: return null }
        val (first, change) = leg(firstPart.trim(), ::station) ?: return null
        var fallback: String? = null
        var fallbackTo: String? = null
        if (parts.size == 2) {
            var f = parts[1].trim()
            for (w in listOf("or ", "else ", "instead ", "otherwise ")) if (f.lowercase().startsWith(w)) { f = f.substring(w.length).trim(); break }
            if (f.isNotEmpty()) {
                val l = leg(f, ::station) ?: return null
                fallback = l.first; fallbackTo = l.second
            }
        }
        val r = CommuteRoute(first, change, onward, fallback, fallbackTo)
        if (r.onwardId == r.firstId) return CommuteRoute(first, change, null, fallback, fallbackTo)
        return r
    }

    /** The morning's line shows from 06:00 until work starts. */
    const val MORNING_FROM_MIN = 6 * 60
    /** The evening's from 45 minutes before work ends until 90 minutes after (the journey home). */
    const val EVENING_LEAD_MS = 45 * 60_000L
    const val EVENING_AFTER_MS = 90 * 60_000L
    /** A status older than this isn't shown or told (the server stopped reading it). */
    const val FRESH_MS = 60 * 60_000L
    const val CHECKED_STEP_MS = 30 * 60_000L

    /** The line's name as About you has it, else the id in words ("north-rail" → "North Rail"). */
    fun name(id: String, r: CommuteRoute? = route()): String =
        r?.nameOf(id) ?: id.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /** The name inside a sentence: "the Metro line" for a name ending "line", else as typed ("North Rail"). */
    fun spoken(id: String, r: CommuteRoute? = route()): String =
        name(id, r).let { n -> if (n.lowercase().endsWith(" line") && !n.lowercase().startsWith("the ")) "the $n" else n }

    /**
     * What to do when the first line isn't running well, from the fallback's [fallbackSeverity]: "North Rail to
     * Riverside is running" (from Riverside on the way home), "North Rail severe delays too", or null when the route
     * has no fallback or TfL said nothing about it.
     */
    fun fallbackAdvice(r: CommuteRoute, fallbackSeverity: Int?, morning: Boolean): String? {
        val f = r.fallback ?: return null
        val sev = fallbackSeverity ?: return null
        return if (level(sev) == LineLevel.GOOD) {
            f + (r.fallbackTo?.let { if (morning) " to $it" else " from $it" } ?: "") + " is running"
        } else "$f ${words(sev)} too"
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

    private fun part(id: String, sev: Int, r: CommuteRoute? = route()) = "${name(id, r)} ${words(sev)}"

    /**
     * Today's line on an office day's commute. All running: "South Rail · Metro · good service". Otherwise each leg in
     * the order travelled, lit: "South Rail severe delays · Metro good service — North Rail to Riverside is running"
     * (the fallback named only when it is running and the first line isn't). Null outside the commute, without a fresh
     * status, without a route in About you, or when TfL said nothing about the route.
     */
    fun todayLine(s: LineStatusSnapshot, office: OfficeWindow?, nowMs: Long, cal: LocalCalendar): RouteLine? {
        val r = route() ?: return null
        val commute = commute(office, nowMs, cal) ?: return null
        if (!fresh(s, nowMs)) return null
        val legs = r.legs(commute == Commute.MORNING).mapNotNull { id -> s.severityOf(id)?.let { id to it } }
        if (legs.isEmpty()) return null
        if (legs.all { level(it.second) == LineLevel.GOOD }) {
            val text = legs.joinToString(" · ") { name(it.first, r) } + " · good service"
            return RouteLine(text, lit = false, spoken = "Trains: " + text.replace(" · ", ", "))
        }
        val first = s.severityOf(r.firstId)
        val fb = r.fallbackId?.let { s.severityOf(it) }
        val fallback = if (first != null && level(first) != LineLevel.GOOD && fb != null && level(fb) == LineLevel.GOOD) {
            fallbackAdvice(r, fb, commute == Commute.MORNING)?.let { " — $it" }.orEmpty()
        } else ""
        val text = legs.joinToString(" · ") { (id, sev) -> part(id, sev, r) } + fallback
        return RouteLine(text, lit = true, spoken = "Trains: " + text.replace(" · ", ", ").replace(" — ", ". "))
    }

    /**
     * The status as Ask MEKA hears it ("are the trains OK?"): one line, any time it is fresh. "train lines on Meka's
     * route (TfL status, checked 08:30): South Rail good service; North Rail good service; Metro minor delays". Empty
     * when stale, unknown or without a route.
     */
    fun askLines(s: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar): List<String> {
        val r = route() ?: return emptyList()
        if (!fresh(s, nowMs)) return emptyList()
        val known = r.ids.mapNotNull { id -> s.severityOf(id)?.let { part(id, it, r) } }
        if (known.isEmpty()) return emptyList()
        val at = LocalClock.formatMinute(cal.minuteOfDay(s.checkedMs!!))
        return listOf("train lines on Meka's route (TfL status, checked $at): " + known.joinToString("; "))
    }

    /**
     * The commute's heads-ups (Places item 4, before live departures): on an office day's commute, with a fresh status,
     * one [NoticeSource.TRAINS] heads-up for each leg of the route (the first line, then the onward one) that isn't
     * running well ([LineLevel.MAJOR]: severe delays, suspended, closed…; minor delays are left to Today's line).
     * "South Rail severe delays" · "North Rail to Riverside is running · TfL 07:30". Once per leg per commute
     * (the key holds the day and the commute, not the status, so a line going from severe delays to suspended doesn't
     * buzz again); due when the status was read, or when quiet hours end if that was in them (so a 06:30 problem
     * reaches Meka at 07:00 rather than being lost to a digest after he has left); stale once the commute is over
     * (work starts, or 90 minutes after it ends).
     */
    fun notices(s: LineStatusSnapshot, office: OfficeWindow?, quiet: QuietHours, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val r = route() ?: return emptyList()
        val commute = commute(office, nowMs, cal) ?: return emptyList()
        if (!fresh(s, nowMs)) return emptyList()
        office!!
        val morning = commute == Commute.MORNING
        val from = if (morning) cal.toEpochMs(cal.epochDayOf(office.startMs), MORNING_FROM_MIN) else office.endMs - EVENING_LEAD_MS
        val until = if (morning) office.startMs else office.endMs + EVENING_AFTER_MS
        val read = maxOf(from, s.checkedMs!!)
        val atMs = quiet.endAfter(read, cal)
        if (atMs >= until) return emptyList()
        val checked = LocalClock.formatMinute(cal.minuteOfDay(s.checkedMs))
        val day = cal.epochDayOf(office.startMs)
        return r.legs(morning).mapNotNull { id ->
            val sev = s.severityOf(id) ?: return@mapNotNull null
            if (level(sev) != LineLevel.MAJOR) return@mapNotNull null
            val advice = if (id == r.firstId) {
                fallbackAdvice(r, r.fallbackId?.let { s.severityOf(it) }, morning) ?: "Check before you leave"
            } else {
                val at = r.change
                if (morning) "Your onward leg" + (at?.let { " from $it" } ?: "") else "Your first leg" + (at?.let { ", to $it" } ?: "")
            }
            Notice(
                key = "trains:$day:${commute.name.lowercase()}:$id", source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP,
                title = part(id, sev, r).replaceFirstChar { it.uppercaseChar() }, text = "$advice · TfL $checked",
                atMs = atMs, target = NoticeTarget.TODAY, expiresAtMs = until,
            )
        }
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
            planned = LineStatusCodec.decode(e[LineStatusFields.PLANNED]?.textOrNull),
            plannedDay = (e[LineStatusFields.PLANNED_DAY] as? FieldValue.Int64)?.value,
            plannedCheckedMs = (e[LineStatusFields.PLANNED_CHECKED] as? FieldValue.Int64)?.value,
        )
    }

    companion object {
        const val ENTITY_ID = "line_status"
    }
}
