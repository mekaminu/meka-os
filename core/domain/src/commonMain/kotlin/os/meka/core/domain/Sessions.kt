package os.meka.core.domain

/**
 * Booked habits: the Gym (build plan M1, Meka 2026-10-07: "MEKA's job is getting you there, not programming workouts").
 * Non-AI and pure; nothing is stored but the habit's settings and each day's answer.
 *
 * A habit with "Book my sessions" on ([HabitFields.BOOK_SLOTS]) gets its week booked around the calendar: from today to
 * Sunday, as many sessions as this week still needs ([GoalRules.weekTarget] minus the days already ticked), each in the
 * habit's part of the day when it has room (else any time 07:00–21:30), clear of events (15 minutes either side to get
 * there and back, an hour before kick-off) and of work hours (bank holidays are free days), with a rest day between
 * sessions while the week leaves room for one. Sessions of two booked habits never overlap.
 *
 * The booking is worked out afresh from what's there, so nothing goes stale: a meeting that lands on the slot moves the
 * session, and "Didn't go" ([HabitCompletionFields.MISSED]) or a slot that passed unticked simply rebooks the week's
 * remaining sessions on the days left — rebooked, never nagged. Today's slot is worked out from the whole day (not from
 * now), so it stays put while the day goes by and becomes "Did you go?" once it's over.
 *
 * An optional rotation (Push · Pull · Legs) labels the sessions in turn, carrying on from the last session ticked.
 *
 * From Sunday evening ([NEXT_WEEK_FROM_MIN], when the weekly review comes) next week is booked too, Monday to Sunday at
 * the full target, carrying on the rest days and the rotation, so Goals says "Next week: Mon 17:45 · Wed 17:45 · …" and
 * Today's card says what's next. Those are previews like the rest: worked out afresh, so they follow the calendar.
 */
data class SessionHabit(
    val id: String,
    val title: String,
    val perWeek: Int,
    val timing: HabitTiming,
    val minutes: Int,
    val createdAtMs: Long,
    val doneDays: Set<Long>,
    val missedDays: Set<Long>,
    val rotation: List<String> = emptyList(),
    /** The label of the latest session ticked (any day), so the rotation carries on. */
    val lastLabel: String? = null,
    /** Today's answer, when "Went": its label and note. */
    val todayLabel: String? = null,
    val todayNote: String? = null,
)

/** One session booked in the week. */
data class BookedSession(
    val habitId: String,
    val title: String,
    /** The rotation's label for it ("Push"); null with no rotation. */
    val label: String?,
    val day: Long,
    val startMs: Long,
    val endMs: Long,
    /** "Today 17:45", "Thu 17:45". */
    val whenLine: String,
)

enum class SessionStatus {
    /** Booked later today. */
    BOOKED,
    /** On now. */
    NOW,
    /** The slot is over: "Did you go?". */
    ASK,
    /** Ticked today. */
    WENT,
    /** "Didn't go" today: rebooked. */
    MISSED,
}

/** Today's card for one booked habit. */
data class SessionCard(
    val habitId: String,
    val status: SessionStatus,
    /** "Gym · Push", "Gym". */
    val heading: String,
    /** "Today 17:45–18:45", "Now · until 18:45", "Did you go? · 17:45–18:45", "Went · 2 of 3 this week", "Not today · no worries". */
    val line: String,
    /** "Next: Thu 17:45 · Pull", "Rebooked for Thu 17:45", "No other slot this week", "Week done · 3 of 3"; null when nothing to add. */
    val next: String?,
    /** The label "Went" records (the rotation's turn for this session). */
    val label: String?,
    /** Today's note once went ("5 km"). */
    val note: String?,
    val startMs: Long?,
    val endMs: Long?,
    /** What a screen reader says. */
    val spoken: String,
) {
    /** Went / Didn't go are offered: once the slot is over (and while it's on, for an early finish). */
    val asks: Boolean get() = status == SessionStatus.ASK || status == SessionStatus.NOW
    /** Undo is offered for today's answer. */
    val answered: Boolean get() = status == SessionStatus.WENT || status == SessionStatus.MISSED
}

data class SessionsView(
    /** Today's cards, one per booked habit with something today, in time order. */
    val cards: List<SessionCard>,
    /** The week's bookings from today to Sunday, in time order (today's included while it's ahead or on). */
    val sessions: List<BookedSession>,
    /** Habit id → its week line for Goals ("Booked Today 17:45 · Thu 17:45", "Week done · 3 of 3"). */
    val lines: Map<String, String>,
) {
    /** Today's sessions still to come or on now: the planner keeps them free (shown, not applied). */
    fun todayBlocks(today: Long, nowMs: Long): List<BookedSession> = sessions.filter { it.day == today && it.endMs > nowMs }

    companion object {
        val EMPTY = SessionsView(emptyList(), emptyList(), emptyMap())
    }
}

object SessionRules {
    /** Room kept either side of an event (getting there and back), and after work. */
    const val BUFFER_MIN = 15
    /** Kept clear before a kick-off, like the planner. */
    const val FIXTURE_LEAD_MIN = 60
    const val GRANULARITY_MIN = 15
    /** A habit added today isn't booked for the next half hour. */
    const val LEAD_AFTER_ADDING_MIN = 30
    const val MAX_LABEL = 24
    const val MAX_LABELS = 7
    const val MAX_NOTE = 80
    /** Sunday from 18:00 (the weekly review's card, [ReviewRules.CARD_START_MIN]) next week is booked too. */
    const val NEXT_WEEK_FROM_MIN = ReviewRules.CARD_START_MIN
    /** The fallback when the habit's part of the day has no room: 07:00–21:30. */
    private val ANY_WINDOW = 7 * 60 until 21 * 60 + 30

    /** The rotation presets the apps offer (the first is none). */
    val ROTATIONS: List<List<String>> = listOf(
        emptyList(), listOf("Push", "Pull", "Legs"), listOf("Upper", "Lower"), listOf("Full body A", "Full body B"),
    )

    fun rotationLabel(r: List<String>): String = if (r.isEmpty()) "No rotation" else r.joinToString(" · ")
    fun rotationAt(index: Int): List<String> = ROTATIONS[index.coerceIn(0, ROTATIONS.size - 1)]

    fun decodeRotation(s: String?): List<String> = cleanRotation(s?.split('|').orEmpty())

    fun cleanRotation(labels: List<String>): List<String> =
        labels.map { it.trim().replace('|', '/').take(MAX_LABEL) }.filter { it.isNotEmpty() }.take(MAX_LABELS)

    /** One line, trimmed, at most [MAX_NOTE] characters; null when blank. */
    fun cleanNote(note: String?): String? =
        note?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE)

    /** Where a session may go, as local minutes [start, end): the habit's part of the day, sessions ending by 21:30. */
    fun window(t: HabitTiming): IntRange = when (t) {
        HabitTiming.MORNING -> 6 * 60 + 30 until 12 * 60
        HabitTiming.AFTERNOON -> 12 * 60 until 17 * 60
        HabitTiming.EVENING -> 17 * 60 until 21 * 60 + 30
        HabitTiming.ANYTIME -> ANY_WINDOW
    }

    /** What's busy on [day]: timed events with room either side, and the work shift (bank holidays off). */
    fun busyOn(
        day: Long, events: List<CalendarEvent>, work: WorkSchedule?, holidays: HolidayCalendar, cal: LocalCalendar,
    ): List<DayPlanner.Slot> {
        val start = cal.toEpochMs(day, 0)
        val end = cal.toEpochMs(day + 1, 0)
        val min = 60_000L
        val out = events.filter { !it.allDay && it.startAtMs < end && it.endAtMs > start }.map { e ->
            val lead = if (e.isFixture) FIXTURE_LEAD_MIN else BUFFER_MIN
            DayPlanner.Slot(e.startAtMs - lead * min, e.endAtMs + BUFFER_MIN * min)
        }.toMutableList()
        if (work != null && work.startMinute != work.endMinute) {
            if (WorkModeRules.isWorkDay(work, holidays, day)) {
                val shiftEnd = if (work.crossesMidnight) cal.toEpochMs(day + 1, work.endMinute) else cal.toEpochMs(day, work.endMinute)
                out += DayPlanner.Slot(cal.toEpochMs(day, work.startMinute) - BUFFER_MIN * min, shiftEnd + BUFFER_MIN * min)
            }
            // Last night's shift running into this morning.
            if (work.crossesMidnight && WorkModeRules.isWorkDay(work, holidays, day - 1)) {
                out += DayPlanner.Slot(start, cal.toEpochMs(day, work.endMinute) + BUFFER_MIN * min)
            }
        }
        return out
    }

    /** The first slot of [minutes] on [day] inside [window] (local minutes), clear of [busy], from [notBeforeMs]. */
    fun slotOn(day: Long, window: IntRange, minutes: Int, busy: List<DayPlanner.Slot>, notBeforeMs: Long, cal: LocalCalendar): DayPlanner.Slot? {
        val min = 60_000L
        val origin = cal.toEpochMs(day, 0)
        val from = maxOf(cal.toEpochMs(day, window.first), notBeforeMs)
        val to = cal.toEpochMs(day, window.last + 1)
        val need = minutes.coerceAtLeast(GRANULARITY_MIN) * min
        val g = GRANULARITY_MIN * min
        for (free in DayPlanner.subtract(DayPlanner.Slot(from, to), busy)) {
            val r = ((free.startMs - origin) % g + g) % g
            val s = if (r == 0L) free.startMs else free.startMs + (g - r)
            if (free.endMs - s >= need) return DayPlanner.Slot(s, s + need)
        }
        return null
    }

    /**
     * Books every habit in [habits] from [today] to Sunday (and next week from Sunday evening) and says what today holds (see the file comment). [busy]
     * gives a day's busy blocks ([busyOn]). Habits are booked in order, so a later one never overlaps an earlier one.
     */
    fun book(
        habits: List<SessionHabit>, today: Long, nowMs: Long, cal: LocalCalendar, busy: (Long) -> List<DayPlanner.Slot>,
    ): SessionsView {
        if (habits.isEmpty()) return SessionsView.EMPTY
        val sunday = GoalRules.weekStart(today) + 6
        val bookNextWeek = today == sunday && cal.minuteOfDay(nowMs) >= NEXT_WEEK_FROM_MIN
        val taken = mutableMapOf<Long, MutableList<DayPlanner.Slot>>()
        val sessions = mutableListOf<BookedSession>()
        val cards = mutableListOf<SessionCard>()
        val lines = linkedMapOf<String, String>()
        val dayBusy = mutableMapOf<Long, List<DayPlanner.Slot>>()
        fun hhmm(ms: Long) = LocalClock.formatMinute(cal.minuteOfDay(ms))
        fun whenLine(day: Long, startMs: Long) = "${if (day == today) "Today" else FastingRules.dayLabel(day)} ${hhmm(startMs)}"

        for (h in habits.sortedBy { it.id }) {
            val createdDay = cal.epochDayOf(h.createdAtMs)
            val ws = GoalRules.weekStart(today)
            val weekTarget = GoalRules.weekTarget(h.perWeek, today, createdDay)
            val doneWeek = h.doneDays.count { it in ws..today }
            val wentToday = today in h.doneDays
            val missedToday = !wentToday && today in h.missedDays
            var needed = (weekTarget - doneWeek).coerceAtLeast(0)

            // Candidate days with a slot in [from, to] (answered days skipped), then picked with a rest day between
            // sessions while the range still leaves room for one.
            var last = h.doneDays.filter { it <= today }.maxOrNull() ?: Long.MIN_VALUE / 2
            fun pick(from: Long, to: Long, want: Int): List<Pair<Long, DayPlanner.Slot>> {
                val candidates = mutableListOf<Pair<Long, DayPlanner.Slot>>()
                for (d in from..to) {
                    if (d in h.doneDays || d in h.missedDays) continue
                    val notBefore = if (d == createdDay) h.createdAtMs + LEAD_AFTER_ADDING_MIN * 60_000L else Long.MIN_VALUE
                    val blocks = dayBusy.getOrPut(d) { busy(d) } + taken[d].orEmpty()
                    val slot = slotOn(d, window(h.timing), h.minutes, blocks, notBefore, cal)
                        ?: slotOn(d, ANY_WINDOW, h.minutes, blocks, notBefore, cal)
                        ?: continue
                    candidates += d to slot
                }
                var left = want
                val chosen = mutableListOf<Pair<Long, DayPlanner.Slot>>()
                candidates.forEachIndexed { i, c ->
                    if (left == 0) return@forEachIndexed
                    if (c.first - last >= 2 || candidates.size - i <= left) {
                        chosen += c; left--; last = c.first
                    }
                }
                return chosen
            }
            val chosen = pick(today, sunday, needed)
            needed -= chosen.size
            // Sunday evening: next week at its full target, after this week's (rest days and rotation carry on).
            val chosenNext = if (!bookNextWeek) emptyList() else {
                val nextWeekTarget = GoalRules.weekTarget(h.perWeek, sunday + 1, createdDay)
                val doneNext = h.doneDays.count { it in sunday + 1..sunday + 7 }
                pick(sunday + 1, sunday + 7, (nextWeekTarget - doneNext).coerceAtLeast(0))
            }
            // Labels carry on from the last session ticked.
            val rot = h.rotation
            var next = if (rot.isEmpty()) 0 else (rot.indexOf(h.lastLabel).let { if (it < 0) 0 else it + 1 }) % rot.size
            val all = (chosen + chosenNext).map { (d, slot) ->
                val label = rot.getOrNull(next)
                if (rot.isNotEmpty()) next = (next + 1) % rot.size
                taken.getOrPut(d) { mutableListOf() } += slot
                BookedSession(h.id, h.title, label, d, slot.startMs, slot.endMs, whenLine(d, slot.startMs))
            }
            sessions += all
            val booked = all.filter { it.day <= sunday }
            val nextWeek = all.filter { it.day > sunday }
            val short = needed
            val weekMet = doneWeek >= weekTarget
            val thisWeek = when {
                weekMet -> "Week done · $doneWeek of $weekTarget"
                booked.isEmpty() -> "No room left this week"
                else -> "Booked " + booked.joinToString(" · ") { it.whenLine } + (if (short > 0) " · no room for $short more" else "")
            }
            lines[h.id] = if (nextWeek.isEmpty()) thisWeek else "$thisWeek · Next week: " + nextWeek.joinToString(" · ") { it.whenLine }

            // Today's card.
            val todays = booked.firstOrNull { it.day == today }
            val later = all.filter { it.day > today }
            fun named(label: String?) = listOfNotNull(h.title, label).joinToString(" · ")
            val upcoming = later.firstOrNull()?.let { s -> "Next: ${s.whenLine}" + (s.label?.let { " · $it" } ?: "") }
            val count = "$doneWeek of $weekTarget this week"
            val card = when {
                wentToday -> SessionCard(
                    h.id, SessionStatus.WENT, named(h.todayLabel), "Went · $count",
                    upcoming ?: if (weekMet) "Week done · $doneWeek of $weekTarget" else null,
                    h.todayLabel, h.todayNote, null, null,
                    "${named(h.todayLabel)}, went today, $count" + (h.todayNote?.let { ", note $it" } ?: ""),
                )
                missedToday -> {
                    val re = later.firstOrNull { it.day <= sunday }?.let { s -> "Rebooked for ${s.whenLine}" + (s.label?.let { " · $it" } ?: "") }
                        ?: nextWeek.firstOrNull()?.let { "No other slot this week · " + upcoming }
                        ?: "No other slot this week"
                    SessionCard(h.id, SessionStatus.MISSED, h.title, "Not today · no worries", re, null, null, null, null,
                        "${h.title}, not today. $re")
                }
                todays != null -> {
                    val span = "${hhmm(todays.startMs)}–${hhmm(todays.endMs)}"
                    val (status, line) = when {
                        nowMs < todays.startMs -> SessionStatus.BOOKED to "Today $span"
                        nowMs < todays.endMs -> SessionStatus.NOW to "Now · until ${hhmm(todays.endMs)}"
                        else -> SessionStatus.ASK to "Did you go? · $span"
                    }
                    SessionCard(h.id, status, named(todays.label), line, upcoming ?: count, todays.label, null,
                        todays.startMs, todays.endMs, "${named(todays.label)}, $line")
                }
                else -> null
            }
            card?.let { cards += it }
        }
        return SessionsView(
            cards.sortedWith(compareBy<SessionCard> { it.startMs ?: Long.MAX_VALUE }.thenBy { it.habitId }),
            sessions.sortedWith(compareBy<BookedSession> { it.startMs }.thenBy { it.habitId }),
            lines,
        )
    }

    /** The "time to go" heads-up comes this long before a session (leave by [BUFFER_MIN] before it starts). */
    const val LEAVE_NOTICE_MIN = 30

    /**
     * Today's notices for booked sessions, through the governor (build plan Gym slice 2): half an hour before an
     * unanswered session "Gym · Push at 17:45" · "Leave by 17:30 · until 18:45" (stale once it starts), and when its slot
     * is over "Did you go? · Gym · Push" · "17:45–18:45 · Went or Didn't go in Today" (standing until the day ends).
     * Answering (Went / Didn't go) takes both away; a session moved by a new meeting gets a fresh "time to go". Never a
     * nag: one of each per session, and a missed one is rebooked, not chased.
     */
    fun notices(view: SessionsView, cal: LocalCalendar): List<Notice> {
        val out = mutableListOf<Notice>()
        val min = 60_000L
        fun hhmm(ms: Long) = LocalClock.formatMinute(cal.minuteOfDay(ms))
        for (c in view.cards) {
            if (c.answered) continue
            val start = c.startMs ?: continue
            val end = c.endMs ?: continue
            val day = cal.epochDayOf(start)
            out += Notice(
                key = "session:${c.habitId}:$day:leave:$start", source = NoticeSource.SESSION_LEAVE, tier = NoticeTier.HEADS_UP,
                title = "${c.heading} at ${hhmm(start)}", text = "Leave by ${hhmm(start - BUFFER_MIN * min)} · until ${hhmm(end)}",
                atMs = start - LEAVE_NOTICE_MIN * min, target = NoticeTarget.TODAY, expiresAtMs = start,
                precision = NoticePrecision.CLOCK,
            )
            out += Notice(
                key = "session:${c.habitId}:$day:ask", source = NoticeSource.SESSION_ASK, tier = NoticeTier.HEADS_UP,
                title = "Did you go? · ${c.heading}", text = "${hhmm(start)}–${hhmm(end)} · Went or Didn't go in Today",
                atMs = end, target = NoticeTarget.TODAY, expiresAtMs = cal.toEpochMs(day + 1, 0),
                actions = listOf(NoticeAction.WENT, NoticeAction.DIDNT_GO),
            )
        }
        return out
    }

    /**
     * The habit a "Did you go?" notice ([notices]' key `session:<habit>:<day>:ask`) asks about, when that day is
     * [today]; null for any other key or day (a notification left in the shade past midnight answers nothing).
     */
    fun askedHabit(key: String, today: Long): String? {
        if (!key.startsWith(ASK_PREFIX) || !key.endsWith(ASK_SUFFIX)) return null
        val mid = key.substring(ASK_PREFIX.length, key.length - ASK_SUFFIX.length)
        val cut = mid.lastIndexOf(':').takeIf { it > 0 } ?: return null
        val day = mid.substring(cut + 1).toLongOrNull() ?: return null
        return mid.substring(0, cut).takeIf { day == today }
    }

    /** The card a "Did you go?" notice may still answer: that session today, offering Went / Didn't go. */
    fun askedCard(view: SessionsView, key: String, today: Long): SessionCard? {
        val id = askedHabit(key, today) ?: return null
        return view.cards.firstOrNull { it.habitId == id && it.asks }
    }

    /** The quiet note after answering from a notification: "Went · 2 of 3 this week · Next: Thu 17:45 · Pull". */
    fun answeredLine(card: SessionCard): String = listOfNotNull(card.line, card.next).joinToString(" · ")

    private const val ASK_PREFIX = "session:"
    private const val ASK_SUFFIX = ":ask"
}
