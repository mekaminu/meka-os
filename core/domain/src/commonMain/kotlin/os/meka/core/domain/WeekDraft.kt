package os.meka.core.domain

/**
 * One faint card in Obi's draft of the week: a task he would put on [day], drawn after the day's own cards until Keep
 * all. [why] says where it comes from ("Carried 2 days", "Due Thu", "No day yet").
 */
data class GhostCard(val taskId: String, val title: String, val day: Long, val minutes: Int, val why: String) {
    /** The card's duration as the planner cards draw it: "0:30". */
    val duration: String get() = BoardPlannerRules.duration(minutes)

    /** Screen readers: "Obi's draft: Book MOT, about 30m, carried 2 days". */
    val spoken: String get() = "${WeekDraftRules.SPOKEN_PREFIX}$title, about ${TaskEstimateRules.label(minutes)}, ${why.replaceFirstChar { it.lowercaseChar() }}"
}

/**
 * Obi's draft of the week ([WeekDraftRules]): the week's plan ([WeekPlan], its suggested spread) shown on the Board as
 * faint cards, with Keep all · Change · Start over. Nothing moves until Keep all.
 */
data class WeekDraft(val plan: WeekPlan) {
    /** The bar's line: "✦ Obi's draft · 6 tasks over 4 days · office evenings kept light". */
    val line: String
        get() = if (isReplan) DayReplanRules.line(plan) else "${WeekDraftRules.MARK} ${WeekDraftRules.TITLE} · ${plan.spreadLine}"

    /** Part 1, slice 3: "I lost the afternoon, re-plan" — the rest of today, not the week ([DayReplanRules]). */
    val isReplan: Boolean get() = plan.replanDay != null

    /** What a voice reply or the bar's second line says: "Council tax on Thu 29, Book MOT on Sat 31 · Fri kept light". */
    val said: String get() = WeekDraftRules.said(this)
}

/**
 * Ada plans with you, part 1, slice 1 (Meka approved 2026-10-21 19:54): Obi drafts the week on the Board. Pure and
 * non-AI, the same on the Fold and the Mac; built on [PlanWeekRules]' suggested spread.
 *
 * - **Where it starts:** "Plan your week" on the Board, "plan my week" said to Obi, and Plan now on Sunday's Needs you
 *   card all draft the week instead of opening the four steps first.
 * - **Ghost cards:** each task in the spread is drawn faint (dashed edge, [GHOST_ALPHA]) at the end of the day it would
 *   go to, with ✦ and its duration; a task already on that day, done, or gone since the draft has no ghost ([ghostsOn]
 *   reads the live Board, so the faint cards keep up with his hands).
 * - **The bar** above the days: the draft's line and three buttons — **Keep all** (every move at once, one Undo puts
 *   every one back), **Change** (the four steps open on the Suggested spread with the draft's days), **Start over**
 *   (the draft goes and the four steps open from the start).
 * - A week with nothing to place has no draft ([draft] null): the steps open as before, with "Nothing left over".
 */
object WeekDraftRules {
    const val MARK = "✦"
    const val TITLE = "Obi's draft"
    const val KEEP_ALL = "Keep all"
    const val CHANGE = "Change"
    const val START_OVER = "Start over"
    const val SPOKEN_PREFIX = "Obi's draft: "
    /** How faint a ghost card is drawn. */
    const val GHOST_ALPHA = 0.55f
    /** What Obi says when "plan my week" drafts the week. */
    const val OPENING = "I've drafted your week on the Board. Keep all, change any of it, or start over."
    /** The undo bar after Start over. */
    const val DROPPED = "Draft put away · nothing moved"

    /** The draft for [plan], or null when the spread places nothing. */
    fun draft(plan: WeekPlan): WeekDraft? = if (plan.placing.isEmpty()) null else WeekDraft(plan)

    /**
     * The ghost cards on [day] from the live [board]: the draft's rows going there, in the spread's order, less any
     * task that is done, gone from the Board or already on that day.
     */
    fun ghostsOn(draft: WeekDraft?, board: WeekBoard, day: Long): List<GhostCard> {
        if (draft == null) return emptyList()
        val rows = draft.plan.placing.filter { it.chosen == day }
        if (rows.isEmpty()) return emptyList()
        val open = HashMap<String, Long?>()
        board.weeks.forEach { w ->
            w.days.forEach { d -> d.openCards.forEach { open[it.id] = d.epochDay } }
            w.lane.forEach { open.getOrPut(it.id) { null } }
        }
        return rows.filter { r -> open.containsKey(r.taskId) && open[r.taskId] != day }
            .map { r -> GhostCard(r.taskId, r.title, day, r.minutes, r.why) }
    }

    /** Every ghost on the Board's days, by day (for a whole-week view and the tests). */
    fun ghosts(draft: WeekDraft?, board: WeekBoard): Map<Long, List<GhostCard>> =
        board.days.associate { it.epochDay to ghostsOn(draft, board, it.epochDay) }.filterValues { it.isNotEmpty() }

    /** True while [draft] still has a ghost on the live [board] (when none is left, the bar goes by itself). */
    fun showing(draft: WeekDraft?, board: WeekBoard): Boolean =
        draft != null && board.days.any { ghostsOn(draft, board, it.epochDay).isNotEmpty() }

    /**
     * The short summary: the first two placements by day ("Council tax on Thu 29, Book MOT on Sat 31"), "and 3 more"
     * after, then the lightest day left with free time ("Fri kept light"), if any.
     */
    fun said(draft: WeekDraft): String {
        val p = draft.plan
        val placing = p.placing
        val firsts = placing.take(2).joinToString(", ") { r -> "${r.title} on ${p.dayLabel(r.chosen!!)}" }
        val more = (placing.size - 2).takeIf { it > 0 }?.let { " and $it more" } ?: ""
        val tail = if (draft.isReplan) "" else lightDay(draft)?.let { " · ${it.label} kept light" } ?: ""
        return firsts + more + tail
    }

    /** The first day after the first with free time, nothing new from the draft and not stacking up; null if none. */
    private fun lightDay(draft: WeekDraft): PlanDay? {
        val used = draft.plan.placing.mapNotNull { it.chosen }.toSet()
        return draft.plan.days.drop(1).firstOrNull { it.day !in used && it.freeLine != null && !it.stacking }
    }

    // ---- Part 1, slice 2: Obi talks the draft through when asked by voice ----

    /** Said when "plan my week" finds nothing to place: the four steps open as before. */
    const val NOTHING_SPOKEN = "There's nothing waiting to be placed this week, so I've opened Plan your week."
    /** The question part every spoken draft ends with. */
    const val SPOKEN_CLOSE = "Keep all, change any of it, or start over."

    /**
     * What Obi says aloud after "plan my week" said to him (Ada plans with you, part 1, slice 2; Meka 2026-10-21: "talked
     * through out loud if he asked by voice"): "I've drafted your week on the Board. Council tax on Thursday, and Book
     * MOT on Saturday, plus 3 more. I've kept Friday light. Keep all, change any of it, or start over." Days are said
     * whole ("today", "tomorrow", "on Thursday"); [NOTHING_SPOKEN] when there's no draft.
     */
    fun spoken(draft: WeekDraft?): String {
        if (draft == null) return NOTHING_SPOKEN
        val p = draft.plan
        val placing = p.placing
        val firsts = placing.take(2).map { r -> "${r.title.trim().trimEnd('.', '!', '?')} ${spokenDay(p, r.chosen!!)}" }
        val list = if (firsts.size == 2) "${firsts[0]}, and ${firsts[1]}" else firsts.single()
        val more = (placing.size - 2).takeIf { it > 0 }?.let { ", plus $it more" } ?: ""
        val light = lightDay(draft)?.let { " I've kept ${spokenDay(p, it.day).removePrefix("on ")} light." } ?: ""
        return "I've drafted your week on the Board. $list$more.$light $SPOKEN_CLOSE"
    }

    /** "today", "tomorrow" or "on Thursday" for [day] of [plan]. */
    fun spokenDay(plan: WeekPlan, day: Long): String = when (plan.day(day)?.label) {
        "Today" -> "today"
        "Tomorrow" -> "tomorrow"
        else -> "on ${CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(day) - 1]}"
    }
}
