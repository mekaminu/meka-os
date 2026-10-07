package os.meka.core.domain

/** What the "now" card is about (Fold modes, slice 3). */
enum class NowKind {
    /** An event starts within [CoverNowRules.SOON_MIN] minutes, or within the hour with no task up next. */
    EVENT_SOON,

    /** An event is happening now. */
    EVENT_RUNNING,

    /** Today's Up next task. */
    TASK,

    /** Nothing up next and nothing on in the hour. */
    CLEAR,
}

/** The one-tap actions on the "now" card, in the order they are shown (at most [CoverNowRules.MAX_ACTIONS]). */
enum class NowAction {
    /** Opens the event's call link ([NowView.join]). */
    JOIN,

    /** Opens the place in Maps ([NowView.mapsQuery]). */
    MAPS,

    /** Opens the event's detail. */
    OPEN_EVENT,

    /** Completes the task (the same completion as its ring). */
    DONE,

    /** Moves just this task (or occurrence) to tomorrow (the task detail's snooze). */
    TOMORROW,

    /** Opens the task's detail. */
    OPEN_TASK,
}

/**
 * The "now" card (Fold modes, slice 3): on the closed Fold's cover screen it heads Today in place of Up next, and on
 * the Mac it heads the menu-bar window. One thing, what it is, and the taps that deal with it.
 */
data class NowView(
    val kind: NowKind,
    /** "In 12 min" · "Now · ends 15:30" · "Up next" · "You're clear" */
    val label: String,
    /** The label is lit (accent): something is starting or happening. */
    val lit: Boolean,
    /** The event or task title; "Nothing else planned today" when clear. */
    val title: String,
    /** "14:00–15:00 · Room 4" · "At 14:00 · 30 min · ↻ Every weekday" · "Anytime today"; null when there's nothing to say. */
    val line: String?,
    val event: CalendarEvent?,
    val task: Task?,
    val join: JoinLink?,
    val mapsQuery: String?,
    val actions: List<NowAction>,
    /** The other thing after it: "Then: Standup at 15:00" · "Then: Send the invoice"; null when there's none. */
    val thenLine: String?,
    /** What [thenLine] opens. */
    val thenEvent: CalendarEvent?,
    val thenTask: Task?,
    /** "3 need you" · "1 needs you"; null when nothing does. Opens the Needs you tab. */
    val needsYouLine: String?,
) {
    /** For Swift: whether an action is offered. */
    fun offers(action: NowAction): Boolean = action in actions
}

/**
 * The "now" card's rules, non-AI and pure (nothing stored, read from Today).
 *
 * What it shows, first match wins:
 * 1. an event starting within [SOON_MIN] minutes (time to go or join): "In 12 min";
 * 2. an event that started within the last [JUST_STARTED_MIN] minutes (you may be late): "Now · ends 15:30";
 * 3. the Up next task: "Up next";
 * 4. the next event within the hour (Today's Up next event): "In 40 min";
 * 5. an event still running: "Now · ends 17:00" (the latest to start, when two overlap);
 * 6. clear: "You're clear" · "Nothing else planned today".
 *
 * Actions: an event offers Join (its call link) or else Maps (its place), then Open; a task offers Done, Tomorrow and
 * Open. "Then" names the other of the Up next task and the next event, so the second thing is never lost.
 * Hidden events never show: Today's timeline already leaves them out.
 */
object CoverNowRules {
    const val SOON_MIN = 15
    const val JUST_STARTED_MIN = 10
    const val MAX_ACTIONS = 3
    const val CLEAR_LABEL = "You're clear"
    const val NOTHING = "Nothing else planned today"
    private const val MIN_MS = 60_000L

    fun now(today: Today, nowMs: Long, cal: LocalCalendar): NowView {
        fun hhmm(ms: Long) = LocalClock.formatMinute(cal.minuteOfDay(ms))
        val tl = today.timeline
        val nextEvent = tl.nextEvent
        val running = tl.rows.filter { it.kind == TimelineKind.EVENT && it.running && it.event != null }
            .maxWithOrNull(compareBy<TimelineRow> { it.event!!.startAtMs }.thenBy { it.title })?.event
        val task = today.upNext
        val needs = today.needsYou.size.takeIf { it > 0 }?.let { if (it == 1) "1 needs you" else "$it need you" }

        val justStarted = running?.takeIf { nowMs - it.startAtMs <= JUST_STARTED_MIN * MIN_MS }
        val kind = when {
            nextEvent != null && nextEvent.minutes <= SOON_MIN -> NowKind.EVENT_SOON
            justStarted != null -> NowKind.EVENT_RUNNING
            task != null -> NowKind.TASK
            nextEvent != null -> NowKind.EVENT_SOON
            running != null -> NowKind.EVENT_RUNNING
            else -> NowKind.CLEAR
        }

        fun eventView(e: CalendarEvent, label: String): NowView {
            val d = EventDetails.build(e, nowMs, cal)
            val actions = buildList {
                if (d.join != null) add(NowAction.JOIN) else if (d.mapsQuery != null) add(NowAction.MAPS)
                add(NowAction.OPEN_EVENT)
            }
            val line = listOfNotNull("${hhmm(e.startAtMs)}–${hhmm(e.endAtMs)}", d.location?.takeIf { d.mapsQuery != null })
                .joinToString(" · ")
            val then = task?.let { "Then: ${it.title}" }
            return NowView(
                kind = kind, label = label, lit = true, title = e.title, line = line, event = e, task = null,
                join = d.join, mapsQuery = d.mapsQuery, actions = actions.take(MAX_ACTIONS),
                thenLine = then, thenEvent = null, thenTask = task, needsYouLine = needs,
            )
        }

        return when (kind) {
            NowKind.EVENT_SOON -> eventView(nextEvent!!.event, "In ${TimelineRules.inLabel(nextEvent.minutes)}")
            NowKind.EVENT_RUNNING -> {
                val e = justStarted ?: running!!
                eventView(e, "Now · ends ${hhmm(e.endAtMs)}")
            }
            NowKind.TASK -> {
                val t = task!!
                val row = tl.rows.firstOrNull { it.kind == TimelineKind.TASK && it.task?.id == t.id }
                val line = if (row != null) listOfNotNull("At ${row.time}", row.detail).joinToString(" · ")
                else listOfNotNull("Anytime today", t.repeatMeta(cal.epochDayOf(nowMs))?.let { "↻ $it" }).joinToString(" · ")
                // The next event (within the hour) or, failing that, the next timed thing after the task.
                val thenEvent = nextEvent?.event ?: running
                val thenLine = when {
                    nextEvent != null -> "Then: ${nextEvent.event.title} at ${hhmm(nextEvent.event.startAtMs)}"
                    running != null -> "Then: ${running.title} until ${hhmm(running.endAtMs)}"
                    else -> null
                }
                NowView(
                    kind = kind, label = "Up next", lit = false, title = t.title, line = line, event = null, task = t,
                    join = null, mapsQuery = null, actions = listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK),
                    thenLine = thenLine, thenEvent = thenEvent, thenTask = null, needsYouLine = needs,
                )
            }
            NowKind.CLEAR -> {
                val left = tl.anytime.size
                NowView(
                    kind = kind, label = CLEAR_LABEL, lit = false, title = NOTHING,
                    line = if (left > 0) "$left anytime today" else null, event = null, task = null,
                    join = null, mapsQuery = null, actions = emptyList(),
                    thenLine = null, thenEvent = null, thenTask = null, needsYouLine = needs,
                )
            }
        }
    }
}
