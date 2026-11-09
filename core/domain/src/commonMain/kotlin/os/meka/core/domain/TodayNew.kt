package os.meka.core.domain

/** One step on Today's evening card ([EveningCardRules]). */
enum class EveningStepKind { REVIEW, SHUT_DOWN, PLAN_TOMORROW }

/** A step of the evening: "Shut down the day · 1 left · move or drop them", ticked once [done]. */
data class EveningStep(val kind: EveningStepKind, val title: String, val line: String, val done: Boolean)

/** "This evening · 1 of 3" and its steps, in the order they are done. */
data class EveningCard(val label: String, val steps: List<EveningStep>, val doneCount: Int) {
    val allDone: Boolean get() = doneCount == steps.size
}

/** Today's own list head: "Today · 2 left · 45m", with the done bar's share of the day's length. */
data class TodayListHead(val label: String, val progress: Float, val doneLine: String?)

/**
 * The Today rebuild (Meka approved 2026-11-08 22:58, `docs/design/day-list/TodayNew.dc.html`), slice 2a: the day's tasks
 * near the top and one evening card instead of three. Non-AI and pure, shared by the Fold and the Mac.
 *
 * - **This evening** ([evening]): from 17:00 (or the evening's start when that is earlier, or once shut down: the
 *   Tomorrow peek's own window) one card right under Now with the evening's steps: Review your week (only while the
 *   weekly review is due or done that week), Shut down the day, Plan tomorrow; "This evening · 1 of 3". It takes the
 *   place of the separate review, shutdown and tomorrow cards. Not when he has switched the evening card off.
 * - **Later** on the card puts it away until [LATER_UNTIL_MIN] (21:00), offered while that is at least a quarter of
 *   an hour away ([laterOffered]).
 * - **Today's head** ([head]): "Today · 2 left · 45m" (what's still open and how long it adds up to; a task with no
 *   length counts [DayListRules.DEFAULT_MINUTES]), the gold bar's share done, and "1 of 3 done" under it.
 */
object EveningCardRules {
    const val LABEL = "This evening"
    const val REVIEW_DONE = "Done for this week"
    const val SHUT_DOWN = "Shut down the day"
    const val PLAN_TOMORROW = "Plan tomorrow"
    const val LATER = "Later"
    const val LATER_UNTIL_MIN = 21 * 60

    /** The card, or null outside the evening (no Tomorrow peek yet) or with the evening card switched off. */
    fun evening(shutdown: ShutdownView, review: ReviewCard): EveningCard? {
        val peek = shutdown.peek ?: return null
        if (shutdown.cardOff) return null
        val steps = listOfNotNull(
            reviewStep(review),
            EveningStep(EveningStepKind.SHUT_DOWN, SHUT_DOWN, shutDownLine(shutdown), shutdown.doneToday),
            EveningStep(EveningStepKind.PLAN_TOMORROW, PLAN_TOMORROW, peek.line.removePrefix("Tomorrow · "), shutdown.tomorrow.taskCount > 0),
        )
        val done = steps.count { it.done }
        return EveningCard("$LABEL · $done of ${steps.size}", steps, done)
    }

    private fun reviewStep(review: ReviewCard): EveningStep? {
        if (review.title.isEmpty() || !(review.offered || review.reviewed)) return null
        val line = if (review.reviewed) REVIEW_DONE else review.line.ifEmpty { "A few minutes on how it went" }
        return EveningStep(EveningStepKind.REVIEW, review.title, line, review.reviewed)
    }

    /** "Day shut down at 18:42" once done; else "2 left · move or drop them", or "Nothing left · close the day". */
    fun shutDownLine(s: ShutdownView): String = s.doneLine ?: when (val n = s.left.size) {
        0 -> "Nothing left · close the day"
        else -> "$n left · move or drop them"
    }

    /** "Later" puts the card away until 21:00; offered while that is at least 15 minutes ahead. */
    fun laterOffered(minute: Int): Boolean = minute <= LATER_UNTIL_MIN - 15

    /** The card is away: Later was tapped today and 21:00 hasn't come. */
    fun away(laterDay: Long?, today: Long, minute: Int): Boolean = laterDay == today && minute < LATER_UNTIL_MIN

    /** The card as drawn: [card] unless Later put it away ([away]). Slice 2d-2c: the Mac's one call, as the Fold's. */
    fun shown(card: EveningCard?, laterDay: Long?, today: Long, minute: Int): EveningCard? =
        card?.takeIf { !away(laterDay, today, minute) }

    /**
     * The weekly review for Also today's REVIEW tile: only while there's no evening card (Monday's "Review last week";
     * Sunday's review rides on the evening card as its first step). Null keeps the tile out.
     */
    fun reviewForTile(review: ReviewCard?, card: EveningCard?): ReviewCard? = review?.takeIf { card == null }

    /** Today's head over its own tasks: [open] the ones still to do, [done] those ticked today. */
    fun head(open: List<Task>, done: List<Task>): TodayListHead {
        val len = { t: Task -> t.estimateMinutes ?: DayListRules.DEFAULT_MINUTES }
        val openMin = open.sumOf(len)
        val doneMin = done.sumOf(len)
        val total = openMin + doneMin
        val label = when {
            open.isEmpty() && done.isEmpty() -> "Today"
            open.isEmpty() -> "Today · all done"
            else -> "Today · ${open.size} left · ${TaskEstimateRules.label(openMin)}"
        }
        val all = open.size + done.size
        return TodayListHead(
            label = label,
            progress = if (total > 0) doneMin.toFloat() / total else 0f,
            doneLine = if (done.isNotEmpty()) "${done.size} of $all done" else null,
        )
    }

    const val SCHEDULE = "Schedule"

    /**
     * The timeline's label once Today's own tasks head the screen: "Schedule · Work 09:00–17:30 · 2 events" for the
     * timeline's "Today · Work 09:00–17:30 · 2 events", so "Today" names the tasks only.
     */
    fun scheduleLabel(sectionLabel: String): String =
        if (sectionLabel.startsWith(TimelineRules.SECTION)) SCHEDULE + sectionLabel.removePrefix(TimelineRules.SECTION) else sectionLabel

    /** The inline add line under Today's tasks (it opens the Day list, where Enter adds one after another). */
    const val ADD_LINE = "+ Add for today · Enter for the next"
}
