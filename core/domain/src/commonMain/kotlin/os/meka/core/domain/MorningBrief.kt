package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Morning brief (build plan M1), non-AI: one look at the day when it starts — work hours, events and what's planned
 * or due, what you're waiting on (and who to chase), anything on the radar that needs you, habits and a running fast,
 * and a few news headlines from the topics Meka chose (mirrored by the server from public feeds, see [News]).
 *
 * It is offered from the end of quiet hours (07:00 when quiet hours are off or end outside 05:00–10:00) until noon.
 * "Got it" is stored on one `context_mode` entity with the fixed id [ENTITY_ID] (the local day and when), so reading
 * it on the Mac also puts the Fold's card away. Both fields are plain last-writer-wins. Nothing is changed by the
 * brief itself: it only reads what the other parts of MEKA already know.
 */
object BriefFields {
    /** Local epoch day the brief was last read ("Got it"). */
    const val SEEN_DAY = "briefSeenDay"
    const val SEEN_AT = "briefSeenAtMs"
    /** The device that read it (its device id), so the other one can say "Brief read on your Mac". */
    const val SEEN_BY = "briefSeenBy"
    /** That device's name as its app gives it ("Mac", "Fold"); empty when unknown. */
    const val SEEN_ON = "briefSeenOn"
    /** "A morning brief worth listening to", slice 3: Short · Standard · Full ([BriefLength.id]); plain last-writer-wins. */
    const val LENGTH = "briefLength"
    /**
     * Slice 6: the one-liners said in the last 30 days, "epochDay<TAB>text" per line, oldest first
     * ([BriefOneLinerRules]); plain last-writer-wins.
     */
    const val LINES = "briefLines"
}

/** Something on the radar or in Lists that needs you today: a renewal, a cancel-by date, a decision to review. */
data class BriefLine(val id: String, val title: String, val detail: String?)

data class MorningBriefView(
    /** Show the "Morning brief" card in Today: it's the morning and it hasn't been read this morning. */
    val offered: Boolean,
    /** Read since this morning's brief started (a read after midnight but before the start was last night's). */
    val seenToday: Boolean,
    /** Local minute of the day the brief starts today. */
    val startMinute: Int,
    /** "Good morning" (before noon), "Good afternoon", "Good evening": the brief can be opened any time. */
    val greeting: String,
    /** "Tue 6 Oct" */
    val dateLabel: String,
    /** "Work 09:00–17:30" when today is a work day, else null. */
    val workLine: String?,
    /** Today in time order: all-day events, then events and planned tasks, then open tasks with no time. */
    val day: List<TomorrowRow>,
    val eventCount: Int,
    val taskCount: Int,
    /** "2 events · 4 tasks · first at 09:30" or "Nothing planned yet". */
    val daySummary: String,
    val overdueCount: Int,
    /** Open waiting-for items: chases due first (at most [BriefRules.MAX_WAITING]). */
    val waiting: List<WaitingItem>,
    val waitingTotal: Int,
    /** "Waiting on 3 things · 1 to chase today"; null when you're waiting on nothing. */
    val waitingLine: String?,
    /** Renewals, bills and cancel-by dates that need doing, and decisions due a review. */
    val attention: List<BriefLine>,
    /** "1 habit behind · 2 to do today"; null with no habits. */
    val habitsLine: String?,
    /** "Fasting since 20:05 yesterday · goal at 12:05"; null when not fasting. */
    val fastingLine: String?,
    /**
     * The card's second line (Fold review 2026-10-09 07:26, item 4): the weather, the day's counts and the next Barça
     * match: "16° · drizzle from 15:00 · 2 tasks · Barça v Getafe tomorrow 17:30 · 1 to chase".
     */
    val cardLine: String,
    /** Up to [NewsRules.MAX_IN_BRIEF] headlines from the chosen topics, newest first, each with its source named. */
    val headlines: List<BriefHeadline> = emptyList(),
    /** Every topic, with the ones the brief shows marked (synced). */
    val newsTopics: List<NewsTopicChoice> = emptyList(),
    /**
     * "Brief read on your Mac": in the morning, when the brief was read on the other device, Today shows this slim
     * line (with Open) in the card's place until noon; null otherwise.
     */
    val readElsewhereLine: String? = null,
    /** Today's weather under the date (Weather slice 2): "9–15°, light rain from 15:00 — take a coat"; set by the facade. */
    val weatherLine: String? = null,
    /** School rhythm, slice 2: "Theo: PE today" under the weather ([SchoolRules.todayLine]); also on the card's line. */
    val school: SchoolLine? = null,
    /** A morning brief worth listening to, slice 1: what the spoken brief adds ([BriefScriptRules.script]); set by the facade. */
    val story: BriefStory = BriefStory.EMPTY,
    /** Protected time, slice 5: "Protected from 19:00 tonight" under the date ([ProtectedRules.briefLine]); null with none. */
    val protectedLine: String? = null,
    /** Week board slice 5: "3 carried over · 5 planned · about 4h" under the day's summary ([BoardTodayRules.plan]); null with nothing to do. */
    val planLine: String? = null,
) {
    /** [day] as the brief pane draws it (Fold review 2026-10-09 07:26, item 7): see [BriefRules.dayLine]. */
    val dayLines: List<BriefDayLine> get() = day.map(BriefRules::dayLine)

    /** Slice 3: the tasks the spoken brief names, with why ("Overdue"), as the pane's Focus rows ([BriefCardRules]). */
    val focusLines: List<BriefDayLine> get() = BriefCardRules.focusLines(story)
    /** "+ 4 smaller ones on Today" under the focus; null for none. */
    val focusMore: String? get() = BriefCardRules.focusMore(story)
    /** "1 decision waiting in Needs you"; null for none. */
    val needsYouLine: String? get() = BriefCardRules.needsYouLine(story)
    /** Slice 4: requests from watched people and urgent voice messages, urgent first ("Waiting on you" in the pane). */
    val asks: List<BriefAsk> get() = BriefCardRules.askLines(story)
    /** Today's card's third line: "Focus · Renew passport · overdue · + 2 more"; null with no focus. */
    val focusCardLine: String? get() = BriefCardRules.cardLine(story)
    /** The one-liner the spoken brief closes with ([BriefScriptRules.oneLiner]), at the foot of the pane. */
    val oneLiner: String get() = BriefScriptRules.oneLiner(story)
    /** Short · Standard · Full, the chosen one lit. */
    val lengths: List<BriefLengthChoice> get() = BriefCardRules.lengths(story.length)

    companion object {
        val EMPTY = MorningBriefView(
            offered = false, seenToday = false, startMinute = BriefRules.DEFAULT_START_MIN, greeting = "Good morning",
            dateLabel = "", workLine = null, day = emptyList(), eventCount = 0, taskCount = 0, daySummary = "Nothing planned yet",
            overdueCount = 0, waiting = emptyList(), waitingTotal = 0, waitingLine = null, attention = emptyList(),
            habitsLine = null, fastingLine = null, cardLine = "",
        )
    }
}

/**
 * One row of the brief's Today section as the pane draws it (Fold review 2026-10-09 07:26, item 7): every row's title
 * sits on one left edge in the regular `body` weight, after a 22 dp lead — a tick circle for a task ([taskId] set;
 * tapping it completes the task), a small dot for an event — and the time moves into [caption] ("09:30 · Room 4",
 * "All day", "14:00 · ↻ Daily", "Overdue"). [lit] colours the caption in the accent (an overdue task). [spoken] is
 * the screen reader's label.
 */
data class BriefDayLine(
    val id: String,
    val taskId: String?,
    val title: String,
    val caption: String?,
    val lit: Boolean,
    val spoken: String,
)

/** Pure rules, unit-tested without a replica. */
object BriefRules {
    /** A brief row ([TomorrowRow]: events "e-<id>", tasks "t-<id>") as the pane draws it. */
    fun dayLine(r: TomorrowRow): BriefDayLine {
        val taskId = if (!r.isEvent && r.id.startsWith("t-")) r.id.removePrefix("t-") else null
        val caption = listOfNotNull(r.time, r.detail).joinToString(" · ").ifEmpty { null }
        val lit = taskId != null && r.detail?.startsWith("Overdue") == true
        val spoken = listOfNotNull(if (taskId != null) "Task" else "Event", r.title, caption).joinToString(", ")
        return BriefDayLine(r.id, taskId, r.title, caption, lit, spoken)
    }

    /**
     * "Work 09:00–17:30" on a work day; on a bank holiday that would have been one, "Christmas Day · no work"; else null.
     * Shared by the brief and the shutdown's tomorrow preview.
     */
    fun workLine(schedule: WorkSchedule, holidays: HolidayCalendar, epochDay: Long): String? {
        val iso = CivilDate.isoDayOfWeek(epochDay)
        if (!schedule.enabled || iso !in schedule.days) return null
        holidays.title(epochDay)?.let { return "$it · no work" }
        return "Work ${schedule.hoursOn(iso).label}"
    }

    /** When the brief starts when quiet hours don't say otherwise. */
    const val DEFAULT_START_MIN = 7 * 60
    /** Quiet hours that end inside this range start the brief; outside it (a night owl, a night shift) 07:00 does. */
    val QUIET_END_RANGE = 5 * 60..10 * 60
    /** The card goes at noon (the brief can still be opened from Today's header). */
    const val END_MIN = 12 * 60
    /** Waiting-for items shown in the brief; the rest are in Lists. */
    const val MAX_WAITING = 5
    /**
     * Calm Today: the brief counts as read once it has been open this long (Got it still says so at once), so the card
     * folds away without a tap. A pane opened by mistake and closed straight away leaves the card where it was.
     */
    const val READ_AFTER_MS = 5_000L

    /** True when a brief that was open for [openForMs] has been read (see [READ_AFTER_MS]). */
    fun readByLooking(openForMs: Long): Boolean = openForMs >= READ_AFTER_MS

    /**
     * Whether closing the pane after [openForMs] should put the card away: only while the card is offered (this
     * morning, not read yet), so opening the brief in the evening or after noon never marks tomorrow's as read.
     */
    fun putAwayOnClose(view: MorningBriefView, openForMs: Long): Boolean =
        view.offered && !view.seenToday && readByLooking(openForMs)

    fun startMinute(quiet: QuietHours): Int =
        if (quiet.enabled && quiet.startMinute != quiet.endMinute && quiet.endMinute in QUIET_END_RANGE) quiet.endMinute else DEFAULT_START_MIN

    /**
     * Whether a read at [seenAtMs] (on [seenDay]) puts away the brief of [epochDay], which starts at [startMs]. Once the
     * morning has started ([nowMs] at or after [startMs]), only a read on that day at or after the start does: reading
     * it at 00:30 (last night's, still open) leaves the morning's card to come. Before the start, any read that day
     * counts (the pane says Done). An old read with no time recorded counts for its whole day.
     */
    fun readThisMorning(seenDay: Long?, seenAtMs: Long?, epochDay: Long, startMs: Long, nowMs: Long): Boolean =
        seenDay == epochDay && (seenAtMs == null || seenAtMs >= startMs || nowMs < startMs)

    /** "Brief read on your Mac"; "Brief read on your other device" when its name is unknown. */
    fun readElsewhereLine(on: String?): String =
        "Brief read on your " + (on?.trim()?.takeIf { it.isNotEmpty() && it.length <= 20 } ?: "other device")

    fun greeting(minute: Int): String = when {
        minute < 12 * 60 -> "Good morning"
        minute < 18 * 60 -> "Good afternoon"
        else -> "Good evening"
    }

    /** "Waiting on 3 things · 1 to chase today"; null for none. */
    fun waitingLine(total: Int, due: Int): String? {
        if (total == 0) return null
        val head = "Waiting on " + ShutdownRules.count(total, "thing")
        return if (due > 0) "$head · $due to chase today" else head
    }

    /** The fixture's title in the card is shortened at a word past this many characters. */
    const val MAX_FIXTURE_CHARS = 32

    /** What the fixtures feed adds to a title whose kick-off isn't set yet. */
    private const val TBC_SUFFIX = "(kick-off TBC)"

    /**
     * The card's second line (Fold review 2026-10-09 07:26, item 4: "2 tasks" said nothing). The weather now and what the
     * rest of today does ([WeatherRules.nowLine], when the forecast has it), the day's counts without "first at" (the
     * pane has that), the next Barça match today or tomorrow ([fixtureLine]), then chases and what's on your lists:
     * "16° · drizzle from 15:00 · 2 tasks · Barça v Getafe tomorrow 17:30 · 1 to chase". "Nothing planned yet" when the
     * day is empty.
     */
    fun cardLine(
        weather: String?, eventCount: Int, taskCount: Int, fixture: String?, chaseDue: Int, attention: Int,
        school: String? = null,
    ): String = listOfNotNull(
        weather?.trim()?.takeIf { it.isNotEmpty() },
        school?.trim()?.takeIf { it.isNotEmpty() },
        if (eventCount == 0 && taskCount == 0) "Nothing planned yet"
        else listOfNotNull(
            ShutdownRules.count(eventCount, "event").takeIf { eventCount > 0 },
            ShutdownRules.count(taskCount, "task").takeIf { taskCount > 0 },
        ).joinToString(" · "),
        fixture,
        chaseDue.takeIf { it > 0 }?.let { "$it to chase" },
        attention.takeIf { it > 0 }?.let { ShutdownRules.count(it, "thing") + " on your lists" },
    ).joinToString(" · ")

    /**
     * The next match from the fixtures feed that hasn't finished and starts today or tomorrow (not all-day): "Barça v
     * Getafe today 17:30", "Barça v Getafe tomorrow 17:30", "Barça v Getafe on now" once it has started, "Barça v Getafe
     * tomorrow" while the kick-off is to be confirmed. Pass the events shown on my day, so a hidden fixture stays hidden.
     * Null when there's no match today or tomorrow.
     */
    fun fixtureLine(events: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar): String? {
        val today = cal.epochDayOf(nowMs)
        val e = events.filter { it.isFixture && !it.allDay && it.endAtMs > nowMs && cal.epochDayOf(it.startAtMs) in today..today + 1 }
            .minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id }) ?: return null
        val tbc = e.title.trimEnd().endsWith(TBC_SUFFIX)
        val title = shorten(e.title.trimEnd().removeSuffix(TBC_SUFFIX).trim().ifEmpty { "Barça" })
        val day = if (cal.epochDayOf(e.startAtMs) == today) "today" else "tomorrow"
        return when {
            tbc -> "$title $day"
            nowMs >= e.startAtMs -> "$title on now"
            else -> "$title $day ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))}"
        }
    }

    /** At most [MAX_FIXTURE_CHARS], cut at a word with "…". */
    private fun shorten(title: String): String {
        if (title.length <= MAX_FIXTURE_CHARS) return title
        val at = title.take(MAX_FIXTURE_CHARS + 1).lastIndexOf(' ')
        return (if (at >= MAX_FIXTURE_CHARS / 2) title.take(at) else title.take(MAX_FIXTURE_CHARS)).trimEnd(' ', '·', '-', ',') + "…"
    }
}

class MorningBrief(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    /** Short · Standard · Full (slice 3); Standard until chosen. */
    fun length(): BriefLength = BriefLength.of(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(BriefFields.LENGTH)?.textOrNull)

    /** Chooses how long the spoken brief is, on every device. */
    fun setLength(length: BriefLength) {
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(BriefFields.LENGTH to length.id.fv()))
    }

    /** Slice 6: the one-liners said in the last 30 days, oldest first. */
    fun lines(): List<BriefLineMemory> =
        BriefOneLinerRules.decode(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(BriefFields.LINES)?.textOrNull)

    /**
     * Remembers [text] as [day]'s one-liner on every device, unless [day] already has one (the first said stands, so
     * both devices close with the same line). Returns the day's line.
     */
    fun rememberLine(day: Long, text: String): String {
        val now = lines()
        BriefOneLinerRules.today(now, day)?.let { return it }
        val next = BriefOneLinerRules.remember(now, day, text)
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(BriefFields.LINES to BriefOneLinerRules.encode(next).fv()))
        return BriefOneLinerRules.today(next, day) ?: text
    }

    fun seenDay(): Long? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(BriefFields.SEEN_DAY)?.longOrNull

    /** "Got it": the card is put away on every device until tomorrow morning. [on] names this device ("Mac", "Fold"). */
    fun markSeen(on: String = "") {
        val now = nowMs()
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(
                BriefFields.SEEN_DAY to calendar.epochDayOf(now).fv(), BriefFields.SEEN_AT to now.fv(),
                BriefFields.SEEN_BY to replica.deviceId.fv(), BriefFields.SEEN_ON to on.fv(),
            ),
        )
    }

    fun view(
        all: List<Task>,
        events: List<CalendarEvent>,
        schedule: WorkSchedule,
        quiet: QuietHours,
        lists: ListsView,
        goals: GoalsView,
        fasting: FastingView,
        today: DayWindow,
        headlines: List<Headline> = emptyList(),
        newsTopics: List<NewsTopicChoice> = NewsTopics.ALL.map { NewsTopicChoice(it.id, it.label, it.id in NewsTopics.DEFAULT) },
        /** Bank holidays are days off: "Christmas Day · no work" instead of the work hours. */
        holidays: HolidayCalendar = HolidayCalendar.NONE,
        /** The weather now and the rest of today ([WeatherRules.nowLine]) for the card; null when there's no forecast. */
        weatherNow: String? = null,
        /** What the school says about today ([SchoolRules.todayLine]). */
        school: SchoolLine? = null,
        /** Today's Up next, named in the spoken brief's focus when nothing more pressing is ([BriefScriptRules.focus]). */
        upNextId: String? = null,
        /**
         * Today's full work hours (work from home, leave and the commute laid over the schedule); with it, the brief's
         * clashes count the commute too ("… before you're home at about ten to 7"). Null: the schedule alone.
         */
        work: WorkHours? = null,
        /** Whose club training or fixture a series is ([ClubChildRules]), for "Who's taking …?". */
        children: Map<String, String> = emptyMap(),
        /** Full news hub, slice 3: the words he follows; stories naming them lead the headlines ([NewsRules.forBrief]). */
        newsFollows: List<String> = emptyList(),
        /** Full news hub, slice 4: the stories he has read ([News.readKeys]); the brief leads with new ones. */
        newsRead: Set<String> = emptySet(),
    ): MorningBriefView {
        val now = nowMs()
        val minute = calendar.minuteOfDay(now)
        val start = BriefRules.startMinute(quiet)
        val mark = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)
        val seenToday = BriefRules.readThisMorning(
            mark?.get(BriefFields.SEEN_DAY)?.longOrNull, mark?.get(BriefFields.SEEN_AT)?.longOrNull,
            today.epochDay, calendar.toEpochMs(today.epochDay, start), now,
        )
        val morning = minute >= start && minute < BriefRules.END_MIN
        val readElsewhere = seenToday && morning && mark?.get(BriefFields.SEEN_BY)?.textOrNull.let { it != null && it != replica.deviceId }

        // Today: events of the day and what's left of it (planned, overdue, due, and the rest Today shows).
        val dayEvents = events.filter { it.overlaps(today) }
        val dayTasks = ShutdownRules.left(all, today)
        data class Keyed(val key: Long, val row: TomorrowRow)
        val keyed = buildList {
            dayEvents.forEach { e ->
                val (time, key) = when {
                    e.allDay -> "All day" to Long.MIN_VALUE
                    e.startAtMs < today.startMs -> "Until ${hhmm(e.endAtMs)}" to today.startMs
                    else -> hhmm(e.startAtMs) to e.startAtMs
                }
                val detail = listOfNotNull(e.location, if (e.provider == "fixtures") "Fixtures" else null).joinToString(" · ").ifEmpty { null }
                add(Keyed(key, TomorrowRow("e-" + e.id, time, e.title, true, detail)))
            }
            dayTasks.filter { it.scheduledAtMs != null && it.scheduledAtMs >= today.startMs }.forEach { t ->
                add(Keyed(t.scheduledAtMs!!, TomorrowRow("t-" + t.id, hhmm(t.scheduledAtMs), t.title, false, t.repeatLabel?.let { "↻ $it" })))
            }
        }.sortedWith(compareBy<Keyed> { it.key }.thenBy { it.row.title })
        val untimed = dayTasks.filter { it.scheduledAtMs == null || it.scheduledAtMs < today.startMs }.map { t ->
            val detail = listOfNotNull(
                when {
                    t.dueAtMs != null && t.dueAtMs < now -> "Overdue"
                    t.dueAtMs != null -> "Due ${hhmm(t.dueAtMs)}"
                    t.scheduledAtMs != null -> "Planned ${CivilDate.shortLabel(calendar.epochDayOf(t.scheduledAtMs))}"
                    else -> null
                },
                t.repeatLabel?.let { "↻ $it" },
            ).joinToString(" · ").ifEmpty { null }
            TomorrowRow("t-" + t.id, null, t.title, false, detail)
        }
        val rows = keyed.map { it.row } + untimed
        val first = keyed.firstOrNull { it.key > today.startMs || (it.key == today.startMs && !it.row.time!!.startsWith("Until")) }?.row?.time
        val daySummary = if (rows.isEmpty()) "Nothing planned yet" else listOfNotNull(
            ShutdownRules.count(dayEvents.size, "event").takeIf { dayEvents.isNotEmpty() },
            ShutdownRules.count(dayTasks.size, "task").takeIf { dayTasks.isNotEmpty() },
            first?.let { "first at $it" },
        ).joinToString(" · ")
        val overdue = dayTasks.count { it.dueAtMs != null && it.dueAtMs < now }
        val plan = BoardTodayRules.plan(dayTasks, all, today)

        val workLine = BriefRules.workLine(schedule, holidays, today.epochDay)

        // Lists: what you're waiting on (already sorted: due chases first), and what needs doing on the radar.
        val attention = buildList {
            lists.renewals.attention.forEach { add(BriefLine("r-" + it.id, it.title, it.meta)) }
            lists.decisions.filter { it.state == DueState.DUE }.forEach { add(BriefLine("d-" + it.id, "Review: ${it.statement}", it.meta)) }
        }

        val fastingLine = fasting.current?.let { f ->
            if (f.extended) "${f.title} · ${f.dayLine}"
            else "Fasting · ${f.startedLine.replaceFirstChar { it.lowercase() }} · " +
                if (f.reachedGoal) "goal reached" else "goal at ${hhmm(f.goalAtMs)}"
        }

        return MorningBriefView(
            offered = !seenToday && morning,
            seenToday = seenToday,
            startMinute = start,
            greeting = BriefRules.greeting(minute),
            dateLabel = CivilDate.shortLabel(today.epochDay),
            workLine = workLine,
            day = rows,
            eventCount = dayEvents.size,
            taskCount = dayTasks.size,
            daySummary = daySummary,
            planLine = plan.line,
            overdueCount = overdue,
            waiting = lists.waiting.take(BriefRules.MAX_WAITING),
            waitingTotal = lists.waiting.size,
            waitingLine = BriefRules.waitingLine(lists.waiting.size, lists.chaseDue),
            attention = attention,
            habitsLine = goals.paceLine,
            fastingLine = fastingLine,
            cardLine = BriefRules.cardLine(
                weatherNow, dayEvents.size, dayTasks.size, BriefRules.fixtureLine(events, now, calendar), lists.chaseDue, attention.size,
                school?.text,
            ),
            school = school,
            headlines = NewsRules.forBrief(headlines, newsTopics.filter { it.chosen }.map { it.id }, now, newsFollows, newsRead),
            newsTopics = newsTopics,
            readElsewhereLine = if (readElsewhere) BriefRules.readElsewhereLine(mark?.get(BriefFields.SEEN_ON)?.textOrNull) else null,
            story = story(dayEvents, dayTasks, schedule, holidays, goals, today, now, workLine, upNextId, work, children).copy(plan = plan.sentence),
        )
    }

    /** The spoken brief's own facts (slice 1): timed events, the focus, habits by name and the week's goals. */
    private fun story(
        dayEvents: List<CalendarEvent>, dayTasks: List<Task>, schedule: WorkSchedule, holidays: HolidayCalendar,
        goals: GoalsView, today: DayWindow, now: Long, workLine: String?, upNextId: String? = null,
        work: WorkHours? = null, children: Map<String, String> = emptyMap(),
    ): BriefStory {
        val events = dayEvents.filter { !it.allDay && it.startAtMs >= today.startMs }.sortedBy { it.startAtMs }.map { e ->
            val start = calendar.minuteOfDay(e.startAtMs)
            val end = start + ((e.endAtMs - e.startAtMs) / 60_000L).toInt().coerceAtLeast(0)
            BriefEvent(e.title, start, end)
        }
        val weekIds = goals.week.arcs.filter { !it.done }.associate { it.goalId to it.title }
        val iso = CivilDate.isoDayOfWeek(today.epochDay)
        val working = workLine?.startsWith("Work ") == true
        // Fold review 2026-10-12, bug 4: what falls during work today, said with the child's name where it's theirs.
        val hours = work ?: WorkHours(schedule, holidays)
        val workBlocks = if (working) hours.blocks(today.epochDay, calendar) else emptyList()
        // The commute counts everywhere, slice 3: on an office day, also what starts before he's home or after he's left.
        val commute = if (working) CommuteRules.bands(hours, today.epochDay, calendar) else emptyList()
        val workClashes = WorkClashRules.clashes(dayEvents.filter { it.endAtMs > now }, workBlocks, calendar, children, commute).map { c ->
            c.spoken(FootballRules.spokenFixture(c.title) ?: c.title) { BriefScriptRules.clock(it) }
        }
        val weekend = iso >= 6 && !working
        val length = length()
        val (focus, smaller) = BriefScriptRules.focus(
            dayTasks, today, now, weekIds, upNextId, length.focusFor(weekend),
        ) { calendar.minuteOfDay(it) }
        val said = lines()
        return BriefStory(
            epochDay = today.epochDay,
            oneLiner = BriefOneLinerRules.today(said, today.epochDay),
            recentLines = BriefOneLinerRules.recent(said, today.epochDay),
            weekend = weekend,
            workEndMin = if (working) schedule.hoursOn(iso).endMinute else null,
            leaveMin = commute.firstOrNull { it.toWork }?.startMinute,
            homeMin = commute.firstOrNull { !it.toWork }?.endMinute,
            events = events,
            focus = focus,
            smallerCount = smaller,
            habits = BriefScriptRules.habits(goals.habits),
            weekGoals = goals.week.line,
            workClashes = workClashes,
            goalTitle = weekIds.values.firstOrNull(),
            length = length,
        )
    }

    private fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))

    companion object {
        const val ENTITY_ID = "brief"
    }
}
