package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Weekly reflection (V1, Meka approved 2026-10-12): on Sunday, before the week ahead, a two-minute look back in the
 * weekly review — what went well, what to drop, one thing that matters next week — typed or dictated, kept as a dated
 * journal. Each field is pre-filled with suggestions from the week's own facts (wins, done tasks, habits met, what is
 * still open, the goals) that a tap adds to it; nothing is inferred from free text and nothing leaves Meka's data.
 *
 * Saving feeds the rest: what went well can also be kept in the work wins log (one win per line, the same line kept
 * once), the one thing can make a goal one of next week's goals (Goals' weekly pick, up to three), and the week is
 * marked reviewed so Today's Sunday card goes. "Skip this week" puts it away for that week only.
 *
 * One synced entity type (ADR-008 addendum 2026-10-15): `reflection`, one per week, id `w<the week's Monday as an
 * epoch day>`, every field last-writer-wins (a removed reflection comes back with Undo, so removal isn't TrueWins).
 */
object ReflectionFields {
    /** The week's Monday (local epoch day). */
    const val WEEK = "week"
    const val WENT_WELL = "wentWell"
    const val DROP = "drop"
    const val MATTERS = "matters"
    const val SKIPPED = "skipped"
    const val SAVED_AT = "savedAtMs"
    const val REMOVED = "removed"
}

/** One week's reflection as stored. */
data class Reflection(
    val weekStart: Long,
    val wentWell: String,
    val drop: String,
    val matters: String,
    val skipped: Boolean,
    val savedAtMs: Long,
) {
    val written: Boolean get() = !skipped && (wentWell.isNotEmpty() || drop.isNotEmpty() || matters.isNotEmpty())
}

/** A suggestion under a field: tap to add [text] to it. For the one thing, a goal's chip carries its [goalId]. */
data class ReflectionHint(val text: String, val goalId: String? = null)

/** One past week in the journal: "Week of 5 Oct", the three answers (empty ones left out), and a spoken line. */
data class ReflectionEntry(
    val weekStart: Long,
    val label: String,
    val wentWell: String,
    val drop: String,
    val matters: String,
    val savedLine: String,
    val spoken: String,
)

data class ReflectionView(
    /** The review's week (its Monday). */
    val weekStart: Long,
    /** "Reflect on your week" (this week) or "Reflect on that week". */
    val title: String,
    /** "Two minutes: what went well, what to drop, one thing that matters next week." or the saved/skipped line. */
    val line: String,
    /** The week in facts: "12 done · 3 of 4 habits met · 2 wins"; "A quiet week…" when nothing is counted. */
    val facts: String,
    val wentWellPrompt: String,
    val dropPrompt: String,
    val mattersPrompt: String,
    /** What was saved for the week (empty before), to fill the fields for an edit. */
    val wentWell: String,
    val drop: String,
    val matters: String,
    val wentWellHints: List<ReflectionHint>,
    val dropHints: List<ReflectionHint>,
    val mattersHints: List<ReflectionHint>,
    /** Saved for this week (and not skipped or removed). */
    val saved: Boolean,
    val skipped: Boolean,
    /** Sunday evening through Monday (the review card's window) with nothing saved or skipped: open and lit. */
    val asking: Boolean,
    /** "Your reflections · 4 weeks"; the journal, newest first (the week on screen left out). */
    val journalTitle: String,
    val journal: List<ReflectionEntry>,
    /** Slice 3: "Comes on Sundays at 18:00 · through Monday", with Change offering [timeChoices]. */
    val whenLine: String = ReviewRules.whenLine(ReviewRules.CARD_START_MIN),
    /** The Sunday start (minutes after midnight) shared with the review card. */
    val startMinute: Int = ReviewRules.CARD_START_MIN,
    val timeChoices: List<Int> = ReviewRules.timeChoices(ReviewRules.CARD_START_MIN),
    /** "16:00", "17:00", … for [timeChoices], in order. */
    val timeLabels: List<String> = timeChoices.map { LocalClock.formatMinute(it) },
    /** Week board item (8), the Friday review: planned against done on the Board, and what kept slipping. */
    val board: BoardWeekReview = BoardWeekReview.EMPTY,
) {
    companion object {
        val EMPTY = ReflectionView(
            0, ReflectionRules.TITLE_THIS_WEEK, ReflectionRules.INTRO, "", ReflectionRules.WENT_WELL, ReflectionRules.DROP_PROMPT,
            ReflectionRules.MATTERS_THIS_WEEK, "", "", "", emptyList(), emptyList(), emptyList(), false, false, false,
            ReflectionRules.JOURNAL, emptyList(),
        )
    }
}

/**
 * Slice 2: the one thing that matters this week, from last week's reflection, on Today's header ("This week's one
 * thing · Run 5k", lit on Monday) and in the brief ([ReflectionRules.briefSentence]).
 */
data class ReflectionWeekLine(val text: String, val spoken: String, val lit: Boolean)

/** What Save said: the line under the fields, and whether it was turned away (lit in the accent). */
data class ReflectionSaved(val line: String, val refused: Boolean)

/** Pure rules, unit-tested without a replica. */
object ReflectionRules {
    const val TITLE_THIS_WEEK = "Reflect on your week"
    const val TITLE_PAST_WEEK = "Reflect on that week"
    const val INTRO = "Two minutes: what went well, what to drop, one thing that matters next week."
    const val WENT_WELL = "What went well?"
    const val DROP_PROMPT = "What to drop?"
    const val MATTERS_THIS_WEEK = "One thing that matters next week"
    const val MATTERS_PAST_WEEK = "One thing that mattered for the week after"
    const val QUIET_WEEK = "A quiet week: nothing ticked off or kept. What would make next week better?"
    const val EMPTY_REFUSED = "Write a line in any of the three · one is enough"
    const val SKIPPED_LINE = "Skipped this week · Reflect now brings it back"
    const val JOURNAL = "Your reflections"
    const val MAX_FIELD = 600
    const val MAX_LINES = 6
    const val MAX_HINTS = 4
    const val MAX_JOURNAL = 52

    fun entityId(weekStart: Long): String = "w$weekStart"

    /** A field tidied: each line's spaces folded, bullets dropped and first letter raised, blank lines out, at most [MAX_LINES], cut at a word past [MAX_FIELD]. */
    fun tidy(text: String): String {
        val lines = text.split('\n').map { l ->
            l.trim().replace(Regex("\\s+"), " ").trimStart('-', '•', '*', ' ').replaceFirstChar { it.uppercase() }
        }.filter { it.isNotEmpty() }.take(MAX_LINES)
        var t = lines.joinToString("\n")
        if (t.length > MAX_FIELD) t = t.take(MAX_FIELD).substringBeforeLast(' ', t.take(MAX_FIELD)).trim()
        return t
    }

    /** A field with [hint] added on a new line, unless it is already there. */
    fun withHint(field: String, hint: String): String {
        val lines = field.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { WinRules.key(it) == WinRules.key(hint) }) return field
        return (lines + hint).joinToString("\n")
    }

    /** "12 done · 3 of 4 habits met · 2 wins · 2 still open", or [QUIET_WEEK]. */
    fun facts(done: Int, habitsMet: Int, habits: Int, wins: Int, stillOpen: Int): String = listOfNotNull(
        "$done done".takeIf { done > 0 },
        "$habitsMet of ${ShutdownRules.count(habits, "habit")} met".takeIf { habits > 0 },
        ShutdownRules.count(wins, "win").takeIf { wins > 0 },
        "$stillOpen still open".takeIf { stillOpen > 0 },
    ).joinToString(" · ").ifEmpty { QUIET_WEEK }

    /** What went well: the week's wins, then habits that met their week, then the latest done tasks; [MAX_HINTS] at most. */
    fun wentWellHints(v: WeeklyReviewView, winTexts: List<String>): List<ReflectionHint> = distinct(
        winTexts + v.habits.filter { it.met && it.target > 0 }.map { "${it.title} · ${it.done} of ${it.target}" } + v.done.map { it.title },
    ).map { ReflectionHint(it) }

    /** What to drop: what is still open from the week, then habits that missed it. */
    fun dropHints(v: WeeklyReviewView, slipping: List<String> = emptyList()): List<ReflectionHint> = distinct(
        slipping + v.stillOpen.map { it.title } + v.habits.filter { !it.met && !v.isCurrent }.map { it.title } +
            v.habits.filter { !it.met && v.isCurrent && it.done == 0 }.map { it.title },
    ).map { ReflectionHint(it) }

    /** The one thing: next week's picked goals first, then the other open goals; each chip names its goal. */
    fun mattersHints(goals: List<GoalItem>, nextWeekStart: Long): List<ReflectionHint> {
        val open = goals.filter { !it.done }
        val ordered = open.filter { it.weekOf == nextWeekStart } + open.filter { it.weekOf != nextWeekStart }
        return ordered.distinctBy { WinRules.key(it.title) }.take(MAX_HINTS).map { ReflectionHint(it.title, it.id) }
    }

    private fun distinct(texts: List<String>): List<String> =
        texts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { WinRules.key(it) }.take(MAX_HINTS)

    /** Sunday evening through Monday for the card's week, with nothing saved or skipped for it. */
    fun asking(card: ReviewCard, weekStart: Long, r: Reflection?, today: Long, minute: Int): Boolean =
        ReviewRules.cardWindow(today, minute, card.startMinute) && card.weekStart == weekStart && (r == null || !(r.written || r.skipped))

    fun savedLine(savedAtMs: Long, cal: LocalCalendar): String =
        "Saved ${CivilDate.shortLabel(cal.epochDayOf(savedAtMs))} at ${LocalClock.formatMinute(cal.minuteOfDay(savedAtMs))}"

    fun weekLabel(weekStart: Long): String =
        "Week of " + CivilDate.fromEpochDay(weekStart).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }

    fun journalTitle(n: Int): String = if (n == 0) JOURNAL else "$JOURNAL · ${ShutdownRules.count(n, "week")}"

    fun entry(r: Reflection, cal: LocalCalendar): ReflectionEntry {
        val label = weekLabel(r.weekStart)
        val spoken = listOfNotNull(
            label,
            r.wentWell.takeIf { it.isNotEmpty() }?.let { "Went well: " + it.replace("\n", ", ") },
            r.drop.takeIf { it.isNotEmpty() }?.let { "To drop: " + it.replace("\n", ", ") },
            r.matters.takeIf { it.isNotEmpty() }?.let { "Mattered next: " + it.replace("\n", ", ") },
        ).joinToString(". ")
        return ReflectionEntry(r.weekStart, label, r.wentWell, r.drop, r.matters, savedLine(r.savedAtMs, cal), spoken)
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun reflections(entities: Map<String, Map<String, FieldValue>>): List<Reflection> = entities.mapNotNull { (_, s) ->
        if (s.f(ReflectionFields.REMOVED).boolOrNull == true) return@mapNotNull null
        val week = s.f(ReflectionFields.WEEK).longOrNull ?: return@mapNotNull null
        Reflection(
            weekStart = week,
            wentWell = s.f(ReflectionFields.WENT_WELL).textOrNull.orEmpty(),
            drop = s.f(ReflectionFields.DROP).textOrNull.orEmpty(),
            matters = s.f(ReflectionFields.MATTERS).textOrNull.orEmpty(),
            skipped = s.f(ReflectionFields.SKIPPED).boolOrNull == true,
            savedAtMs = s.f(ReflectionFields.SAVED_AT).longOrNull ?: 0L,
        )
    }.sortedByDescending { it.weekStart }

    /** The reflection section for the review [v] shows. */
    fun view(
        v: WeeklyReviewView,
        all: List<Reflection>,
        winTexts: List<String>,
        goals: List<GoalItem>,
        today: Long,
        minute: Int,
        cal: LocalCalendar,
        /** Slice 2: the week "reflect on my week" was said to Talk for: open and lit like Sunday evening's until saved. */
        requestedWeek: Long? = null,
        /** Week board item (8): the Board's week ([BoardReviewRules.review]); what kept slipping leads What to drop. */
        board: BoardWeekReview = BoardWeekReview.EMPTY,
    ): ReflectionView {
        val ws = v.weekStart
        val r = all.firstOrNull { it.weekStart == ws }
        val saved = r?.written == true
        val skipped = r?.skipped == true
        val journal = all.filter { it.written && it.weekStart != ws }.take(MAX_JOURNAL).map { entry(it, cal) }
        val line = when {
            saved -> savedLine(r!!.savedAtMs, cal)
            skipped -> SKIPPED_LINE
            else -> INTRO
        }
        return ReflectionView(
            weekStart = ws,
            title = if (v.isCurrent) TITLE_THIS_WEEK else TITLE_PAST_WEEK,
            line = line,
            facts = facts(v.done.size + v.doneMore, v.habits.count { it.met }, v.habits.size, winTexts.size, v.stillOpen.size + v.stillOpenMore),
            wentWellPrompt = WENT_WELL,
            dropPrompt = DROP_PROMPT,
            mattersPrompt = if (ws + 7 > today) MATTERS_THIS_WEEK else MATTERS_PAST_WEEK,
            wentWell = if (saved) r!!.wentWell else "",
            drop = if (saved) r!!.drop else "",
            matters = if (saved) r!!.matters else "",
            wentWellHints = wentWellHints(v, winTexts),
            dropHints = dropHints(v, board.slipping.map { it.title }),
            mattersHints = mattersHints(goals, ws + 7),
            saved = saved,
            skipped = skipped,
            asking = asking(v.card, ws, r, today, minute) || (requestedWeek == ws && !saved && !skipped),
            journalTitle = journalTitle(journal.size),
            journal = journal,
            whenLine = ReviewRules.whenLine(v.card.startMinute),
            startMinute = v.card.startMinute,
            timeChoices = ReviewRules.timeChoices(v.card.startMinute),
            board = board,
        )
    }

    /**
     * Saving: refused when all three are empty; otherwise the fields to write and the line to say. [winsAdded] and
     * [goalPicked] are what the caller did alongside (the went-well lines kept as wins, the one thing's goal picked).
     */
    fun savePlan(wentWell: String, drop: String, matters: String, weekStart: Long, nowMs: Long): Map<String, FieldValue>? {
        val w = tidy(wentWell)
        val d = tidy(drop)
        val m = tidy(matters)
        if (w.isEmpty() && d.isEmpty() && m.isEmpty()) return null
        return mapOf(
            ReflectionFields.WEEK to weekStart.fv(),
            ReflectionFields.WENT_WELL to w.fv(),
            ReflectionFields.DROP to d.fv(),
            ReflectionFields.MATTERS to m.fv(),
            ReflectionFields.SKIPPED to false.fv(),
            ReflectionFields.REMOVED to false.fv(),
            ReflectionFields.SAVED_AT to nowMs.fv(),
        )
    }

    /** "Kept in your reflections · 2 added to your wins · “Run 5k” is one of next week's goals". */
    fun savedSay(winsAdded: Int, goalTitle: String?, nextWeek: Boolean): String = listOfNotNull(
        "Kept in your reflections",
        winsAdded.takeIf { it > 0 }?.let { if (it == 1) "1 added to your wins" else "$it added to your wins" },
        goalTitle?.let { "“$it” is one of ${if (nextWeek) "next" else "that"} week's goals" },
    ).joinToString(" · ")

    /** The went-well lines that go to the wins log: each line, as a win would read it. */
    fun winLines(wentWell: String): List<String> = tidy(wentWell).split('\n').mapNotNull { WinRules.read(it) }

    // ---- Slice 2: the one thing through the week, and "reflect on my week" said to Talk ----

    const val WEEK_LINE = "This week's one thing"
    const val MAX_WEEK_LINE = 60
    const val OPENING = "Here's your week. Two minutes: what went well, what to drop, and one thing that matters next week."
    const val OPENED = "Opened the weekly reflection."

    /**
     * The week's one thing for Today's header: the first line of what last week's reflection said matters (the
     * reflection for the week before [today]'s, saved on its Sunday evening or the Monday), from Monday until Sunday's
     * reflection opens ([start], 18:00 unless set), when next week's is asked instead. Lit on the Monday. Null when
     * last week had none.
     */
    fun weekLine(all: List<Reflection>, today: Long, minute: Int, start: Int = ReviewRules.CARD_START_MIN): ReflectionWeekLine? {
        val dow = CivilDate.isoDayOfWeek(today)
        if (dow == 7 && minute >= start) return null
        val monday = today - (dow - 1)
        val r = all.firstOrNull { it.weekStart == monday - 7 && it.written } ?: return null
        val thing = r.matters.split('\n').map { it.trim().trimEnd('.', '!', ';', ',') }.firstOrNull { it.isNotEmpty() } ?: return null
        val short = if (thing.length <= MAX_WEEK_LINE) thing else thing.take(MAX_WEEK_LINE).substringBeforeLast(' ', thing.take(MAX_WEEK_LINE)).trimEnd() + "…"
        return ReflectionWeekLine("$WEEK_LINE · $short", "The one thing that matters this week: $thing.", lit = dow == 1)
    }

    /**
     * The brief's sentence for [line]: on Monday whatever its length, on the other weekdays Standard and Full only
     * (Short keeps to the day); never at the weekend (the brief there is lighter).
     */
    fun briefSentence(line: ReflectionWeekLine?, length: BriefLength, today: Long): String? {
        val dow = CivilDate.isoDayOfWeek(today)
        if (line == null || dow >= 6) return null
        return line.spoken.takeIf { dow == 1 || length.extras }
    }

    /** The goal to pick: the chip's goal, when its title is still in the one thing's words. */
    fun goalToPick(matters: String, goalId: String?, goals: List<GoalItem>): GoalItem? {
        val g = goals.firstOrNull { it.id == goalId && !it.done } ?: return null
        return g.takeIf { WinRules.key(matters).contains(WinRules.key(g.title)) }
    }
}

/** The reflections on a replica. */
class Reflections(private val replica: Replica, private val nowMs: () -> Long) {
    fun all(): List<Reflection> =
        ReflectionRules.reflections(replica.entities(EntityTypes.REFLECTION).associate { it.ref.entityId to it.fields })

    /** Writes the week's reflection; null when all three were empty (nothing written). */
    fun save(weekStart: Long, wentWell: String, drop: String, matters: String): Boolean {
        val fields = ReflectionRules.savePlan(wentWell, drop, matters, weekStart, nowMs()) ?: return false
        replica.commitLocal(EntityTypes.REFLECTION, ReflectionRules.entityId(weekStart), fields)
        return true
    }

    /** "Skip this week" (true) or "Reflect now" after a skip (false). */
    fun skip(weekStart: Long, on: Boolean) {
        replica.commitLocal(
            EntityTypes.REFLECTION, ReflectionRules.entityId(weekStart),
            mapOf(ReflectionFields.WEEK to weekStart.fv(), ReflectionFields.SKIPPED to on.fv(), ReflectionFields.REMOVED to false.fv()),
        )
    }

    /** Takes a week's reflection out of the journal (true), or puts it back (Undo, false). */
    fun remove(weekStart: Long, removed: Boolean): Boolean {
        replica.entity(EntityTypes.REFLECTION, ReflectionRules.entityId(weekStart)) ?: return false
        replica.commitLocal(EntityTypes.REFLECTION, ReflectionRules.entityId(weekStart), mapOf(ReflectionFields.REMOVED to removed.fv()))
        return true
    }
}
