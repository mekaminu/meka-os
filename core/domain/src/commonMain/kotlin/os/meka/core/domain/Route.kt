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

    /**
     * Live trains (Needs Meka #16, 2026-10-17): the next departures from his home station to where he changes (morning)
     * or from there home (evening), from National Rail's Live Departure Board, in [TrainBoardCodec]'s text.
     */
    const val DEPARTURES = "departures"
    /** Which way [DEPARTURES] goes: [TrainBoard.AM] (home → change) or [TrainBoard.PM] (change → home). */
    const val DEPARTURES_DIR = "departuresDir"
    /** When the server read [DEPARTURES], rounded down to [RouteRules.BOARD_STEP_MS]. */
    const val DEPARTURES_CHECKED = "departuresChecked"
    /** The live board's state: [TrainBoard.OK], [TrainBoard.NO_STATION], [TrainBoard.KEY] or [TrainBoard.ERROR]. */
    const val BOARD_STATE = "boardState"
    /** When [BOARD_STATE] was last ok (rounded like [DEPARTURES_CHECKED]). */
    const val BOARD_OK_AT = "boardOkAt"
    /**
     * Live trains, slice 4: the fallback line's own board, read with [DEPARTURES] only while the first line is in
     * trouble (a cancelled or late train among the next ones, or TfL not calling it good): from home to the route's
     * fallback station in the morning, from there home in the evening, in [TrainBoardCodec]'s text; "" otherwise.
     */
    const val FALLBACK_DEPARTURES = "fallbackDepartures"
}

/**
 * One departure on the live board. Times are London minutes of the day: [depMin] booked, [expMin] expected (equal to
 * it when on time; null when [delayed] with no estimate or [cancelled]); [arrMin] the booked arrival where he gets off.
 */
data class TrainDeparture(
    val depMin: Int,
    val expMin: Int?,
    val cancelled: Boolean = false,
    val delayed: Boolean = false,
    val platform: String? = null,
    val arrMin: Int? = null,
) {
    /** Minutes late (0 on time); null when cancelled or delayed with no estimate. */
    val lateMin: Int?
        get() {
            if (cancelled) return null
            val e = expMin ?: return null
            val d = ((e - depMin) % 1440 + 1440) % 1440
            return if (d < 720) d else 0 // early counts as on time
        }
}

/** The live board as the server last wrote it. */
data class TrainBoard(
    val departures: List<TrainDeparture> = emptyList(),
    val dir: String? = null,
    val checkedMs: Long? = null,
    val state: String? = null,
    val okAtMs: Long? = null,
    /** Live trains, slice 4: the fallback line's next departures (same way, same read), empty unless it was needed. */
    val fallback: List<TrainDeparture> = emptyList(),
) {
    companion object {
        val EMPTY = TrainBoard()
        const val AM = "am"
        const val PM = "pm"
        const val OK = "ok"
        /** His home station (or the change station) couldn't be found on National Rail's list. */
        const val NO_STATION = "nostation"
        /** The rail data key was refused. */
        const val KEY = "key"
        const val ERROR = "error"
    }
}

/**
 * "432,437,3,461;444,x,,": per departure the booked minute, the expected one ("d" delayed with no estimate, "x"
 * cancelled), the platform and the booked arrival minute where he gets off (blank when unknown). Unreadable parts are
 * skipped (it came from the network).
 */
object TrainBoardCodec {
    const val MAX = 6

    fun encode(list: List<TrainDeparture>): String = list.filter { it.depMin in 0 until 1440 }.take(MAX).joinToString(";") { d ->
        val exp = when { d.cancelled -> "x"; d.expMin == null -> "d"; else -> d.expMin.toString() }
        listOf(d.depMin.toString(), exp, platform(d.platform).orEmpty(), d.arrMin?.takeIf { it in 0 until 1440 }?.toString().orEmpty()).joinToString(",")
    }

    fun decode(s: String?): List<TrainDeparture> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 4) return@mapNotNull null
            val dep = f[0].toIntOrNull()?.takeIf { it in 0 until 1440 } ?: return@mapNotNull null
            val (exp, cancelled, delayed) = when (f[1]) {
                "x" -> Triple(null, true, false)
                "d" -> Triple(null, false, true)
                else -> Triple(f[1].toIntOrNull()?.takeIf { it in 0 until 1440 } ?: return@mapNotNull null, false, false)
            }
            TrainDeparture(dep, exp, cancelled, delayed, platform(f[2]), f[3].toIntOrNull()?.takeIf { it in 0 until 1440 })
        }.take(MAX)
    }

    /** Up to 3 letters or digits ("3", "10A"); anything else is dropped. */
    fun platform(p: String?): String? = p?.trim()?.takeIf { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' } }
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
    /** Live trains: the departure board ([LineStatusFields.DEPARTURES]); empty without the rail key or a home station. */
    val board: TrainBoard = TrainBoard.EMPTY,
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

/**
 * What Today shows on an office day's commute: the line and whether it needs a look (lit in the accent). [brief] is
 * what the morning brief says after "On the trains:": [spoken] without its "Trains:" lead and without the leave-by,
 * which the brief says on its own ([BriefStory.leaveBy]).
 */
data class RouteLine(val text: String, val lit: Boolean, val spoken: String, val brief: String = spoken.removePrefix(SPOKEN_LEAD)) {
    companion object {
        const val SPOKEN_LEAD = "Trains: "
    }
}

/**
 * Live trains, slice 3: when to leave for [train] — [leaveMin] (London minute of the day) is its expected time less
 * [walkMin], the time to the train from home ([morning]) or from work. [later] when it's later than his usual train
 * (he's past it, or the ones before it are cancelled or late). [words]: "leave by 07:32 for the 07:42" · "leave work
 * by 17:37 for the 17:52" · "leave by 07:44 for the 07:54 · later than usual".
 */
data class LeaveBy(val leaveMin: Int, val train: TrainDeparture, val walkMin: Int, val morning: Boolean, val later: Boolean = false) {
    val words: String
        get() = (if (morning) "leave by " else "leave work by ") + LocalClock.formatMinute(leaveMin) + " for the " +
            LocalClock.formatMinute(train.depMin) + if (later) " · later than usual" else ""
}

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

    /** A station's name as typed, cleaned ("King’s Cross" → "King's Cross"); null when it can't be one. */
    fun stationName(t: String): String? = station(t)

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

    /** A departure this late or worse (or cancelled, or delayed with no estimate) lights Today's line. */
    const val LATE_MIN = 5
    /** The live board is shown and told only this long after the server read it. */
    const val BOARD_FRESH_MS = 20 * 60_000L
    /** The server writes the board's read time rounded down to this (and the board itself only when it changed). */
    const val BOARD_STEP_MS = 5 * 60_000L
    /** How many of the next departures Today shows. */
    const val SHOWN = 3

    fun boardFresh(b: TrainBoard, nowMs: Long): Boolean {
        val checked = b.checkedMs ?: return false
        return b.state == TrainBoard.OK && b.departures.isNotEmpty() && nowMs - checked < BOARD_FRESH_MS + BOARD_STEP_MS
    }

    /** The departures still to leave at [nowMs] (expected time, else booked), the next [n]; a cancelled one by its booked time. */
    fun upcoming(b: TrainBoard, nowMs: Long, cal: LocalCalendar, n: Int = SHOWN): List<TrainDeparture> {
        val now = cal.minuteOfDay(nowMs)
        return b.departures.filter { d ->
            val leaves = if (d.cancelled) d.depMin else (d.expMin ?: d.depMin)
            // Minutes ahead, wrapping past midnight; a board reaches at most a few hours ahead.
            ((leaves - now) % 1440 + 1440) % 1440 < 12 * 60
        }.take(n)
    }

    /** "07:12 on time", "07:24 5 min late", "07:42 cancelled", "07:50 delayed". */
    fun words(d: TrainDeparture): String {
        val t = LocalClock.formatMinute(d.depMin)
        val late = d.lateMin
        return when {
            d.cancelled -> "$t cancelled"
            late == null -> "$t delayed"
            late == 0 -> "$t on time"
            else -> "$t $late min late"
        }
    }

    /** Cancelled, delayed with no estimate, or [LATE_MIN] or more late. */
    fun bad(d: TrainDeparture): Boolean = d.cancelled || d.lateMin.let { it == null || it >= LATE_MIN }

    /** The board when it is fresh and goes the way of [commute] (home → change in the morning, back in the evening). */
    fun liveBoard(s: LineStatusSnapshot, commute: Commute, nowMs: Long): TrainBoard? {
        val b = s.board
        if (!boardFresh(b, nowMs)) return null
        if (b.dir != (if (commute == Commute.MORNING) TrainBoard.AM else TrainBoard.PM)) return null
        return b
    }

    /**
     * Today's line on an office day's commute. With live departures (the rail key and his home station): "South Rail ·
     * 07:12 on time · 07:24 5 min late · 07:42 cancelled", lit when one of them is cancelled or [LATE_MIN]+ late, with
     * any leg TfL doesn't call good after it ("· Metro minor delays") and the fallback when the first line isn't good.
     * Without them, TfL's status as before. All running: "South Rail · Metro · good service". Otherwise each leg in
     * the order travelled, lit: "South Rail severe delays · Metro good service — North Rail to Riverside is running"
     * (the fallback named only when it is running and the first line isn't). Null outside the commute, without
     * anything fresh, without a route in About you, or when nothing is known about the route.
     */
    fun todayLine(s: LineStatusSnapshot, office: OfficeWindow?, nowMs: Long, cal: LocalCalendar): RouteLine? {
        val r = route() ?: return null
        val commute = commute(office, nowMs, cal) ?: return null
        val morning = commute == Commute.MORNING
        val tfl = fresh(s, nowMs)
        val legs = if (tfl) r.legs(morning).mapNotNull { id -> s.severityOf(id)?.let { id to it } } else emptyList()
        val first = if (tfl) s.severityOf(r.firstId) else null
        val fb = if (tfl) r.fallbackId?.let { s.severityOf(it) } else null
        val fallback = if (first != null && level(first) != LineLevel.GOOD && fb != null && level(fb) == LineLevel.GOOD) {
            fallbackAdvice(r, fb, morning)?.let { " — $it" }.orEmpty()
        } else ""
        val live = liveBoard(s, commute, nowMs)?.let { upcoming(it, nowMs, cal) }?.takeIf { it.isNotEmpty() }
        if (live != null) {
            val notGood = legs.filter { level(it.second) != LineLevel.GOOD }
            // Slice 4: the fallback line's own board, when it was read for trouble on the first line.
            val fbLive = fallbackLive(s, r, morning, nowMs, cal)
            val fallbackPart = if (fbLive != null && (live.any(::bad) || (first != null && level(first) != LineLevel.GOOD))) " — or $fbLive" else fallback
            val leave = office?.let { o -> liveBoard(s, commute, nowMs)?.let { leaveBy(it, commute, o, nowMs, cal) } }
            val rest = live.joinToString(" · ") { words(it) } + notGood.joinToString("") { (id, sev) -> " · " + part(id, sev, r) } + fallbackPart
            val text = name(r.firstId, r) + " · " + (leave?.let { it.words + " · " }.orEmpty()) + rest
            val lit = live.any(::bad) || notGood.isNotEmpty()
            fun say(t: String) = t.replace(" · ", ", ").replace(" — or ", ". Or ").replace(" — ", ". ")
            return RouteLine(text, lit = lit, spoken = "Trains: " + say(text), brief = say(name(r.firstId, r) + " · " + rest))
        }
        if (legs.isEmpty()) return null
        if (legs.all { level(it.second) == LineLevel.GOOD }) {
            val text = legs.joinToString(" · ") { name(it.first, r) } + " · good service"
            return RouteLine(text, lit = false, spoken = "Trains: " + text.replace(" · ", ", "))
        }
        val text = legs.joinToString(" · ") { (id, sev) -> part(id, sev, r) } + fallback
        return RouteLine(text, lit = true, spoken = "Trains: " + text.replace(" · ", ", ").replace(" — ", ". "))
    }

    /** "07:12 on time, platform 3, arrives 07:41". */
    private fun askWords(d: TrainDeparture): String =
        listOfNotNull(words(d), d.platform?.let { "platform $it" }, d.arrMin?.takeIf { !d.cancelled }?.let { "arrives " + LocalClock.formatMinute(it) })
            .joinToString(", ")

    /**
     * The status as Ask MEKA hears it ("are the trains OK?"): one line, any time it is fresh. "train lines on Meka's
     * route (TfL status, checked 08:30): South Rail good service; North Rail good service; Metro minor delays". With
     * a fresh live board also "next trains from Oakfield to Oakfield Junction (live departures, checked 07:05): 07:12
     * on time, platform 3, arrives 07:41; …". Empty when stale, unknown or without a route.
     */
    fun askLines(s: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar, office: OfficeWindow? = null): List<String> {
        val r = route() ?: return emptyList()
        val out = mutableListOf<String>()
        if (fresh(s, nowMs)) {
            val known = r.ids.mapNotNull { id -> s.severityOf(id)?.let { part(id, it, r) } }
            if (known.isNotEmpty()) {
                val at = LocalClock.formatMinute(cal.minuteOfDay(s.checkedMs!!))
                out += "train lines on Meka's route (TfL status, checked $at): " + known.joinToString("; ")
            }
        }
        val b = s.board
        if (boardFresh(b, nowMs)) {
            val next = upcoming(b, nowMs, cal, n = TrainBoardCodec.MAX)
            val home = Household.names.homeStation ?: "his home station"
            val change = r.change ?: "where he changes"
            val (from, to) = if (b.dir == TrainBoard.PM) change to home else home to change
            if (next.isNotEmpty()) {
                val at = LocalClock.formatMinute(cal.minuteOfDay(b.checkedMs!!))
                out += "next trains from $from to $to (live departures, checked $at): " + next.joinToString("; ") { askWords(it) }
                // Slice 3: when to leave, on an office day's commute with the time to the train in About you.
                val c = office?.let { commute(it, nowMs, cal) }
                val leave = if (c != null && b.dir == (if (c == Commute.MORNING) TrainBoard.AM else TrainBoard.PM)) leaveBy(b, c, office!!, nowMs, cal) else null
                leave?.let { l ->
                    out += "when Meka should leave (his ${l.walkMin} min to the train from ${if (c == Commute.MORNING) "home" else "work"} in About you): " + l.words
                }
            }
            // Slice 4: the fallback line's board, read because the first line is in trouble.
            val fbNext = upcoming(b.copy(departures = b.fallback), nowMs, cal, n = TrainBoardCodec.MAX)
            val fbTo = r.fallbackTo
            if (fbNext.isNotEmpty() && r.fallback != null && fbTo != null) {
                val at = LocalClock.formatMinute(cal.minuteOfDay(b.checkedMs!!))
                val (ff, ft) = if (b.dir == TrainBoard.PM) fbTo to home else home to fbTo
                out += "the fallback, ${r.fallback} from $ff to $ft (live departures, checked $at): " + fbNext.joinToString("; ") { askWords(it) }
            }
        }
        return out
    }

    const val HEALTH_KEY = "trains"

    /**
     * Health's Trains row: "Live · checked 07:05" once the board reads; what to do when it can't (no home station, a
     * station not found, the key refused, the feed down). Null without a route in About you (nothing to follow).
     */
    fun healthRow(b: TrainBoard, nowMs: Long, cal: LocalCalendar): HealthRow? {
        route() ?: return null
        val title = "Trains"
        val last = b.okAtMs?.let { "last live ${HealthRules.at(it, nowMs, cal)}" }
        return when {
            Household.names.homeStation == null -> HealthRow(HEALTH_KEY, title, "Line status from TfL · add your home station in About you for live departures", HealthState.UNKNOWN)
            b.state == null -> HealthRow(HEALTH_KEY, title, "Live departures start at the next check", HealthState.UNKNOWN)
            b.state == TrainBoard.NO_STATION -> HealthRow(HEALTH_KEY, title, "Couldn't find your home station or where you change on National Rail's list · check their names in About you", HealthState.WARN)
            b.state == TrainBoard.KEY -> HealthRow(HEALTH_KEY, title, "National Rail refused Navilife's rail key · line status from TfL meanwhile", HealthState.BAD)
            b.state == TrainBoard.ERROR -> HealthRow(HEALTH_KEY, title, listOfNotNull("Couldn't read live departures", last).joinToString(" · "), HealthState.WARN)
            else -> HealthRow(HEALTH_KEY, title, listOfNotNull("Live", b.checkedMs?.let { "checked ${HealthRules.at(it, nowMs, cal)}" }).joinToString(" · "), HealthState.OK)
        }
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
    fun notices(s: LineStatusSnapshot, office: OfficeWindow?, quiet: QuietHours, nowMs: Long, cal: LocalCalendar): List<Notice> =
        lineNotices(s, office, quiet, nowMs, cal) + liveNotices(s, office, quiet, nowMs, cal)

    private fun lineNotices(s: LineStatusSnapshot, office: OfficeWindow?, quiet: QuietHours, nowMs: Long, cal: LocalCalendar): List<Notice> {
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

    /** Live trains, slice 2: a train leaving within this many minutes is "in his window" for a heads-up. */
    const val WINDOW_MIN = 45

    /** When [d] leaves: its expected time, else booked (a cancelled one by its booked time). */
    private fun leavesMin(d: TrainDeparture): Int = if (d.cancelled) d.depMin else (d.expMin ?: d.depMin)

    /** Minutes from [nowMin] until [min], wrapping past midnight. */
    private fun ahead(min: Int, nowMin: Int): Int = ((min - nowMin) % 1440 + 1440) % 1440

    /**
     * Live trains, slice 2 (Places item 4: "a heads-up as soon as a train in his window is delayed ≥ 5 min or
     * cancelled, with the next good one"). On an office day's commute, with a fresh live board going the commute's way,
     * the first train leaving within [WINDOW_MIN] minutes that is [bad] brings one [NoticeSource.TRAINS] heads-up:
     * "South Rail 07:42 cancelled" · "Next good: 07:54 on time, platform 3, arrives 08:20 · also 07:50 cancelled · live
     * 07:05". The next good one is the first train after it that isn't bad (else one before it still to leave, "or");
     * with none on the board, the fallback line when TfL says it is running, else "Check before you leave". Once per
     * train (the key holds the day, the commute and the train's booked time, so a train going from 5 to 15 minutes late
     * doesn't buzz again; a later bad train gets its own once the first has gone). Due when the board was read, or
     * when quiet hours end; stale once that train has left or the commute is over. Wear OS shows it on the watch (the
     * phone's heads-ups aren't local-only).
     */
    fun liveNotices(s: LineStatusSnapshot, office: OfficeWindow?, quiet: QuietHours, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val r = route() ?: return emptyList()
        val commute = commute(office, nowMs, cal) ?: return emptyList()
        val b = liveBoard(s, commute, nowMs) ?: return emptyList()
        office!!
        val morning = commute == Commute.MORNING
        val nowMin = cal.minuteOfDay(nowMs)
        val next = upcoming(b, nowMs, cal, n = TrainBoardCodec.MAX)
        val inWindow = next.filter { ahead(leavesMin(it), nowMin) <= WINDOW_MIN }
        val worst = inWindow.firstOrNull(::bad) ?: return emptyList()
        val i = next.indexOf(worst)
        val after = next.drop(i + 1)
        val good = after.firstOrNull { !bad(it) }
        val before = if (good == null) next.take(i).firstOrNull { !bad(it) } else null
        val alsoBad = after.takeWhile { it != good }.filter(::bad).filter { ahead(leavesMin(it), nowMin) <= WINDOW_MIN }
        val from = if (morning) cal.toEpochMs(cal.epochDayOf(office.startMs), MORNING_FROM_MIN) else office.endMs - EVENING_LEAD_MS
        val until = if (morning) office.startMs else office.endMs + EVENING_AFTER_MS
        val leavesMs = cal.toEpochMs(cal.epochDayOf(nowMs), nowMin) + ahead(leavesMin(worst), nowMin) * 60_000L + 60_000L
        val expires = minOf(until, leavesMs)
        val atMs = quiet.endAfter(maxOf(from, b.checkedMs!!), cal)
        if (atMs >= expires) return emptyList()
        val advice = when {
            good != null -> "Next good: " + askWords(good) + (leaveFor(good, commute, nowMin)?.let { " · leave ${if (morning) "" else "work "}by " + LocalClock.formatMinute(it) }.orEmpty())
            before != null -> "Or the " + askWords(before)
            // Slice 4: the fallback line's first good train on its own board.
            else -> fallbackFirst(b, r, morning, nowMs, cal) ?: fallbackAdvice(r, r.fallbackId?.let { s.severityOf(it) }.takeIf { fresh(s, nowMs) }, morning) ?: "No good train on the board yet · check before you leave"
        }
        val also = alsoBad.takeIf { it.isNotEmpty() }?.let { l -> " · also " + l.joinToString(", ") { words(it) } }.orEmpty()
        val checked = LocalClock.formatMinute(cal.minuteOfDay(b.checkedMs))
        val day = cal.epochDayOf(office.startMs)
        return listOf(
            Notice(
                key = "trainlive:$day:${commute.name.lowercase()}:${worst.depMin}", source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP,
                title = name(r.firstId, r) + " " + words(worst), text = "$advice$also · live $checked",
                atMs = atMs, target = NoticeTarget.TODAY, expiresAtMs = expires,
            ).let { n ->
                // Speaking up, slice 1: cancelled or 10+ minutes late is said out loud where he can hear it privately.
                val said = SpeakUpRules.trainWords(worst, good, good?.let { leaveFor(it, commute, nowMin) })
                if (said == null) n else n.copy(speak = SpeakMoment.TRAIN, spoken = said)
            },
        )
    }

    // ---- Live trains, slice 4: the fallback line's own board ----

    /** How many of the fallback line's departures Today shows after the first line's. */
    const val FALLBACK_SHOWN = 2

    /** "North Rail to Riverside" in the morning, "North Rail from Riverside" in the evening; null without a fallback. */
    private fun fallbackName(r: CommuteRoute, morning: Boolean): String? {
        val f = r.fallback ?: return null
        return f + (r.fallbackTo?.let { if (morning) " to $it" else " from $it" } ?: "")
    }

    /**
     * Today's fallback part from the fallback board, while it is fresh and goes the commute's way: "North Rail to
     * Riverside · 07:15 on time · 07:30 2 min late" (its next [FALLBACK_SHOWN]); null with nothing on it.
     */
    fun fallbackLive(s: LineStatusSnapshot, r: CommuteRoute, morning: Boolean, nowMs: Long, cal: LocalCalendar): String? {
        val b = liveBoard(s, if (morning) Commute.MORNING else Commute.EVENING, nowMs) ?: return null
        val next = upcoming(b.copy(departures = b.fallback), nowMs, cal, n = FALLBACK_SHOWN).takeIf { it.isNotEmpty() } ?: return null
        val name = fallbackName(r, morning) ?: return null
        return name + " · " + next.joinToString(" · ") { words(it) }
    }

    /** The heads-up's "Or North Rail to Riverside: 07:15 on time, platform 2, arrives 07:58" (its first good train). */
    private fun fallbackFirst(b: TrainBoard, r: CommuteRoute, morning: Boolean, nowMs: Long, cal: LocalCalendar): String? {
        val good = upcoming(b.copy(departures = b.fallback), nowMs, cal, n = TrainBoardCodec.MAX).firstOrNull { !bad(it) } ?: return null
        val name = fallbackName(r, morning) ?: return null
        return "Or $name: " + askWords(good)
    }

    /**
     * The server's question before reading the fallback board: is the first line in trouble? A cancelled or late train
     * among the next [SHOWN] on [departures], or TfL's last status for the first line ([firstSeverity]) not good.
     */
    fun needsFallback(departures: List<TrainDeparture>, firstSeverity: Int?): Boolean =
        departures.take(SHOWN).any(::bad) || (firstSeverity != null && level(firstSeverity) != LineLevel.GOOD)

    // ---- Live trains, slice 3: leave-by times both ways ----

    /** The minutes to the train for [commute] (About you's Time to the train); null with none typed. */
    fun walkMin(commute: Commute): Int? =
        if (commute == Commute.MORNING) Household.names.walkHomeMin else Household.names.walkWorkMin

    /** When to leave for [d]: its expected time less the walk, if that's still ahead of [nowMin]; else null. */
    private fun leaveFor(d: TrainDeparture, commute: Commute, nowMin: Int): Int? {
        val walk = walkMin(commute) ?: return null
        if (ahead(leavesMin(d), nowMin) < walk) return null
        return (leavesMin(d) - walk).mod(1440)
    }

    /**
     * Live trains, slice 3 (Places item 4: "leave-by alarms use it"): the train to leave for and when. Only good trains
     * ([bad] ones never) he can still reach with About you's Time to the train. **Morning**: the latest one that keeps
     * him on his usual time (leaving home at work's start less the day's commute, [OfficeWindow.toWorkMin]), so a
     * cancelled train moves the leave-by earlier, never later; when he's past that or none earlier is good, the first
     * he can still catch ([LeaveBy.later]). While the board doesn't yet reach his usual train, nothing (it would be too
     * early to say). **Evening**: the first good train he can catch leaving work when it ends (or now, if later).
     * Null without the time to the train, or with nothing good on the board.
     */
    fun leaveBy(b: TrainBoard, commute: Commute, office: OfficeWindow, nowMs: Long, cal: LocalCalendar): LeaveBy? {
        val walk = walkMin(commute) ?: return null
        val morning = commute == Commute.MORNING
        val nowMin = cal.minuteOfDay(nowMs)
        val all = upcoming(b, nowMs, cal, n = TrainBoardCodec.MAX)
        val reach = all.filter { !bad(it) && ahead(leavesMin(it), nowMin) >= walk }
        val pick: TrainDeparture
        var later = false
        if (morning) {
            val usual = office.toWorkMin?.let { ((office.startMs - it * 60_000L + walk * 60_000L - nowMs) / 60_000L).toInt() }
            if (usual == null) {
                pick = reach.firstOrNull() ?: return null
            } else {
                val onTime = reach.lastOrNull { ahead(leavesMin(it), nowMin) <= usual }
                val reachesUsual = all.any { ahead(leavesMin(it), nowMin) >= usual }
                pick = when {
                    onTime != null && (reachesUsual || usual < walk) -> onTime
                    onTime != null -> return null
                    usual < walk || reachesUsual -> reach.firstOrNull()?.also { later = true } ?: return null
                    else -> return null
                }
            }
        } else {
            val ends = maxOf(0L, (office.endMs - nowMs) / 60_000L).toInt()
            pick = reach.firstOrNull { ahead(leavesMin(it), nowMin) >= walk + ends } ?: return null
        }
        val leave = (leavesMin(pick) - walk).mod(1440)
        return LeaveBy(leave, pick, walk, morning, later)
    }

    /**
     * A morning brief worth listening to, the train to leave for: on an office morning's commute with a fresh live board
     * and the time to the train in About you, [leaveBy] for the morning; null otherwise (the brief then says the
     * schedule's "Leave about …").
     */
    fun briefLeave(s: LineStatusSnapshot, office: OfficeWindow?, nowMs: Long, cal: LocalCalendar): LeaveBy? {
        if (office == null || route() == null) return null
        val commute = commute(office, nowMs, cal)?.takeIf { it == Commute.MORNING } ?: return null
        val board = liveBoard(s, commute, nowMs) ?: return null
        return leaveBy(board, commute, office, nowMs, cal)
    }

    /**
     * The brief's sentence for [l]: "Leave by 7:32 for the 7:42 train, home about 6:50." · "Your usual train isn't
     * running well, so leave by 7:44 for the 7:54 train." ([LeaveBy.later]); [homeMin] from the schedule, as before.
     */
    fun briefSentence(l: LeaveBy, homeMin: Int?, clock: (Int) -> String): String {
        val m = l.train.depMin.mod(1440)
        val h = (m / 60 + 11) % 12 + 1
        val train = if (m % 60 == 0) "$h o'clock" else "$h:" + (m % 60).toString().padStart(2, '0')
        val leave = "leave by ${clock(l.leaveMin)} for the $train train"
        val home = homeMin?.let { ", home about ${clock(it)}" }.orEmpty()
        return if (l.later) "Your usual train isn't running well, so $leave$home." else leave.replaceFirstChar { it.uppercase() } + "$home."
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
            board = TrainBoard(
                departures = TrainBoardCodec.decode(e[LineStatusFields.DEPARTURES]?.textOrNull),
                dir = e[LineStatusFields.DEPARTURES_DIR]?.textOrNull,
                checkedMs = (e[LineStatusFields.DEPARTURES_CHECKED] as? FieldValue.Int64)?.value,
                state = e[LineStatusFields.BOARD_STATE]?.textOrNull,
                okAtMs = (e[LineStatusFields.BOARD_OK_AT] as? FieldValue.Int64)?.value,
                fallback = TrainBoardCodec.decode(e[LineStatusFields.FALLBACK_DEPARTURES]?.textOrNull),
            ),
        )
    }

    companion object {
        const val ENTITY_ID = "line_status"
    }
}
