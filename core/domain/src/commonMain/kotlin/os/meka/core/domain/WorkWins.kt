package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Work wins log (V1, Meka approved 2026-10-10): on Friday at 16:00 MEKA asks "what went well this week?"; one typed
 * (or dictated) line at a time is kept as a dated list for reviews, and "this quarter" lists them week by week.
 * One synced entity type (ADR-008 addendum 2026-10-10): `win`, one line, a random id.
 *
 * The wins live in the weekly review (the Review tab, both apps): the reviewed week's wins with a field to add one,
 * and the quarter's list under them. Plain, non-AI rules; nothing leaves Meka's own synced data.
 */
object WinFields {
    const val TEXT = "text"
    /** The local epoch day the win belongs to (today, or that week's Friday when added while reviewing a past week). */
    const val DAY = "day"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** One win as stored. */
data class Win(val id: String, val text: String, val day: Long, val addedAtMs: Long)

/** One win as the review shows it: "Shipped the Q3 report" · "Fri 9 Oct". */
data class WinRow(val id: String, val text: String, val day: Long, val dayLabel: String, val spoken: String)

/** One week of the quarter's list: "Week of 5 Oct" with its wins, oldest first. */
data class WinsWeek(val weekStart: Long, val label: String, val rows: List<WinRow>)

data class WinsView(
    /** The reviewed week's wins, oldest first. */
    val week: List<WinRow>,
    /** "2 wins this week", or what to do when there are none. */
    val weekLine: String,
    /** The field's hint: "What went well this week?" (or "that week" for a past week). */
    val hint: String,
    /** "This quarter · Oct – Dec · 7 wins". */
    val quarterTitle: String,
    /** The quarter's weeks with wins, newest first. */
    val quarter: List<WinsWeek>,
    val quarterCount: Int,
    /** Friday from 16:00 to the end of Sunday with no win this week: the review lights its Wins section. */
    val asking: Boolean,
) {
    companion object {
        val EMPTY = WinsView(emptyList(), WinRules.NONE_THIS_WEEK, WinRules.HINT_THIS_WEEK, "This quarter", emptyList(), 0, false)
    }
}

/** What adding a line did: the line to show under the field and whether it was turned away (lit in the accent). */
data class WinAdded(val line: String, val refused: Boolean, val id: String? = null)

object WinRules {
    const val MAX_TEXT = 200
    /** Friday ([PROMPT_WEEKDAY]) at 16:00. */
    const val PROMPT_WEEKDAY = 5
    const val PROMPT_MIN = 16 * 60

    const val HINT_THIS_WEEK = "What went well this week?"
    const val HINT_PAST_WEEK = "What went well that week?"
    const val NONE_THIS_WEEK = "Nothing yet. A line or two on Friday is enough: kept for your reviews."
    const val NONE_PAST_WEEK = "No wins kept that week. Add one now and it's dated that Friday."
    const val NOT_READ = "Type what went well: “Shipped the Q3 report”."
    const val ALREADY = "Already in this week's wins"
    const val NOTICE_TITLE = "What went well this week?"
    const val NOTICE_TEXT = "A line for your wins · kept for your reviews"

    /** The calendar quarter [day] is in: its first and last epoch days and "Oct – Dec". */
    fun quarter(day: Long): Triple<Long, Long, String> {
        val d = CivilDate.fromEpochDay(day)
        val firstMonth = (d.month - 1) / 3 * 3 + 1
        val lastMonth = firstMonth + 2
        val start = CivilDate.toEpochDay(d.year, firstMonth, 1)
        val end = CivilDate.toEpochDay(d.year, lastMonth, CivilDate.lengthOfMonth(d.year, lastMonth))
        val m = Recurrence.MONTH_SHORT
        return Triple(start, end, "${m[firstMonth - 1]} – ${m[lastMonth - 1]}")
    }

    /** One typed line, tidied: spaces collapsed, cut at a word past [MAX_TEXT], the first letter raised; null when empty. */
    fun read(text: String): String? {
        var t = text.trim().replace(Regex("\\s+"), " ").trimStart('-', '•', '*', ' ')
        if (t.isEmpty()) return null
        if (t.length > MAX_TEXT) t = t.take(MAX_TEXT).substringBeforeLast(' ', t.take(MAX_TEXT)).trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    fun key(text: String): String = text.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!').lowercase()

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun wins(entities: Map<String, Map<String, FieldValue>>): List<Win> = entities.mapNotNull { (id, s) ->
        if (s.f(WinFields.DELETED).boolOrNull == true) return@mapNotNull null
        val text = s.f(WinFields.TEXT).textOrNull?.trim().orEmpty()
        val day = s.f(WinFields.DAY).longOrNull ?: return@mapNotNull null
        if (text.isEmpty()) return@mapNotNull null
        Win(id, text, day, s.f(WinFields.ADDED_AT).longOrNull ?: 0L)
    }.sortedWith(compareBy({ it.day }, { it.addedAtMs }, { it.id }))

    fun row(w: Win) = WinRow(w.id, w.text, w.day, CivilDate.shortLabel(w.day), "${CivilDate.longLabel(w.day)}: ${w.text}")

    /** The day a win added now lands on: today in the week in progress, else that week's Friday. */
    fun dayFor(weekStart: Long, today: Long): Long =
        if (today >= weekStart && today < weekStart + 7) today else weekStart + (PROMPT_WEEKDAY - 1)

    /** Friday from 16:00 through Sunday with nothing kept this week. */
    fun asking(wins: List<Win>, today: Long, minute: Int): Boolean {
        val dow = CivilDate.isoDayOfWeek(today)
        val inWindow = dow > PROMPT_WEEKDAY || (dow == PROMPT_WEEKDAY && minute >= PROMPT_MIN)
        val ws = GoalRules.weekStart(today)
        return inWindow && wins.none { it.day in ws until ws + 7 }
    }

    /** The review's wins for the week starting [weekStart], and the quarter [today] is in. */
    fun view(wins: List<Win>, weekStart: Long, today: Long, minute: Int): WinsView {
        val current = today >= weekStart && today < weekStart + 7
        val week = wins.filter { it.day in weekStart until weekStart + 7 }.map(::row)
        val weekLine = when {
            week.isNotEmpty() -> ShutdownRules.count(week.size, "win") + if (current) " this week" else " that week"
            current -> NONE_THIS_WEEK
            else -> NONE_PAST_WEEK
        }
        val (qStart, qEnd, qLabel) = quarter(today)
        val inQuarter = wins.filter { it.day in qStart..qEnd }
        val weeks = inQuarter.groupBy { GoalRules.weekStart(it.day) }.entries.sortedByDescending { it.key }.map { (ws, inWeek) ->
            WinsWeek(ws, "Week of " + CivilDate.fromEpochDay(ws).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }, inWeek.map(::row))
        }
        val title = "This quarter · $qLabel" + if (inQuarter.isEmpty()) "" else " · " + ShutdownRules.count(inQuarter.size, "win")
        return WinsView(week, weekLine, if (current) HINT_THIS_WEEK else HINT_PAST_WEEK, title, weeks, inQuarter.size, asking(wins, today, minute))
    }

    /** Adding a typed line to the week starting [weekStart]: refused when empty; the same line twice that week is kept once. */
    fun addPlan(
        text: String,
        weekStart: Long,
        today: Long,
        wins: List<Win>,
        nowMs: Long,
        newId: () -> String,
    ): Pair<WinAdded, Pair<String, Map<String, FieldValue>>?> {
        val t = read(text) ?: return WinAdded(NOT_READ, true) to null
        val day = dayFor(weekStart, today)
        val ws = GoalRules.weekStart(day)
        if (wins.any { it.day in ws until ws + 7 && key(it.text) == key(t) }) return WinAdded(ALREADY, false) to null
        val id = newId()
        val line = "Kept for ${if (day == today) "today" else CivilDate.shortLabel(day)}: $t"
        return WinAdded(line, false, id) to (id to mapOf(
            WinFields.TEXT to t.fv(),
            WinFields.DAY to day.fv(),
            WinFields.ADDED_AT to nowMs.fv(),
        ))
    }

    /**
     * The Friday question's heads-up (source [NoticeSource.WINS]) through the governor like every notice, so quiet
     * hours and Meka's tier apply: Friday at 16:00, opening the review, standing until Friday ends. Gone once a win is
     * kept for the week (the rules re-run each evaluation).
     */
    fun notices(wins: List<Win>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        val ws = GoalRules.weekStart(today)
        if (wins.any { it.day in ws until ws + 7 }) return emptyList()
        val friday = ws + (PROMPT_WEEKDAY - 1)
        if (today > friday) return emptyList()
        return listOf(Notice(
            key = "wins:$ws", source = NoticeSource.WINS, tier = NoticeTier.HEADS_UP,
            title = NOTICE_TITLE, text = NOTICE_TEXT,
            atMs = cal.toEpochMs(friday, PROMPT_MIN), target = NoticeTarget.REVIEW,
            expiresAtMs = cal.toEpochMs(friday + 1, 0),
        ))
    }
}

/** The wins log on a replica. */
class WorkWins(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long, private val cal: LocalCalendar) {
    fun all(): List<Win> = WinRules.wins(replica.entities(EntityTypes.WIN).associate { it.ref.entityId to it.fields })

    fun view(weekStart: Long): WinsView {
        val now = nowMs()
        return WinRules.view(all(), weekStart, cal.epochDayOf(now), cal.minuteOfDay(now))
    }

    /** Keeps one line for the week starting [weekStart] (today in the week in progress, else that Friday). */
    fun add(text: String, weekStart: Long): WinAdded {
        val now = nowMs()
        val (added, write) = WinRules.addPlan(text, weekStart, cal.epochDayOf(now), all(), now, newId)
        write?.let { (id, fields) -> replica.commitLocal(EntityTypes.WIN, id, fields) }
        return added
    }

    /** Takes a win off the list (both apps). */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.WIN, id)?.takeIf { it[WinFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.WIN, id, mapOf(WinFields.DELETED to true.fv()))
        return true
    }
}

/**
 * Ask and Talk know the wins log (Work wins, slice 2): "show my wins this quarter" is answered from the log, and "add a
 * win: closed the Henley deal" (typed, or said to Talk as the spoken 30-second note) becomes a card that keeps the line
 * on Meka's tap. Non-AI, pure. Wins are Meka's own words, so they never mark a question untrusted.
 */
object WinAskRules {
    /** How many wins go with a question (newest first), under the quarter's line. */
    const val ASK_ITEMS = 10

    /**
     * The log as MEKA describes it to the model (Ask's `wins` lines): first the quarter ("Wins · this quarter, Oct – Dec ·
     * 7 kept · 1 this week · the newest 10 below"), then the newest wins, each "Win · Fri 9 Oct · Closed the Henley deal".
     * Nothing at all while the log is empty (the model is told no wins lines means none kept yet).
     */
    fun askLines(wins: List<Win>, today: Long): List<String> {
        if (wins.isEmpty()) return emptyList()
        val (qStart, qEnd, qLabel) = WinRules.quarter(today)
        val inQuarter = wins.count { it.day in qStart..qEnd }
        val ws = GoalRules.weekStart(today)
        val thisWeek = wins.count { it.day in ws until ws + 7 }
        val newest = wins.sortedWith(compareByDescending<Win> { it.day }.thenByDescending { it.addedAtMs }).take(ASK_ITEMS)
        val head = if (inQuarter == 0) "Wins · none kept this quarter ($qLabel) · earlier ones below"
        else listOfNotNull(
            "Wins · this quarter, $qLabel · $inQuarter kept",
            "$thisWeek this week".takeIf { thisWeek > 0 },
            "the newest $ASK_ITEMS below".takeIf { wins.size > ASK_ITEMS },
        ).joinToString(" · ")
        return listOf(head) + newest.map { "Win · ${CivilDate.shortLabel(it.day)} · ${it.text.replace(Regex("""\s+"""), " ").trim()}" }
    }

    /**
     * The line a model's `add_win` would keep: tidied like a typed one ([WinRules.read]); null when there's nothing to
     * keep or the same line is already in [today]'s week (no card for what's already there).
     */
    fun text(wins: List<Win>, title: String?, today: Long): String? {
        val t = WinRules.read(title ?: return null) ?: return null
        val ws = GoalRules.weekStart(today)
        return t.takeIf { wins.none { w -> w.day in ws until ws + 7 && WinRules.key(w.text) == WinRules.key(t) } }
    }

    /** The card's words: "Keep in this week's wins · “Closed the Henley deal”". */
    fun cardLine(text: String): String = "Keep in this week's wins · “$text”"

    /** The undo bar's line: "Kept “Closed the Henley deal” in this week's wins". */
    fun doneLine(text: String): String = "Kept “$text” in this week's wins"
}
