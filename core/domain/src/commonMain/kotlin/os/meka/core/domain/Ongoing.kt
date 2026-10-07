package os.meka.core.domain

/** What an ongoing ("live") notification is about (Outside the app, slice 1). */
enum class OngoingKind {
    /** The next event on the calendar, from [OngoingRules.COUNTDOWN_MIN] minutes before it until just after it starts. */
    MEETING,

    /** A fast that is running. */
    FAST,
}

/**
 * One ongoing notification: shown outside the app (the Fold's notification shade and lock screen; the Mac's menu bar)
 * for as long as it's true, and updated in place. The platform ticks the clock itself from [clockBaseMs], so nothing
 * needs re-posting every minute.
 */
data class OngoingItem(
    val kind: OngoingKind,
    /** Stable for the life of the thing ("meeting-<event id>", "fast-<fast id>"), so an update replaces it. */
    val key: String,
    /** "Call with Tunde" · "Fasting · goal 16 h" */
    val title: String,
    /** "Starts 14:00 · Room 4" · "Started 14:00 · ends 15:00" · "Goal at 12:05 · started 20:05 yesterday" */
    val text: String,
    /** What the lock screen shows when titles stay private: "Next event" · "Fasting". */
    val publicTitle: String,
    /** The countdown's target ([countDown]) or the time it counts up from. */
    val clockBaseMs: Long,
    val countDown: Boolean,
    /** The fast's progress to its goal, 0–100; null for an event. */
    val progressPercent: Int?,
    /** Something is starting (or has just started) / the goal is reached. */
    val lit: Boolean,
    val event: CalendarEvent?,
    /** The event's call link (Join), by the event detail's rules; null when there's none. */
    val join: JoinLink?,
    /** Short words for the Mac's menu bar: "12 min" · "Now" · "14 h 12 m". */
    val short: String,
)

data class OngoingView(
    /** At most one event and one fast, the event first. */
    val items: List<OngoingItem>,
    /** The menu bar's words beside MEKA's mark (the first item's [OngoingItem.short]); null when nothing is going on. */
    val menuBar: String?,
    /** When the list next changes on its own (an event comes into or leaves its window, a goal is reached, midnight). */
    val nextChangeMs: Long,
) {
    companion object {
        val EMPTY = OngoingView(emptyList(), null, Long.MAX_VALUE)
    }
}

/**
 * What runs outside the app, non-AI and pure (nothing stored), read from Today's timeline (so hidden and all-day
 * events never count) and the fasting view:
 * - the next timed event, from [COUNTDOWN_MIN] minutes before it starts ("Starts 14:00", counting down) until
 *   [JUST_STARTED_MIN] minutes after ("Started 14:00 · ends 15:00", counting up: you may be late);
 * - a running fast, counting up from its start with its progress to the goal ("Goal reached at 12:05" once it is).
 * Two events at once: the one starting soonest, else the latest to start. Ending the fast or the event leaving its
 * window takes the notification away.
 */
object OngoingRules {
    const val COUNTDOWN_MIN = 30
    const val JUST_STARTED_MIN = CoverNowRules.JUST_STARTED_MIN
    private const val MIN_MS = 60_000L

    fun view(today: Today, fasting: FastingView, nowMs: Long, cal: LocalCalendar): OngoingView {
        fun hhmm(ms: Long) = LocalClock.formatMinute(cal.minuteOfDay(ms))
        val events = today.timeline.rows
            .filter { it.kind == TimelineKind.EVENT && it.event != null }
            .map { it.event!! }
            .filter { !it.allDay }
            .distinctBy { it.id }
        val upcoming = events.filter { it.startAtMs > nowMs }.sortedWith(compareBy({ it.startAtMs }, { it.title }))
        val soon = upcoming.firstOrNull { it.startAtMs - nowMs <= COUNTDOWN_MIN * MIN_MS }
        val started = events
            .filter { it.startAtMs <= nowMs && nowMs < it.startAtMs + JUST_STARTED_MIN * MIN_MS && nowMs < it.endAtMs }
            .maxWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.title })

        val items = mutableListOf<OngoingItem>()
        val event = soon ?: started
        if (event != null) {
            val d = EventDetails.build(event, nowMs, cal)
            val place = d.location?.takeIf { d.mapsQuery != null }
            val isSoon = event === soon
            val text = if (isSoon) listOfNotNull("Starts ${hhmm(event.startAtMs)}", place).joinToString(" · ")
            else listOfNotNull("Started ${hhmm(event.startAtMs)} · ends ${hhmm(event.endAtMs)}", place).joinToString(" · ")
            val mins = ((event.startAtMs - nowMs + MIN_MS - 1) / MIN_MS).toInt()
            items += OngoingItem(
                kind = OngoingKind.MEETING, key = "meeting-${event.id}", title = event.title, text = text,
                publicTitle = if (event.isFixture) "Kick-off" else "Next event",
                clockBaseMs = event.startAtMs, countDown = isSoon, progressPercent = null, lit = true,
                event = event, join = d.join, short = if (isSoon) "$mins min" else "Now",
            )
        }

        val fast = fasting.current
        if (fast != null) {
            val pct = (fast.progress(nowMs) * 100).toInt().coerceIn(0, 100)
            val reached = nowMs >= fast.goalAtMs
            val goalWhen = if (fast.extended) fast.goalWhen else hhmm(fast.goalAtMs)
            val started = fast.startedLine.replaceFirstChar { it.lowercase() }
            // An extended fast leads with its day ("5-day fast · Day 3 of 5"); the clock beside it shows the hours.
            val text = if (reached) "Goal reached at $goalWhen · $started" else "Goal at $goalWhen · $started"
            items += OngoingItem(
                kind = OngoingKind.FAST, key = "fast-${fast.id}",
                title = if (fast.extended) "${fast.title} · ${FastingRules.dayOf(fast.startedAtMs, fast.goalAtMs, nowMs)}" else fast.title, text = text,
                publicTitle = "Fasting", clockBaseMs = fast.startedAtMs, countDown = false,
                progressPercent = if (reached) 100 else pct, lit = reached, event = null, join = null,
                short = FastingRules.duration(nowMs - fast.startedAtMs),
            )
        }

        // When this changes on its own: the next event enters its window, the shown one starts or leaves it, the
        // goal is reached, or the day turns (tomorrow's events aren't on Today's timeline yet).
        val changes = buildList {
            upcoming.firstOrNull { it.startAtMs - nowMs > COUNTDOWN_MIN * MIN_MS }?.let { add(it.startAtMs - COUNTDOWN_MIN * MIN_MS) }
            soon?.let { add(it.startAtMs) }
            started?.let { add(minOf(it.startAtMs + JUST_STARTED_MIN * MIN_MS, it.endAtMs)) }
            fast?.goalAtMs?.takeIf { it > nowMs }?.let { add(it) }
            fast?.takeIf { it.extended }?.let { add(FastingRules.nextDayAt(it.startedAtMs, nowMs)) }
            add(cal.toEpochMs(cal.epochDayOf(nowMs) + 1, 0))
        }
        return OngoingView(items, items.firstOrNull()?.short, changes.filter { it > nowMs }.minOrNull() ?: Long.MAX_VALUE)
    }
}
