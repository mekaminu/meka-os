package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Balance view (V2, slice 1, 2026-10-19): one Monday–Sunday week's time across work, family, health and himself, against
 * targets he sets. Shown in Review on both apps, for the week Review shows. Non-AI, pure, read only: everything is
 * counted from what Navilife already holds, nothing is inferred from free text beyond whole words in a title.
 *
 * Where the time comes from ([BalanceRules.count]):
 * - **Work**: Work mode's hours on each work day (leave, bank holidays and a "Work off" day don't count), less the
 *   part of a family, health or self event that falls inside them (a dentist at 14:00 isn't work), plus a timed event
 *   outside them whose title says it's work ("Client call", "Interview").
 * - **Family**: a child's club session or fixture ([childOf]), and events naming the family (Week ahead's family words,
 *   About you's names) or a family occasion ("Date night", "Birthday party").
 * - **Health** and **Self**: events whose title says so (gym, run, dentist… / reading, cinema, haircut…), and each day a
 *   habit with such a title was ticked and no event that day already counted: [HABIT_HEALTH_MIN] for a health habit,
 *   [HABIT_SELF_MIN] for a self one.
 * In the week in progress only what has happened so far counts. All-day events, the matches he follows and events with
 * no clear area aren't counted (and [BalanceView.uncounted] says how many). Overlapping events in one area count once.
 *
 * Targets: one synced `context_mode` entity [ENTITY_ID] (ADR-008 addendum 2026-10-19), minutes a week per area,
 * last-writer-wins per field; absent = the default ([BalanceArea.defaultMin]; work = his own hours that week).
 *
 * Slice 2 (2026-10-19): each row also carries a four-week trend ([BalanceRules.trendMinutes], [BalanceRules.trend]): the
 * reviewed week and the three before, each counted the same way, with "Last 4 weeks · met in 2 · about 6 h a week".
 */
enum class BalanceArea(val label: String, val field: String, val defaultMin: Int?, val choices: List<Int>) {
    WORK("Work", "workMin", null, listOf(35, 40, 45, 50).map { it * 60 }),
    FAMILY("Family", "familyMin", 8 * 60, listOf(4, 6, 8, 10, 14).map { it * 60 }),
    HEALTH("Health", "healthMin", 3 * 60, listOf(2, 3, 4, 5, 7).map { it * 60 }),
    SELF("Self", "selfMin", 2 * 60, listOf(1, 2, 3, 5).map { it * 60 }),
}

/**
 * One week of an area's trend (slice 2): the Monday it starts, "12 Oct", the minutes counted, [pct] of the trend's
 * scale (the biggest week or the target, whichever is more) for its little bar, whether it met its target (work: went
 * over), and whether it's the week in progress (counted so far).
 */
data class BalanceWeek(val weekStart: Long, val label: String, val minutes: Int, val pct: Int, val met: Boolean, val current: Boolean)

/** One thing that counted: "Football U10s" · "Sat" · 90 min. */
data class BalanceItem(val area: BalanceArea, val title: String, val day: Long, val minutes: Int)

/** One area's row: "Family" · "6 h 30 of 8 h" · "1 h 30 to go"; [pct] of the target for its bar (0–100). */
data class BalanceRow(
    val area: BalanceArea,
    val label: String,
    val minutes: Int,
    val targetMin: Int,
    val pct: Int,
    /** "6 h 30 of 8 h" (work: "36 h of your 37 h 30"). */
    val line: String,
    /** "Met", "1 h 30 to go", "2 h short", "3 h over", "Within your hours"; null with nothing to say. */
    val status: String?,
    /** The status in the accent: a target met, or work over its target. */
    val lit: Boolean,
    /** Up to [BalanceRules.MAX_ITEMS] of what counted, biggest first: "Football U10s · Sat · 1 h 30". */
    val items: List<String>,
    val moreItems: Int,
    /** The target's chips: minutes a week, the chosen one marked by [targetMin] (work's "Your hours" is [BalanceRules.YOUR_HOURS]). */
    val choices: List<Int>,
    /** The target is the default (not set by him). */
    val defaultTarget: Boolean,
    val spoken: String,
    /** Slice 2: the reviewed week and the [BalanceRules.TREND_WEEKS] - 1 before it, oldest first; empty without a trend. */
    val trend: List<BalanceWeek> = emptyList(),
    /** The target's height on the trend's scale (0–100), for its hairline. */
    val trendTargetPct: Int = 0,
    /** "Last 4 weeks · met in 2 · about 6 h 30 a week" (work: "over in 3"); null when all four are empty. */
    val trendLine: String? = null,
)

data class BalanceView(
    val weekStart: Long,
    val isCurrent: Boolean,
    val rows: List<BalanceRow>,
    /** "2 of 3 targets met · work 3 h over" ("so far" in the week in progress). */
    val line: String,
    /** Where the time comes from, one line. */
    val sources: String,
    /** "4 events not counted · no clear area" or null. */
    val uncounted: String?,
) {
    companion object {
        val EMPTY = BalanceView(0, true, emptyList(), "", BalanceRules.SOURCES, null)
    }
}

object BalanceFields {
    val ALL: List<String> get() = BalanceArea.entries.map { it.field }
}

object BalanceRules {
    const val ENTITY_ID = "balance_targets"
    const val TITLE = "Balance"
    const val HABIT_HEALTH_MIN = 45
    const val HABIT_SELF_MIN = 30
    const val MAX_ITEMS = 3
    /** Slice 2: how many weeks the trend shows (the reviewed one included). */
    const val TREND_WEEKS = 4
    /** Work's chip for "his own hours" (clears the set target). */
    const val YOUR_HOURS = 0
    const val SOURCES = "From your calendar, your work hours and the habits you ticked. All-day events, matches you follow and events with no clear area aren't counted."

    private val HEALTH = setOf(
        "gym", "run", "running", "jog", "jogging", "workout", "yoga", "pilates", "swim", "swimming", "physio", "physiotherapy",
        "dentist", "doctor", "doctors", "gp", "hospital", "walk", "walking", "hike", "hiking", "cycle", "cycling", "bike", "spin",
        "tennis", "padel", "squash", "badminton", "fitness", "stretch", "stretching", "crossfit", "boxing", "climbing",
        "bouldering", "parkrun", "massage", "sauna", "checkup", "optician", "vaccine", "jab", "weights", "hiit", "steps",
        "exercise", "physical", "therapy", "counselling", "nurse",
    )
    private val HEALTH_PHRASES = listOf("5 a side", "five a side", "check up")
    private val SELF = setOf(
        "read", "reading", "book", "books", "library", "course", "study", "studying", "learning", "learn", "haircut", "barber",
        "hairdresser", "hairdressers", "spa", "cinema", "film", "movie", "theatre", "theater", "concert", "gig", "museum",
        "gallery", "golf", "guitar", "piano", "hobby", "meditate", "meditation", "journal", "journaling", "podcast", "chess",
        "gaming", "pub", "drinks", "friends", "mates", "lads",
    )
    private val SELF_PHRASES = listOf("me time")
    private val FAMILY = setOf(
        "birthday", "party", "anniversary", "playdate", "nativity", "bedtime", "grandma", "grandad", "granny", "nan", "mum",
        "dad", "wife", "husband", "children", "son", "daughter",
    )
    private val FAMILY_PHRASES = listOf("date night", "sports day", "parents evening", "school run", "family day")
    private val WORK = setOf(
        "meeting", "call", "standup", "interview", "deadline", "client", "workshop", "offsite", "conference", "webinar",
        "presentation", "review", "sync", "1on1", "appraisal",
    )
    private val WORK_PHRASES = listOf("1 1", "one to one", "stand up")

    /** Lower-case words of [text] (letters and digits), read by hand so Kotlin/Native's regexes can't differ. */
    fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun has(text: String, set: Set<String>, phrases: List<String>): Boolean {
        val ws = words(text)
        if (ws.any { it in set }) return true
        val joined = " " + ws.joinToString(" ") + " "
        return phrases.any { joined.contains(" $it ") }
    }

    /** The area a habit's title names (health or self only), or null. */
    fun habitArea(title: String): BalanceArea? = when {
        has(title, HEALTH, HEALTH_PHRASES) -> BalanceArea.HEALTH
        has(title, SELF, SELF_PHRASES) -> BalanceArea.SELF
        else -> null
    }

    /**
     * The area a timed event counts for: a child's session or a family event first, then health, self, and work words;
     * null for no clear area. [childOf] says whose club session or fixture it is.
     */
    fun area(e: CalendarEvent, childOf: (CalendarEvent) -> String?): BalanceArea? {
        val text = listOfNotNull(e.title, e.calendarTitle, e.calendarName).joinToString(" · ")
        return when {
            childOf(e) != null -> BalanceArea.FAMILY
            WeekAheadRules.isFamilyEvent(e) || has(text, FAMILY, FAMILY_PHRASES) -> BalanceArea.FAMILY
            has(e.title, HEALTH, HEALTH_PHRASES) -> BalanceArea.HEALTH
            has(e.title, SELF, SELF_PHRASES) -> BalanceArea.SELF
            has(e.title, WORK, WORK_PHRASES) -> BalanceArea.WORK
            else -> null
        }
    }

    /** "6 h 30", "8 h", "45 min", "0 h". */
    fun hours(min: Int): String = when {
        min <= 0 -> "0 h"
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${(min % 60).toString().padStart(2, '0')}"
    }

    private fun spokenHours(min: Int): String = when {
        min <= 0 -> "no time"
        min < 60 -> "$min minutes"
        min % 60 == 0 -> if (min == 60) "1 hour" else "${min / 60} hours"
        else -> (if (min / 60 == 1) "1 hour" else "${min / 60} hours") + " ${min % 60}"
    }

    /** Minutes covered by [spans] (start, end) after merging overlaps. */
    fun covered(spans: List<Pair<Long, Long>>): Long {
        var total = 0L
        var curStart = Long.MIN_VALUE
        var curEnd = Long.MIN_VALUE
        for ((s, e) in spans.filter { it.second > it.first }.sortedBy { it.first }) {
            if (s > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = s; curEnd = e
            } else if (e > curEnd) curEnd = e
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total / 60_000L
    }

    private fun overlap(a: Pair<Long, Long>, b: Pair<Long, Long>): Pair<Long, Long>? {
        val s = maxOf(a.first, b.first)
        val e = minOf(a.second, b.second)
        return if (e > s) s to e else null
    }

    /** The stored targets: area → minutes a week; an area absent (or 0 for work) uses its default. */
    fun targets(fields: Map<String, FieldValue>?): Map<BalanceArea, Int> = BalanceArea.entries.mapNotNull { a ->
        fields?.get(a.field)?.longOrNull?.toInt()?.takeIf { it in 30..100 * 60 }?.let { a to it }
    }.toMap()

    /** His own hours in the week starting [weekStart]: each work day's scheduled length. */
    fun scheduledMin(work: WorkHours, weekStart: Long): Int = (0..6).sumOf { i ->
        val day = weekStart + i
        if (!work.isWorkDay(day)) 0 else {
            val h = work.hoursOn(day)
            val len = h.endMinute - h.startMinute
            if (len > 0) len else len + LocalClock.MINUTES_PER_DAY
        }
    }

    /**
     * The week starting [weekStart] (a Monday) counted: what each area had, and the items that counted. [habits] are the
     * review's habits for that week (title, ticked Monday..Sunday).
     */
    fun count(
        weekStart: Long,
        nowMs: Long,
        cal: LocalCalendar,
        work: WorkHours,
        events: List<CalendarEvent>,
        habits: List<Pair<String, List<Boolean>>>,
        childOf: (CalendarEvent) -> String?,
    ): Pair<Map<BalanceArea, Int>, Pair<List<BalanceItem>, Int>> {
        val startMs = cal.toEpochMs(weekStart, 0)
        val endMs = minOf(cal.toEpochMs(weekStart + 7, 0), nowMs)
        if (endMs <= startMs) return BalanceArea.entries.associateWith { 0 } to (emptyList<BalanceItem>() to 0)
        val spans = BalanceArea.entries.associateWith { mutableListOf<Pair<Long, Long>>() }
        val items = mutableListOf<BalanceItem>()
        val areaDays = BalanceArea.entries.associateWith { mutableSetOf<Long>() }
        var uncounted = 0
        // Work blocks so far this week.
        val blocks = (0..6).flatMap { i -> work.blocks(weekStart + i, cal) }
            .mapNotNull { b -> overlap(b.startMs to b.endMs, startMs to endMs) }
        val nonWorkInBlocks = mutableListOf<Pair<Long, Long>>()
        for (e in events) {
            if (e.allDay || e.isFixture || e.endAtMs <= e.startAtMs) continue
            val span = overlap(e.startAtMs to e.endAtMs, startMs to endMs) ?: continue
            val area = area(e, childOf)
            if (area == null) { uncounted++; continue }
            val inBlocks = blocks.mapNotNull { overlap(it, span) }
            if (area == BalanceArea.WORK) {
                // Work words inside his hours are already counted by the hours; only the part outside them is added.
                val outside = ((span.second - span.first) / 60_000L - covered(inBlocks)).toInt()
                if (outside <= 0) continue
                spans.getValue(area) += span
                items += BalanceItem(area, e.title.trim(), cal.epochDayOf(span.first), outside)
                continue
            }
            nonWorkInBlocks += inBlocks
            spans.getValue(area) += span
            areaDays.getValue(area) += cal.epochDayOf(span.first)
            items += BalanceItem(area, e.title.trim(), cal.epochDayOf(span.first), ((span.second - span.first) / 60_000L).toInt())
        }
        val minutes = mutableMapOf<BalanceArea, Int>()
        val blockMin = covered(blocks).toInt()
        val outsideWork = covered(spans.getValue(BalanceArea.WORK)) - covered(spans.getValue(BalanceArea.WORK).flatMap { s -> blocks.mapNotNull { overlap(it, s) } })
        minutes[BalanceArea.WORK] = (blockMin - covered(nonWorkInBlocks).toInt() + outsideWork.toInt()).coerceAtLeast(0)
        for (a in listOf(BalanceArea.FAMILY, BalanceArea.HEALTH, BalanceArea.SELF)) minutes[a] = covered(spans.getValue(a)).toInt()
        // Habits ticked on days with no event in their area.
        val today = cal.epochDayOf(nowMs)
        for ((title, days) in habits) {
            val area = habitArea(title) ?: continue
            val each = if (area == BalanceArea.HEALTH) HABIT_HEALTH_MIN else HABIT_SELF_MIN
            days.forEachIndexed { i, on ->
                val day = weekStart + i
                if (!on || day > today || day in areaDays.getValue(area)) return@forEachIndexed
                areaDays.getValue(area) += day
                minutes[area] = minutes.getValue(area) + each
                items += BalanceItem(area, title.trim(), day, each)
            }
        }
        if (blockMin > 0) items += BalanceItem(BalanceArea.WORK, "Your work hours", -1, minutes.getValue(BalanceArea.WORK) - outsideWork.toInt())
        return minutes to (items to uncounted)
    }

    private fun itemLine(i: BalanceItem): String =
        if (i.day < 0) "${i.title} · ${hours(i.minutes)}"
        else "${i.title.take(40)} · ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(i.day) - 1]} · ${hours(i.minutes)}"

    /** The review's balance for the week starting [weekStart]. */
    fun view(
        weekStart: Long,
        nowMs: Long,
        cal: LocalCalendar,
        work: WorkHours,
        events: List<CalendarEvent>,
        habits: List<Pair<String, List<Boolean>>>,
        targets: Map<BalanceArea, Int>,
        childOf: (CalendarEvent) -> String? = { null },
        trendWeeks: List<Pair<Long, Map<BalanceArea, Int>>> = emptyList(),
    ): BalanceView {
        val today = cal.epochDayOf(nowMs)
        val isCurrent = today >= weekStart && today < weekStart + 7
        val (minutes, counted) = count(weekStart, nowMs, cal, work, events, habits, childOf)
        val (items, uncounted) = counted
        val ownHours = scheduledMin(work, weekStart)
        val rows = BalanceArea.entries.map { a ->
            val set = targets[a]
            val target = set ?: a.defaultMin ?: ownHours
            val n = minutes.getValue(a)
            val pct = if (target <= 0) (if (n > 0) 100 else 0) else (n * 100 / target).coerceIn(0, 100)
            val (status, lit) = when {
                a == BalanceArea.WORK && target > 0 && n > target -> "${hours(n - target)} over" to true
                a == BalanceArea.WORK -> (if (target == 0) null else if (isCurrent) "Within your hours so far" else "Within your hours") to false
                n >= target -> "Met" to true
                isCurrent -> "${hours(target - n)} to go" to false
                else -> "${hours(target - n)} short" to false
            }
            val line = when {
                a == BalanceArea.WORK && set == null -> if (target == 0) "${hours(n)} · no work hours that week" else "${hours(n)} of your ${hours(target)}"
                else -> "${hours(n)} of ${hours(target)}"
            }
            val mine = items.filter { it.area == a }.sortedWith(compareByDescending<BalanceItem> { it.minutes }.thenBy { it.day }.thenBy { it.title })
            val choices = if (a == BalanceArea.WORK) listOf(YOUR_HOURS) + a.choices else a.choices
            val (weeks, targetPct, trendLine) = trend(
                a, trendWeeks.map { (ws, m) -> ws to (if (ws == weekStart) n else m[a] ?: 0) },
                { ws -> set ?: a.defaultMin ?: scheduledMin(work, ws) }, today,
            )
            BalanceRow(
                a, a.label, n, target, pct, line, status, lit,
                mine.take(MAX_ITEMS).map(::itemLine), (mine.size - MAX_ITEMS).coerceAtLeast(0),
                choices, set == null,
                "${a.label}: ${spokenHours(n)} of ${spokenHours(target)}" + (status?.let { ", ${it.lowercase()}" } ?: "") +
                    (trendLine?.let { ". $it" } ?: ""),
                weeks, targetPct, trendLine,
            )
        }
        val others = rows.filter { it.area != BalanceArea.WORK }
        val met = others.count { it.minutes >= it.targetMin }
        val workRow = rows.first { it.area == BalanceArea.WORK }
        val line = listOfNotNull(
            "$met of ${others.size} targets met" + if (isCurrent) " so far" else "",
            workRow.status?.takeIf { workRow.lit }?.let { "work $it" },
        ).joinToString(" · ")
        val notCounted = uncounted.takeIf { it > 0 }?.let { ShutdownRules.count(it, "event") + " not counted · no clear area" }
        return BalanceView(weekStart, isCurrent, rows, line, SOURCES, notCounted)
    }

    /**
     * Slice 2: each area's minutes in the [TREND_WEEKS] weeks ending with the one starting [weekStart], oldest first.
     * [habitsFor] gives a week's habits (title, ticked Monday..Sunday) like the review's.
     */
    fun trendMinutes(
        weekStart: Long,
        nowMs: Long,
        cal: LocalCalendar,
        work: WorkHours,
        events: List<CalendarEvent>,
        habitsFor: (Long) -> List<Pair<String, List<Boolean>>>,
        childOf: (CalendarEvent) -> String?,
    ): List<Pair<Long, Map<BalanceArea, Int>>> = (TREND_WEEKS - 1 downTo 0).map { back ->
        val ws = weekStart - 7L * back
        ws to count(ws, nowMs, cal, work, events, habitsFor(ws), childOf).first
    }

    /** "12 Oct" for the week starting [weekStart]. */
    fun weekLabel(weekStart: Long): String {
        val d = CivilDate.fromEpochDay(weekStart)
        return "${d.day} ${Recurrence.MONTH_SHORT[d.month - 1]}"
    }

    /**
     * Slice 2: one area's trend from [weeks] (each week's start and minutes) against [targetOf] (a week's target), with
     * [today] for the week in progress. The line averages the finished weeks only.
     */
    fun trend(area: BalanceArea, weeks: List<Pair<Long, Int>>, targetOf: (Long) -> Int, today: Long): Triple<List<BalanceWeek>, Int, String?> {
        if (weeks.isEmpty()) return Triple(emptyList(), 0, null)
        val targetNow = targetOf(weeks.last().first)
        val scale = maxOf(weeks.maxOf { it.second }, targetNow, 1)
        val out = weeks.map { (ws, n) ->
            val t = targetOf(ws)
            val current = today >= ws && today < ws + 7
            val met = if (area == BalanceArea.WORK) t > 0 && n > t else t > 0 && n >= t
            BalanceWeek(ws, weekLabel(ws), n, (n * 100 / scale).coerceIn(0, 100), met, current)
        }
        if (out.all { it.minutes == 0 }) return Triple(out, (targetNow * 100 / scale).coerceIn(0, 100), null)
        val finished = out.filterNot { it.current }
        val metCount = out.count { it.met }
        val avg = finished.takeIf { it.isNotEmpty() }?.let { f -> (f.sumOf { it.minutes } / f.size / 15) * 15 }
        val line = listOfNotNull(
            "Last ${out.size} weeks",
            if (area == BalanceArea.WORK) (if (metCount == 0) "never over" else "over in $metCount")
            else (if (metCount == 0) "not met yet" else "met in $metCount"),
            avg?.let { "about ${hours(it)} a week" },
        ).joinToString(" · ")
        return Triple(out, (targetNow * 100 / scale).coerceIn(0, 100), line)
    }

    /** The line after setting a target: "Family: 10 h a week · on both devices". */
    fun setLine(area: BalanceArea, minutes: Int?): String =
        if (minutes == null) "${area.label}: ${if (area == BalanceArea.WORK) "your own hours" else hours(area.defaultMin ?: 0) + " a week"} · on both devices"
        else "${area.label}: ${hours(minutes)} a week · on both devices"
}

/** The balance targets on a replica. */
class BalanceTargets(private val replica: Replica) {
    fun targets(): Map<BalanceArea, Int> = BalanceRules.targets(replica.entity(EntityTypes.CONTEXT_MODE, BalanceRules.ENTITY_ID)?.fields)

    /**
     * Sets [area]'s target to [minutes] a week ([BalanceRules.YOUR_HOURS] or the area's default clears it); returns the
     * line to show, or null when it was already that (nothing written) or [minutes] isn't one of the area's choices.
     */
    fun set(area: BalanceArea, minutes: Int): String? {
        val clear = minutes == BalanceRules.YOUR_HOURS || minutes == area.defaultMin
        if (!clear && minutes !in area.choices) return null
        val now = targets()[area]
        if (clear && now == null) return null
        if (!clear && now == minutes) return null
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, BalanceRules.ENTITY_ID,
            mapOf(area.field to (if (clear) FieldValue.Null else minutes.toLong().fv())),
        )
        return BalanceRules.setLine(area, if (clear) null else minutes)
    }
}
