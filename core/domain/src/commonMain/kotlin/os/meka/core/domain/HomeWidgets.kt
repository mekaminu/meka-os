package os.meka.core.domain

/**
 * The Fold's home-screen widgets (Outside the app, slice 2): **Next up**, **Needs you** and **Fast**. Non-AI and pure
 * (nothing stored), read from the views MEKA already has, so a widget says exactly what the app says.
 *
 * A widget can't redraw itself every minute, so anything that moves with the clock is a target the launcher ticks
 * itself (a countdown to an event's start, a fast's timer counting up), and [HomeWidgetsView.nextChangeMs] says when
 * the words themselves change (an event coming into the hour, starting, ending; a fast's goal; midnight), never more
 * than [HomeWidgetRules.MAX_WAIT_MIN] minutes away so a planned task passing or a task falling overdue shows up soon.
 */
data class HomeWidgetsView(
    val next: WidgetNext,
    val needsYou: WidgetNeedsYou,
    val fast: WidgetFast,
    /** When to look again even if nothing is edited (a soft time: within a few minutes is fine, ADR-007). */
    val nextChangeMs: Long,
)

/** Next up: the "now" card's one thing, without its buttons (a tap opens Today). */
data class WidgetNext(
    val kind: NowKind,
    /** "Starts in" (followed by [countdownToMs] ticking) · "Now · ends 15:30" · "Up next" · "You're clear" */
    val label: String,
    /** Something is starting or happening: the label in the accent colour. */
    val lit: Boolean,
    /** The event or task title; "Nothing else planned today" when clear. */
    val title: String,
    /** "14:00–15:00 · Room 4" · "At 14:00 · 30 min" · "Anytime today"; null when there's nothing to say. */
    val line: String?,
    /** Set for an event that hasn't started: the launcher counts down to it. */
    val countdownToMs: Long?,
    /** "Then: Standup at 15:00"; null when there's no second thing. */
    val thenLine: String?,
)

/** Needs you: how many decisions wait, and the first of them. */
data class WidgetNeedsYou(
    val count: Int,
    /** "3", or "9+" past nine (the tab's badge rule). Empty at zero. */
    val countText: String,
    /** "need you" · "needs you" · "Nothing needs you" */
    val label: String,
    /** The top card's title ("Send the invoice", "From your lists"); null at zero. */
    val top: String?,
    /** Its why ("Overdue · was due yesterday 17:00"); null at zero. */
    val why: String?,
    /** The top card is overdue or a conflict: lit in the critical colour. */
    val urgent: Boolean,
)

/** Fast: a running fast's timer and progress, or the eating window when none is running. */
data class WidgetFast(
    val running: Boolean,
    /** "Fasting · goal 16 h" · "No fast running" */
    val title: String,
    /** "Goal at 12:00" · "Goal reached at 12:00" · "Eating window open until 20:00" · "Last fast 16 h 05 m" */
    val line: String?,
    /** The timer counts up from here; null when no fast runs. */
    val startedAtMs: Long?,
    /** 0–100 towards the goal; 0 when no fast runs. */
    val progressPercent: Int,
    val reached: Boolean,
)

object HomeWidgetRules {
    /** Look again at least this often, so the passing of planned tasks and due times shows within the half hour. */
    const val MAX_WAIT_MIN = 30
    const val NOTHING_NEEDS_YOU = "Nothing needs you"
    const val NO_FAST = "No fast running"
    const val STARTS_IN = "Starts in"
    private const val MIN_MS = 60_000L

    fun view(
        now: NowView,
        today: Today,
        listsDue: Int,
        stack: NeedsYouStack,
        fasting: FastingView,
        nowMs: Long,
        cal: LocalCalendar,
    ): HomeWidgetsView {
        fun hhmm(ms: Long) = LocalClock.formatMinute(cal.minuteOfDay(ms))

        // Next up: an event that hasn't started counts down instead of saying "In 12 min" (which would go stale).
        // A booked session (the Gym) counts down the same way.
        val startsAt = when {
            now.kind == NowKind.EVENT_SOON && now.event != null -> now.event.startAtMs
            now.kind == NowKind.SESSION && now.session?.status == SessionStatus.BOOKED -> now.session?.startMs
            else -> null
        }?.takeIf { it > nowMs }
        val next = WidgetNext(
            kind = now.kind,
            label = if (startsAt != null) STARTS_IN else now.label,
            lit = now.lit,
            title = now.title,
            line = now.line,
            countdownToMs = startsAt,
            thenLine = now.thenLine,
        )

        // Needs you: the same count as the tab's badge (Today's Needs you plus what's due on the lists).
        val count = today.needsYou.size + listsDue
        val top = stack.cards.firstOrNull()?.takeIf { count > 0 }
        val needsYou = WidgetNeedsYou(
            count = count,
            countText = when {
                count <= 0 -> ""
                count > 9 -> "9+"
                else -> count.toString()
            },
            label = when (count) {
                0 -> NOTHING_NEEDS_YOU
                1 -> "needs you"
                else -> "need you"
            },
            top = top?.title,
            why = top?.why,
            urgent = top?.urgent == true,
        )

        val f = fasting.current
        val fast = if (f != null) {
            val reached = nowMs >= f.goalAtMs
            WidgetFast(
                running = true,
                title = f.title,
                line = (if (f.extended) f.goalWhen else hhmm(f.goalAtMs)).let { if (reached) "Goal reached at $it" else "Goal at $it" },
                startedAtMs = f.startedAtMs,
                progressPercent = if (reached) 100 else (f.progress(nowMs) * 100).toInt().coerceIn(0, 100),
                reached = reached,
            )
        } else {
            WidgetFast(
                running = false, title = NO_FAST,
                line = fasting.windowLine.takeIf { it.isNotBlank() } ?: fasting.last?.line,
                startedAtMs = null, progressPercent = 0, reached = false,
            )
        }

        // When the words change by themselves: each timed event (and booked session) coming into the hour, into the last quarter hour,
        // starting, ten minutes in, and ending; the fast's goal; midnight; and never later than MAX_WAIT_MIN.
        val changes = buildList {
            today.timeline.rows
                .filter { it.kind == TimelineKind.EVENT && it.event != null && !it.event.allDay }
                .map { it.event!! }
                .forEach { e ->
                    add(e.startAtMs - 60 * MIN_MS)
                    add(e.startAtMs - CoverNowRules.SOON_MIN * MIN_MS)
                    add(e.startAtMs)
                    add(e.startAtMs + CoverNowRules.JUST_STARTED_MIN * MIN_MS)
                    add(e.endAtMs)
                }
            // Booked sessions: the same moments, and the "Did you go?" card stepping back after its hour.
            today.timeline.rows.mapNotNull { it.session }.forEach { b ->
                add(b.startMs - 60 * MIN_MS)
                add(b.startMs - CoverNowRules.SOON_MIN * MIN_MS)
                add(b.startMs)
                add(b.startMs + CoverNowRules.JUST_STARTED_MIN * MIN_MS)
                add(b.endMs)
                add(b.endMs + CoverNowRules.ASK_FRESH_MIN * MIN_MS)
            }
            f?.goalAtMs?.let { add(it) }
            add(cal.toEpochMs(cal.epochDayOf(nowMs) + 1, 0))
            add(nowMs + MAX_WAIT_MIN * MIN_MS)
        }
        return HomeWidgetsView(next, needsYou, fast, changes.filter { it > nowMs }.min())
    }
}
