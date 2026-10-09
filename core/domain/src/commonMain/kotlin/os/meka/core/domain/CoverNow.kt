package os.meka.core.domain

/** What the "now" card is about (Fold modes, slice 3). */
enum class NowKind {
    /** An event starts within [CoverNowRules.SOON_MIN] minutes, or within the hour with no task up next. */
    EVENT_SOON,

    /** An event is happening now. */
    EVENT_RUNNING,

    /** Today's Up next task. */
    TASK,

    /** A booked session (the Gym): starting soon, on now, or over and asking "Did you go?". */
    SESSION,

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

    /** Answers a booked session "Went" ([NowView.session]), as from Today's session card. */
    WENT,

    /** Answers a booked session "Didn't go": it's rebooked, never nagged. */
    DIDNT_GO,

    /**
     * "Move to later" on a late planned task ([LateTaskRules]): re-plans it to [NowView.laterAtMs], the first free
     * stretch from now.
     */
    LATER,
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
    /** The booked session, for [NowKind.SESSION]: [NowAction.WENT] and [NowAction.DIDNT_GO] answer it. */
    val session: SessionCard? = null,
    /** A late planned task ([LateTaskRules]): the label is lit and the line starts "Since 09:15". */
    val late: Boolean = false,
    /** Where [NowAction.LATER] puts the task; null when it isn't offered. */
    val laterAtMs: Long? = null,
) {
    /** For Swift: whether an action is offered. */
    fun offers(action: NowAction): Boolean = action in actions
}

/**
 * The "now" card's rules, non-AI and pure (nothing stored, read from Today).
 *
 * What it shows, first match wins (a booked session, the Gym, counts like an event: whichever starts first):
 * 1. an event or session starting within [SOON_MIN] minutes (time to go or join): "In 12 min";
 * 2. an event or session that started within the last [JUST_STARTED_MIN] minutes (you may be late): "Now · ends 15:30";
 * 3. a session that ended within [ASK_FRESH_MIN] minutes and isn't answered: "Did you go?" (Went · Didn't go);
 * 4. the Up next task: "Up next";
 * 5. the next event or session within the hour (Today's Up next event): "In 40 min";
 * 6. an event still running: "Now · ends 17:00" (the latest to start, when two overlap), else a session on now;
 * 7. a session still asking "Did you go?" (until it's answered or the day ends), not lit;
 * 8. clear: "You're clear" · "Nothing else planned today".
 *
 * Actions: an event offers Join (its call link) or else Maps (its place), then Open; a task offers Done, Tomorrow and
 * Open; a session on now or over offers Went and Didn't go (one booked for later has nothing to tap yet). "Then" names
 * the other of the Up next task and the next event (or session), so the second thing is never lost.
 * Hidden events never show: Today's timeline already leaves them out.
 */
object CoverNowRules {
    const val SOON_MIN = 15
    const val JUST_STARTED_MIN = 10
    /** A session's "Did you go?" leads the card for this long after it ends; then it waits behind the task. */
    const val ASK_FRESH_MIN = 60
    const val MAX_ACTIONS = 3
    const val CLEAR_LABEL = "You're clear"
    const val NOTHING = "Nothing else planned today"
    private const val MIN_MS = 60_000L

    fun now(today: Today, nowMs: Long, cal: LocalCalendar, sessions: List<SessionCard> = emptyList()): NowView {
        fun hhmm(ms: Long) = LocalClock.formatMinute(cal.minuteOfDay(ms))
        val tl = today.timeline
        val nextEvent = tl.nextEvent
        val running = tl.rows.filter { it.kind == TimelineKind.EVENT && it.running && it.event != null }
            .maxWithOrNull(compareBy<TimelineRow> { it.event!!.startAtMs }.thenBy { it.title })?.event
        val task = today.upNext
        val needs = today.needsYou.size.takeIf { it > 0 }?.let { if (it == 1) "1 needs you" else "$it need you" }

        val timed = sessions.filter { it.startMs != null && it.endMs != null }
        val sessionNext = timed.filter { it.status == SessionStatus.BOOKED && it.startMs!! - nowMs <= TimelineRules.UP_NEXT_WINDOW_MIN * MIN_MS }
            .minByOrNull { it.startMs!! }
        val sessionOn = timed.filter { it.status == SessionStatus.NOW }.minByOrNull { it.startMs!! }
        val sessionAsk = timed.filter { it.status == SessionStatus.ASK }.maxByOrNull { it.endMs!! }
        /** The session starts before the next event (or there's none within the hour). */
        val sessionFirst = sessionNext != null && (nextEvent == null || sessionNext.startMs!! < nextEvent.event.startAtMs)

        val justStarted = running?.takeIf { nowMs - it.startAtMs <= JUST_STARTED_MIN * MIN_MS }
        val sessionJustStarted = sessionOn?.takeIf { nowMs - it.startMs!! <= JUST_STARTED_MIN * MIN_MS }
        val soonMs = SOON_MIN * MIN_MS
        val session: SessionCard? = when {
            sessionFirst && sessionNext!!.startMs!! - nowMs <= soonMs -> sessionNext
            nextEvent != null && nextEvent.minutes <= SOON_MIN -> null
            justStarted != null -> null
            sessionJustStarted != null -> sessionJustStarted
            sessionAsk != null && nowMs - sessionAsk.endMs!! <= ASK_FRESH_MIN * MIN_MS -> sessionAsk
            task != null -> null
            sessionFirst -> sessionNext
            nextEvent != null -> null
            running != null -> null
            else -> sessionOn ?: sessionAsk
        }
        val kind = when {
            session != null -> NowKind.SESSION
            nextEvent != null && nextEvent.minutes <= SOON_MIN -> NowKind.EVENT_SOON
            justStarted != null -> NowKind.EVENT_RUNNING
            task != null -> NowKind.TASK
            nextEvent != null -> NowKind.EVENT_SOON
            running != null -> NowKind.EVENT_RUNNING
            else -> NowKind.CLEAR
        }

        fun sessionView(c: SessionCard): NowView {
            val start = c.startMs!!
            val end = c.endMs!!
            val span = "${hhmm(start)}–${hhmm(end)}"
            val (label, lit, line) = when (c.status) {
                SessionStatus.BOOKED -> Triple(
                    "In ${TimelineRules.inLabel(((start - nowMs + MIN_MS - 1) / MIN_MS).toInt().coerceAtLeast(1))}", true,
                    "$span · Leave by ${hhmm(start - SessionRules.BUFFER_MIN * MIN_MS)}",
                )
                SessionStatus.NOW -> Triple("Now · ends ${hhmm(end)}", true, span)
                else -> Triple("Did you go?", nowMs - end <= ASK_FRESH_MIN * MIN_MS, span)
            }
            return NowView(
                kind = NowKind.SESSION, label = label, lit = lit, title = c.heading, line = line, event = null, task = null,
                join = null, mapsQuery = null,
                actions = if (c.asks) listOf(NowAction.WENT, NowAction.DIDNT_GO) else emptyList(),
                thenLine = task?.let { "Then: ${it.title}" }, thenEvent = null, thenTask = task, needsYouLine = needs,
                session = c,
            )
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
            NowKind.SESSION -> sessionView(session!!)
            NowKind.EVENT_SOON -> eventView(nextEvent!!.event, "In ${TimelineRules.inLabel(nextEvent.minutes)}")
            NowKind.EVENT_RUNNING -> {
                val e = justStarted ?: running!!
                eventView(e, "Now · ends ${hhmm(e.endAtMs)}")
            }
            NowKind.TASK -> {
                val t = task!!
                val line = taskLine(t, tl, nowMs, cal)
                // The next event (within the hour) or, failing that, the next timed thing after the task.
                val thenEvent = if (sessionFirst) null else nextEvent?.event ?: running
                val thenLine = when {
                    sessionFirst -> "Then: ${sessionNext!!.heading} at ${hhmm(sessionNext.startMs!!)}"
                    nextEvent != null -> "Then: ${nextEvent.event.title} at ${hhmm(nextEvent.event.startAtMs)}"
                    running != null -> "Then: ${running.title} until ${hhmm(running.endAtMs)}"
                    else -> null
                }
                taskView(t, line, thenLine, thenEvent, needs, today.upNextLate(nowMs), today.upNextLaterMs)
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

    /** The Up next card's label, the same on every screen (Fold review 2026-10-09, item 3). */
    const val UP_NEXT_LABEL = "Up next"

    /**
     * Up next as a card on every screen (Fold review 2026-10-09, item 3): the open Fold and the Mac's window show the
     * closed Fold's card — "UP NEXT", the title, its line ("Anytime today" · "At 14:00 · 30 min") and Done · Tomorrow ·
     * Open — instead of a bare title with a ring. No "Then" or Needs you lines: those screens list the next event and
     * Needs you beside it. Null when nothing is up next.
     */
    fun upNext(today: Today, nowMs: Long, cal: LocalCalendar): NowView? {
        val t = today.upNext ?: return null
        return taskView(
            t, taskLine(t, today.timeline, nowMs, cal), thenLine = null, thenEvent = null, needs = null,
            late = today.upNextLate(nowMs), laterAtMs = today.upNextLaterMs,
        )
    }

    /**
     * "At 14:00 · 30 min · ↻ Every weekday" when the task is on the timeline ("Since 09:15 · 30 min" once it's late),
     * else "Anytime today" (and its repeat).
     */
    private fun taskLine(t: Task, tl: DayTimeline, nowMs: Long, cal: LocalCalendar): String {
        val row = tl.rows.firstOrNull { it.kind == TimelineKind.TASK && it.task?.id == t.id }
        return if (row != null && row.late) row.detail ?: LateTaskRules.sinceLabel(row.time)
        else if (row != null) listOfNotNull("At ${row.time}", row.detail).joinToString(" · ")
        else listOfNotNull("Anytime today", t.repeatMeta(cal.epochDayOf(nowMs))?.let { "↻ $it" }).joinToString(" · ")
    }

    /**
     * Done · Tomorrow · Open; a late planned task (lit) offers Done · Move to later · Tomorrow instead (the card itself
     * opens it), or Done · Tomorrow · Open when today has no room left.
     */
    private fun taskView(
        t: Task, line: String, thenLine: String?, thenEvent: CalendarEvent?, needs: String?,
        late: Boolean = false, laterAtMs: Long? = null,
    ): NowView {
        val later = laterAtMs.takeIf { late }
        val actions = if (later != null) listOf(NowAction.DONE, NowAction.LATER, NowAction.TOMORROW)
        else listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK)
        return NowView(
            kind = NowKind.TASK, label = UP_NEXT_LABEL, lit = late, title = t.title, line = line, event = null, task = t,
            join = null, mapsQuery = null, actions = actions,
            thenLine = thenLine, thenEvent = thenEvent, thenTask = null, needsYouLine = needs,
            late = late, laterAtMs = later,
        )
    }
}
