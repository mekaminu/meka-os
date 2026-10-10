package os.meka.core.domain

/**
 * The Galaxy Watch's one screen once it is linked (build plan "Galaxy Watch", slice 2). The watch runs its own copy of
 * the day (its own key and replica, ADR-005 amendment 2026-10-10), so this reads the same "now" card the closed Fold's
 * cover screen and the Mac's menu bar show ([CoverNowRules]) and the running fast, and keeps only what a wrist can do
 * in one tap: Done and Tomorrow on a task, Went and Didn't go on a booked session, Start and End on a fast. Opening
 * things, calls and directions stay on the phone. Pure, no AI; nothing is stored.
 */
data class WatchHomeView(
    /** "UP NEXT" · "IN 12 MIN" · "NOW · ENDS 15:30" · "YOU'RE CLEAR" (upper case, as the watch's small label). */
    val label: String,
    /** The label is lit (accent): something is starting, happening or late. */
    val lit: Boolean,
    val title: String,
    /** "14:00–15:00 · Room 4" · "Anytime today"; null when there's nothing to say. */
    val line: String?,
    /** The watch's buttons for [title], in order: the first is the primary (filled) one. */
    val buttons: List<WatchButton>,
    /** "Then: Standup at 15:00"; null when there's nothing after it. */
    val thenLine: String?,
    val fast: WatchFast,
)

/** A button on the watch: what it says and what it does to the task or session it sits under. */
data class WatchButton(val action: NowAction, val label: String, val primary: Boolean, val targetId: String)

/**
 * The fast row: running ("Fasting · goal 16 h", the clock "14:05", "Goal 16 h · at 12:05", the ring's share) with End,
 * or resting (the eating window's line) with Start.
 */
data class WatchFast(
    val running: Boolean,
    val title: String,
    /** "14:05" (hours and minutes since it started; hours can pass 24); blank while resting. */
    val clock: String,
    val line: String,
    /** Share of the goal done, 0 to 1. */
    val progress: Float,
    val reached: Boolean,
    /** "Start fast" · "End fast". */
    val button: String,
    /** The fast [button] ends; null while resting (Start begins the plan's daily fast). */
    val fastId: String?,
)

object WatchHomeRules {
    /** What a wrist can do in one tap; anything else on the card stays on the phone. */
    val WATCH_ACTIONS = listOf(NowAction.DONE, NowAction.WENT, NowAction.TOMORROW, NowAction.DIDNT_GO)

    const val UNLINKED = "This watch was unlinked"
    const val UNLINKED_LINE = "Link it again: tap below, then type the new code in Ask → More → Watch on your phone"
    const val LINK_AGAIN = "Link again"
    const val NO_SERVER = "This watch doesn't know MEKA's server · install it again from the Mac (tools/install-watch.sh)"
    const val ASKING = "Getting a code…"
    const val TRY_AGAIN = "Try again"
    const val OFFLINE = "Offline · it syncs when the watch is back online"

    const val START_FAST = "Start fast"
    const val END_FAST = "End fast"
    const val NOT_FASTING = "Not fasting"

    fun label(a: NowAction): String = when (a) {
        NowAction.DONE -> "Done"
        NowAction.TOMORROW -> "Tomorrow"
        NowAction.WENT -> "Went"
        NowAction.DIDNT_GO -> "Didn't go"
        else -> ""
    }

    /** "14:05" for 14 h 5 m 9 s (the watch shows minutes; the seconds would keep its screen busy). */
    fun clock(elapsedMs: Long): String {
        val m = elapsedMs.coerceAtLeast(0) / 60_000L
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    fun fast(v: FastingView, nowMs: Long): WatchFast {
        val f = v.current ?: return WatchFast(
            running = false, title = NOT_FASTING, clock = "", line = v.windowLine.ifBlank { v.plan.line },
            progress = 0f, reached = false, button = START_FAST, fastId = null,
        )
        return WatchFast(
            running = true,
            title = f.title,
            clock = clock(nowMs - f.startedAtMs),
            line = f.dayLine ?: f.goalLine,
            progress = f.progress(nowMs),
            reached = f.reachedGoal || nowMs >= f.goalAtMs,
            button = END_FAST,
            fastId = f.id,
        )
    }

    fun view(now: NowView, fasting: FastingView, nowMs: Long): WatchHomeView {
        val target = now.task?.id ?: now.session?.habitId
        val offered = WATCH_ACTIONS.filter { now.offers(it) }.filter { a ->
            when (a) {
                NowAction.DONE, NowAction.TOMORROW -> now.task != null
                NowAction.WENT, NowAction.DIDNT_GO -> now.session != null
                else -> false
            }
        }
        val buttons = if (target == null) emptyList() else offered.mapIndexed { i, a ->
            WatchButton(a, label(a), primary = i == 0, targetId = if (a == NowAction.WENT || a == NowAction.DIDNT_GO) now.session!!.habitId else now.task!!.id)
        }
        return WatchHomeView(
            label = now.label.uppercase(),
            lit = now.lit || now.late,
            title = now.title,
            line = now.line,
            buttons = buttons,
            thenLine = now.thenLine,
            fast = fast(fasting, nowMs),
        )
    }

    /**
     * When the watch should look again with nothing synced in between: the next minute for a running fast's clock,
     * else the next minute too (the card's "In 12 min" counts down). Watches sleep their screens, so this only runs
     * while MEKA is in front.
     */
    fun nextTickMs(nowMs: Long): Long = (nowMs / 60_000L + 1) * 60_000L
}
