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
    /** Inbox autopilot, slice 7b-2: how many emails wait in "From your mail"; null when none (or no mail connected). */
    val inbox: WatchInbox? = null,
    /** Car and parking, slice 7: each car's parking timer that still shows (running first by when it ends). */
    val parking: List<WatchParking> = emptyList(),
)

/**
 * Car and parking, slice 7: a car's parking timer on the wrist. The timer is synced (it holds no location), so the
 * watch's own copy of the day has it; where the car is parked stays on the Fold. One button, +15 min, while it runs.
 */
data class WatchParking(
    val carId: String,
    /** "PARKING" · "PARKING · THE GOLF". */
    val label: String,
    /** The last [ParkingTimerRules.REMIND_MIN] minutes, or ended: the accent. */
    val lit: Boolean,
    val ended: Boolean,
    /** "Paid until 15:30 · 40 min left" · "Ended 15:30 · 5 min ago". */
    val line: String,
    /** "RingGo 81234", or null. */
    val note: String?,
    /** "+15 min" while it runs; null once ended. */
    val button: String?,
    /** The tile's and complication's short line: "Parking · 40 min left" · "The Golf · 40 min left". */
    val short: String,
    /** "40 min", "1 h 20" (at most 7 characters), for the complication; "Ended" once ended. */
    val left: String,
    /** Share of the paid time still left, 0 to 1. */
    val leftShare: Float,
    val endsAtMs: Long,
    val spoken: String,
)

/**
 * The watch's inbox count (Inbox autopilot, slice 7b-2): the wrist only shows how many emails need him, never what they
 * say; going through them stays on the phone and the Mac (the ten-minute inbox).
 */
data class WatchInbox(
    /** "INBOX" · "TEN-MINUTE INBOX" (from the moment's time until Not today or a clear inbox). */
    val label: String,
    /** The ten-minute inbox's moment has come: the label is lit. */
    val lit: Boolean,
    /** "6 need you" · "6 need you · on your phone". */
    val line: String,
    /** "Inbox clear 4 days running" while the moment is lit, else null. */
    val streakLine: String?,
    /** The tile's one line: "Inbox · 6 need you". */
    val short: String,
    val spoken: String,
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
    const val NO_SERVER = "This watch doesn't know Navilife's server · install it again from the Mac (tools/install-watch.sh)"
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

    const val INBOX = "INBOX"
    const val MOMENT = "TEN-MINUTE INBOX"
    const val ON_PHONE = "on your phone"

    /**
     * The inbox count for the wrist: [waiting] is the number of rows in "From your mail" (linked emails count once, as
     * in the ten-minute inbox); [moment] is today's Ten-minute inbox card while it is up. Null when nothing waits.
     */
    fun inbox(waiting: Int, moment: MailMomentCard?): WatchInbox? {
        if (waiting <= 0) return null
        val count = "$waiting need you"
        val emails = if (waiting == 1) "1 email needs you" else "$waiting emails need you"
        return if (moment != null) {
            WatchInbox(
                label = MOMENT, lit = true, line = "$count · $ON_PHONE", streakLine = moment.streakLine,
                short = "Ten-minute inbox · $count",
                spoken = "Ten-minute inbox. $emails. Go through them on your phone.",
            )
        } else {
            WatchInbox(
                label = INBOX, lit = false, line = count, streakLine = null,
                short = "Inbox · $count",
                spoken = "Inbox. $emails.",
            )
        }
    }

    const val PARKING = "PARKING"
    /** The watch's one extend button, in minutes (the phone and the Mac offer more). */
    const val EXTEND_MIN = 15

    /**
     * Car and parking, slice 7: the timers that still show ([TimersView.rows], made at [nowMs]) for the wrist, running
     * ones first by when they end, then ended ones. With one car the label is just PARKING.
     */
    fun parking(timers: TimersView, nowMs: Long): List<WatchParking> =
        timers.rows.sortedWith(compareBy<TimerRow>({ it.ended }, { it.timer.endsAtMs })).map { r ->
            val named = r.title != ParkingRules.TITLE
            val who = if (named) r.title.replaceFirstChar { it.titlecaseChar() } else "Parking"
            val leftMs = r.timer.endsAtMs - nowMs
            val total = (r.timer.endsAtMs - r.timer.startedAtMs).coerceAtLeast(1L)
            val left = if (r.ended) "Ended" else ParkingTimerRules.leftText(leftMs)
            WatchParking(
                carId = r.carId,
                label = if (named) "$PARKING · ${r.title.uppercase()}" else PARKING,
                lit = r.lit,
                ended = r.ended,
                line = r.line,
                note = r.note,
                button = if (r.ended) null else ParkingTimerRules.extendLabel(EXTEND_MIN),
                short = if (r.ended) "$who · ended ${r.endText}" else "$who · $left left",
                left = left.take(7),
                leftShare = if (r.ended) 0f else (leftMs.toFloat() / total).coerceIn(0f, 1f),
                endsAtMs = r.timer.endsAtMs,
                spoken = (if (named) "Parking, ${r.title}. " else "Parking. ") + r.line.replace(" · ", ", ") + ".",
            )
        }

    fun view(
        now: NowView, fasting: FastingView, nowMs: Long, inbox: WatchInbox? = null,
        parking: List<WatchParking> = emptyList(),
    ): WatchHomeView {
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
            inbox = inbox,
            parking = parking,
        )
    }

    /**
     * When the watch should look again with nothing synced in between: the next minute for a running fast's clock,
     * else the next minute too (the card's "In 12 min" counts down). Watches sleep their screens, so this only runs
     * while MEKA is in front.
     */
    fun nextTickMs(nowMs: Long): Long = (nowMs / 60_000L + 1) * 60_000L
}
