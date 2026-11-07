package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Routines (build plan "Take first", Meka approved the mockup 2026-11-06 21:34). Slice 3b starts them at their time
 * with a quiet notice through the governor ([RoutineRules.notices], [Routines.startDue]). Slice 3 runs them by hand: Run now,
 * the Running now card (Carry on · Skip this step · Stop), and LAST TIME. Slice 1: jobs Obi repeats for you,
 * each a name, when it runs (days of the week and a time, or on payday) and its steps, with an on/off switch. Slice 1
 * keeps the list, the starters and the switch; running them (the Running now card, through the notification governor
 * at quiet times), a routine's own page and New routine in one sentence come next.
 *
 * One synced `routine` entity per routine (ADR-008 addendum 2026-11-06). Each step names the Navilife place it uses and
 * how it runs: Just show (it shows what's there), Asks you (it waits for your tap) or Obi does it, offered only for what
 * Navilife already does by itself (tidy the inbox). **The hard rule:** a step can never send a message, pay, book or
 * trade ([RoutineRules.refusal]); such a step is never written, and one read back from storage is dropped, never run.
 * Plain, non-AI rules; nothing here leaves Meka's own synced data.
 */
object RoutineFields {
    const val NAME = "name"
    /** The ISO days of the week it runs on (1 = Monday), as digits: "7" (Sundays), "12345" (weekdays). */
    const val DAYS = "days"
    /** Runs on payday instead of on [DAYS]. */
    const val PAYDAY = "payday"
    /** Minute of the day it runs at (0..1439). */
    const val MINUTE = "minute"
    /** One step a line: "show|MONEY|Show the week ahead of bills" ([RoutineRules.encodeSteps]). */
    const val STEPS = "steps"
    /** The short line under its name ("Bills, plan the week, inbox, bins"); made from the steps when absent. */
    const val SUMMARY = "summary"
    /** Switched on (true) or paused. */
    const val ON = "on"
    /** The starter it came from ([RoutineStarter.key]), so Ideas doesn't offer it twice. */
    const val STARTER = "starter"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
    // Slice 3 (2026-11-06): the run going now and the last one finished (additive fields, latest wins).
    /** When the run going now started (epoch ms); 0 or absent when it isn't running. */
    const val RUN_AT = "runAtMs"
    /** The step the run going now is on (0-based). */
    const val RUN_STEP = "runStep"
    /** What each step before [RUN_STEP] came to, one line each: "d|Plan next week" ([RoutineOutcome.code]). */
    const val RUN_LOG = "runLog"
    /** When the last run finished (epoch ms). */
    const val LAST_AT = "lastRunAtMs"
    /** What each step of the last run came to, the same shape as [RUN_LOG]. */
    const val LAST_LOG = "lastRunLog"
}

/** What a step came to in a run. */
enum class RoutineOutcome(val code: Char, val label: String) {
    SHOWN('s', "Shown"),
    DONE('d', "Done"),
    SKIPPED('k', "Skipped"),
    /** An "Obi does it" step: Navilife's own Inbox autopilot does it, so the run doesn't wait on you. */
    LEFT('o', "Left to Inbox autopilot"),
    /** The run stopped before it reached this step. */
    NOT_REACHED('n', "Not reached"),
}

/** One step's line in a run's log. */
data class RoutineLogLine(val title: String, val outcome: RoutineOutcome) {
    /** What it came to, in words ("Done", "Skipped"). */
    val outcomeLabel: String get() = outcome.label
    /** Skipped or not reached: drawn quieter. */
    val quiet: Boolean get() = outcome == RoutineOutcome.SKIPPED || outcome == RoutineOutcome.NOT_REACHED
}

/** A run going now: [step] is the step it's on (0-based); [log] what the steps before it came to. */
data class RoutineRun(val startedAtMs: Long, val step: Int, val log: List<RoutineLogLine>)

/** The last run finished: when, and what each step came to. */
data class RoutineLastRun(val atMs: Long, val log: List<RoutineLogLine>)

/**
 * The Running now card (slice 3, the mockup's): "Sunday reset", "Step 2 of 4", the step it's on with its place, and
 * whether it waits on you (Asks you: Carry on · Skip this step) or only shows (Just show: Carry on · Skip this step).
 */
data class RoutineRunCard(
    val id: String,
    val name: String,
    /** "Step 2 of 4". */
    val progress: String,
    /** 0.0..1.0 of the steps behind it, for the card's bar. */
    val fraction: Float,
    val stepTitle: String,
    /** The Navilife place the step uses ("Week board"). */
    val place: String,
    /** "Waiting on you" for an Asks you step, "Have a look" for a Just show one. */
    val state: String,
    val waitingOnYou: Boolean,
    val spoken: String,
)

/** How a step runs: Just show · Asks you · Obi does it. */
enum class RoutineStepMode(val code: String, val label: String) {
    SHOW("show", "Just show"),
    ASK("ask", "Asks you"),
    DOES("does", "Obi does it"),
}

/** The Navilife place a step uses ("Money", "Week board"); [obiMayDo] where Navilife already acts by itself. */
enum class RoutinePlace(val label: String, val obiMayDo: Boolean = false) {
    TODAY("Today"),
    CALENDAR("Calendar"),
    NEEDS_YOU("Needs you"),
    MONEY("Money"),
    PLAN("Plan"),
    WEEK_BOARD("Week board"),
    INBOX("Inbox autopilot", obiMayDo = true),
    HOME("Home"),
    STUDY("Study"),
}

data class RoutineStep(val title: String, val place: RoutinePlace, val mode: RoutineStepMode)

/** When a routine runs: on [days] (ISO, 1 = Monday) at [minute], or on payday at [minute]. */
data class RoutineSchedule(val days: Set<Int>, val minute: Int, val payday: Boolean = false)

data class RoutineItem(
    val id: String,
    val name: String,
    val schedule: RoutineSchedule,
    val steps: List<RoutineStep>,
    val summary: String,
    val on: Boolean,
    val starter: String?,
    val addedAtMs: Long,
    /** Slice 3: the run going now, or null. */
    val run: RoutineRun? = null,
    /** Slice 3: the last run finished, or null before the first. */
    val lastRun: RoutineLastRun? = null,
)

/** A ready-made routine offered under START WITH ONE until it's added. */
data class RoutineStarter(
    val key: String,
    val name: String,
    val schedule: RoutineSchedule,
    val summary: String,
    val steps: List<RoutineStep>,
)

/** A row under YOUR ROUTINES: the day tile ("SUN" over "19:00"), the name, the line and the switch. */
data class RoutineRow(
    val id: String,
    val name: String,
    /** "SUN", "MON–FRI", "DAILY", "PAYDAY". */
    val day: String,
    /** "19:00". */
    val time: String,
    /** "Bills, plan the week, inbox, bins", or "Paused · …" while off. */
    val line: String,
    /** "Next: Sun 8 Nov", "Next: tonight"; null while paused. */
    val next: String?,
    val on: Boolean,
    /** "Pause Sunday reset" / "Switch on Sunday reset" for TalkBack / VoiceOver on the switch. */
    val switchSpoken: String,
    /** One sentence for TalkBack / VoiceOver on the row. */
    val spoken: String,
)

/** A starter under START WITH ONE: the same tile, its name and line, and Add. */
data class RoutineStarterRow(
    val key: String,
    val name: String,
    val day: String,
    val time: String,
    val line: String,
    /** "Every Sunday at 19:00". */
    val whenLine: String,
    val spoken: String,
)

data class RoutinesView(
    /** "2 on · 1 paused", or the empty line. */
    val summary: String,
    val rows: List<RoutineRow>,
    val starters: List<RoutineStarterRow>,
    /** Slice 2: each routine's own page, by id ([RoutineRules.detail]). */
    val details: List<RoutineDetail> = emptyList(),
    /** Slice 3: the Running now cards, the oldest run first. */
    val running: List<RoutineRunCard> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    fun detail(id: String): RoutineDetail? = details.firstOrNull { it.id == id }

    companion object {
        val EMPTY = RoutinesView(RoutineRules.EMPTY_LINE, emptyList(), emptyList())
    }
}

/** One step on a routine's page: "1" · "Show the week ahead of bills" · "Money" · "Just show". */
data class RoutineStepRow(
    val index: Int,
    /** "1", "2". */
    val number: String,
    val title: String,
    /** The Navilife place it uses ("Money", "Week board"). */
    val place: String,
    val mode: RoutineStepMode,
    /** The badge's words ("Just show", "Asks you", "Obi does it"). */
    val badge: String,
    /** The label a tap on the badge moves to ("Asks you"); null when it has only one choice. */
    val nextBadge: String?,
    val spoken: String,
) {
    /** The label's code ("show", "ask", "does"), for the apps' colours. */
    val modeCode: String get() = mode.code
    /** The code a tap moves the label to ([RoutineStepMode.code]); null when it has only one choice. */
    val nextCode: String? get() = RoutineStepMode.entries.firstOrNull { it.label == nextBadge }?.code
}

/**
 * A routine's own page (slice 2, the mockup's detail view): its name and when it runs, the NEXT · STEPS · LAST RUN
 * tiles, its steps each with its label (a tap moves it on: Just show → Asks you → Obi does it, the last only where
 * Navilife already acts by itself), and LAST TIME.
 */
data class RoutineDetail(
    val id: String,
    val name: String,
    /** "Every Sunday at 19:00". */
    val whenLine: String,
    /** The NEXT tile: "Sun 8 Nov", "Tonight", "Tomorrow", "On payday", or "Paused". */
    val next: String,
    /** The STEPS tile: "4". */
    val stepCount: String,
    /** The LAST RUN tile: "Not yet", "Today 19:02", "Yesterday", "Sun 1 Nov". */
    val lastRun: String,
    val steps: List<RoutineStepRow>,
    /** LAST TIME: what happened the last time it ran ("Sun 1 Nov at 19:02 · 3 done, 1 skipped"). */
    val history: String,
    val on: Boolean,
    /** The labels' note under the steps. */
    val stepsNote: String,
    /** Slice 3: LAST TIME's steps, each with what it came to. */
    val lastSteps: List<RoutineLogLine> = emptyList(),
    /** Slice 3: its Running now card while it runs. */
    val running: RoutineRunCard? = null,
    /** Slice 3: "Run now", or null while it runs or has no steps. */
    val runNow: String? = null,
)

/** What adding or changing a routine said, and whether nothing was written. */
data class RoutineSaid(val line: String, val refused: Boolean, val id: String? = null)

object RoutineRules {
    const val TITLE = "Routines"
    const val ROW_LINE = "Jobs Obi repeats for you"
    const val YOURS = "Your routines"
    const val START_WITH = "Start with one"
    const val EMPTY_LINE = "No routines yet · start with one below"
    const val ADD = "Add"
    const val REMOVE = "Remove"
    /** The rule, said once on the page. */
    const val RULE_LINE = "A routine never sends a message, pays, books or trades. Obi only does what Navilife already does by itself, like tidying the inbox."
    const val REFUSED_LINE = "Routines never send messages, pay, book or trade"
    const val GONE_LINE = "That routine isn't there any more"
    const val ALREADY_LINE = "You already have that routine"
    const val MAX_STEPS = 8
    const val MAX_NAME = 60

    // ---- The starters (the approved mockup's four; generic, nothing personal) ----

    val STARTERS: List<RoutineStarter> = listOf(
        RoutineStarter(
            "sunday-reset", "Sunday reset", RoutineSchedule(setOf(7), 19 * 60), "Bills, plan the week, inbox, bins",
            listOf(
                RoutineStep("Show the week ahead of bills", RoutinePlace.MONEY, RoutineStepMode.SHOW),
                RoutineStep("Plan next week", RoutinePlace.WEEK_BOARD, RoutineStepMode.ASK),
                RoutineStep("Tidy the inbox", RoutinePlace.INBOX, RoutineStepMode.DOES),
                RoutineStep("Bins and car dates", RoutinePlace.HOME, RoutineStepMode.SHOW),
            ),
        ),
        RoutineStarter(
            "morning-brief", "Morning brief", RoutineSchedule(Recurrence.WEEKDAYS, 7 * 60), "Weather, trains, today, one money line",
            listOf(
                RoutineStep("Weather and trains", RoutinePlace.TODAY, RoutineStepMode.SHOW),
                RoutineStep("What today holds", RoutinePlace.CALENDAR, RoutineStepMode.SHOW),
                RoutineStep("Anything due", RoutinePlace.NEEDS_YOU, RoutineStepMode.SHOW),
            ),
        ),
        RoutineStarter(
            "study-night", "Study night", RoutineSchedule((1..7).toSet(), 21 * 60), "Tonight’s lessons, tick as you go",
            listOf(
                RoutineStep("Open tonight’s lessons", RoutinePlace.STUDY, RoutineStepMode.SHOW),
                RoutineStep("Tick what you finished", RoutinePlace.STUDY, RoutineStepMode.ASK),
                RoutineStep("Move the rest to tomorrow", RoutinePlace.STUDY, RoutineStepMode.ASK),
            ),
        ),
        RoutineStarter(
            "payday-check", "Payday check", RoutineSchedule(emptySet(), 9 * 60, payday = true), "What came in, bills to come, savings",
            listOf(
                RoutineStep("What came in", RoutinePlace.MONEY, RoutineStepMode.SHOW),
                RoutineStep("Bills before next payday", RoutinePlace.MONEY, RoutineStepMode.SHOW),
                RoutineStep("Savings goal on track?", RoutinePlace.PLAN, RoutineStepMode.SHOW),
            ),
        ),
    )

    // ---- The hard rule ----

    private val forbidden = Regex(
        """\b(send|sends|sending|sent|reply|replies|text|texts|message|messages|email|emails|whatsapp|call|ring|pay|pays|paying|paid|transfer|transfers|book|books|booking|order|orders|buy|buys|purchase|trade|trades|trading|sell|sells)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why a step can't be part of a routine, or null when it can: it would send a message, pay, book or trade
     * ([REFUSED_LINE]). "Tidy the inbox" and "Show the week ahead of bills" are fine; "Pay the gas bill" and "Text Sam
     * I'm late" are not. Checked on every write and every read.
     */
    fun refusal(title: String): String? = if (forbidden.containsMatchIn(title)) REFUSED_LINE else null

    /** The mode a step really runs with: Obi does it only where Navilife already acts by itself, else it asks. */
    fun effectiveMode(step: RoutineStep): RoutineStepMode =
        if (step.mode == RoutineStepMode.DOES && !step.place.obiMayDo) RoutineStepMode.ASK else step.mode

    // ---- Storage ----

    fun encodeSteps(steps: List<RoutineStep>): String =
        steps.joinToString("\n") { "${it.mode.code}|${it.place.name}|${it.title.replace('\n', ' ').replace('|', '/')}" }

    /** The steps stored, safe: unreadable lines and any step that would send, pay, book or trade are dropped. */
    fun decodeSteps(text: String?): List<RoutineStep> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val mode = RoutineStepMode.entries.firstOrNull { it.code == parts[0] } ?: return@mapNotNull null
        val place = RoutinePlace.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        val title = parts[2].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (refusal(title) != null) return@mapNotNull null
        RoutineStep(title, place, mode).let { it.copy(mode = effectiveMode(it)) }
    }.take(MAX_STEPS)

    fun encodeDays(days: Set<Int>): String = days.filter { it in 1..7 }.sorted().joinToString("")

    fun decodeDays(text: String?): Set<Int> = text.orEmpty().mapNotNull { it.digitToIntOrNull()?.takeIf { d -> d in 1..7 } }.toSet()

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The live routines from each entity's fields (id → fields); unreadable ones are skipped. */
    fun items(entities: Map<String, Map<String, FieldValue>>): List<RoutineItem> = entities.mapNotNull { (id, s) ->
        if (s.f(RoutineFields.DELETED).boolOrNull == true) return@mapNotNull null
        val name = s.f(RoutineFields.NAME).textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val minute = s.f(RoutineFields.MINUTE).longOrNull?.toInt()?.takeIf { it in 0 until 24 * 60 } ?: return@mapNotNull null
        val payday = s.f(RoutineFields.PAYDAY).boolOrNull == true
        val days = decodeDays(s.f(RoutineFields.DAYS).textOrNull)
        if (!payday && days.isEmpty()) return@mapNotNull null
        val steps = decodeSteps(s.f(RoutineFields.STEPS).textOrNull)
        val summary = s.f(RoutineFields.SUMMARY).textOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: steps.take(3).joinToString(", ") { it.title }
        RoutineItem(
            id, name, RoutineSchedule(days, minute, payday), steps, summary,
            on = s.f(RoutineFields.ON).boolOrNull ?: true,
            starter = s.f(RoutineFields.STARTER).textOrNull,
            addedAtMs = s.f(RoutineFields.ADDED_AT).longOrNull ?: 0L,
            run = s.f(RoutineFields.RUN_AT).longOrNull?.takeIf { it > 0 }?.let { at ->
                RoutineRun(at, (s.f(RoutineFields.RUN_STEP).longOrNull ?: 0L).toInt().coerceAtLeast(0), decodeLog(s.f(RoutineFields.RUN_LOG).textOrNull))
            },
            lastRun = s.f(RoutineFields.LAST_AT).longOrNull?.takeIf { it > 0 }?.let { RoutineLastRun(it, decodeLog(s.f(RoutineFields.LAST_LOG).textOrNull)) },
        )
    }

    fun encodeLog(log: List<RoutineLogLine>): String =
        log.joinToString("\n") { "${it.outcome.code}|${it.title.replace('\n', ' ')}" }

    fun decodeLog(text: String?): List<RoutineLogLine> = text.orEmpty().lines().mapNotNull { line ->
        val outcome = RoutineOutcome.entries.firstOrNull { line.length > 2 && line[1] == '|' && it.code == line[0] } ?: return@mapNotNull null
        RoutineLogLine(line.substring(2).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null, outcome)
    }.take(MAX_STEPS)

    /** A run left going longer than this (the phone was put away mid-run) is let go: it no longer shows or blocks Run now. */
    const val RUN_STALE_MS = 12L * 60 * 60 * 1000

    /** The run going now on [r], or null when there's none or it was left going too long ([RUN_STALE_MS]). */
    fun liveRun(r: RoutineItem, nowMs: Long): RoutineRun? =
        r.run?.takeIf { nowMs - it.startedAtMs in 0 until RUN_STALE_MS && it.step < r.steps.size }

    // ---- Slice 3b (2026-11-07): starting at its time ----

    /** A routine whose start was missed by more than this (the phone was off) waits for its next time. */
    const val START_WINDOW_MS = 2L * 60 * 60 * 1000

    /**
     * When [r] starts today by itself (its day and time, epoch ms), or null when it doesn't: paused, on payday (no
     * known date yet), no steps, not today, added after today's time, more than [START_WINDOW_MS] past it, or it ran
     * already today (by hand or by itself, finished or stopped). The run it starts is stamped with this time, so the
     * Fold and the Mac starting it at once write the same run.
     */
    fun startTimeToday(r: RoutineItem, nowMs: Long, cal: LocalCalendar): Long? {
        if (!r.on || r.schedule.payday || r.steps.isEmpty()) return null
        val today = cal.epochDayOf(nowMs)
        if (CivilDate.isoDayOfWeek(today) !in r.schedule.days) return null
        val at = cal.toEpochMs(today, r.schedule.minute)
        if (r.addedAtMs >= at || nowMs - at >= START_WINDOW_MS) return null
        if (r.lastRun?.let { cal.epochDayOf(it.atMs) == today } == true) return null
        val live = liveRun(r, nowMs)
        if (live != null && live.startedAtMs != at) return null
        return at
    }

    /** The time [r] should start right now ([startTimeToday] reached, nothing running), or null. */
    fun dueStart(r: RoutineItem, nowMs: Long, cal: LocalCalendar): Long? =
        startTimeToday(r, nowMs, cal)?.takeIf { nowMs >= it && liveRun(r, nowMs) == null }

    /**
     * The quiet notice when a routine starts by itself: "Sunday reset has started" · "Step 1 of 4: Show the week ahead
     * of bills · Have a look", a heads-up through the notification governor (in quiet hours it waits for the next
     * digest), once per routine a day. A tap opens Routines with the Running now card. Made ahead of its time too, so
     * the governor wakes for it; gone once the run is finished, stopped or ran by hand earlier today.
     */
    fun notices(items: List<RoutineItem>, nowMs: Long, cal: LocalCalendar): List<Notice> = items.mapNotNull { r ->
        val at = startTimeToday(r, nowMs, cal) ?: return@mapNotNull null
        val live = liveRun(r, nowMs)
        val text = if (live != null) {
            val card = runCard(r, live)
            "${card.progress}: ${card.stepTitle} · ${card.state}"
        } else {
            val first = r.steps.first()
            "${r.steps.size} ${if (r.steps.size == 1) "step" else "steps"}, starting with ${first.title.replaceFirstChar { it.lowercase() }}"
        }
        Notice(
            key = "$NOTICE_KEY_PREFIX${r.id}:${cal.epochDayOf(at)}", source = NoticeSource.ROUTINES, tier = NoticeTier.HEADS_UP,
            title = "${r.name} has started", text = text, atMs = at, target = NoticeTarget.ROUTINES,
            expiresAtMs = at + START_WINDOW_MS,
        )
    }

    const val NOTICE_KEY_PREFIX = "routine:"

    /** What adding [s] writes (switched on). */
    fun fields(s: RoutineStarter, nowMs: Long): Map<String, FieldValue> = mapOf(
        RoutineFields.NAME to s.name.fv(),
        RoutineFields.DAYS to encodeDays(s.schedule.days).fv(),
        RoutineFields.PAYDAY to s.schedule.payday.fv(),
        RoutineFields.MINUTE to s.schedule.minute.toLong().fv(),
        RoutineFields.STEPS to encodeSteps(s.steps).fv(),
        RoutineFields.SUMMARY to s.summary.fv(),
        RoutineFields.ON to true.fv(),
        RoutineFields.STARTER to s.key.fv(),
        RoutineFields.ADDED_AT to nowMs.fv(),
    )

    // ---- Words ----

    private val DAY_UPPER = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    /** The day tile: "SUN", "MON–FRI", "DAILY", "WEEKENDS", "PAYDAY", "MON · THU", "5 DAYS". */
    fun dayTile(s: RoutineSchedule): String = when {
        s.payday -> "PAYDAY"
        s.days.size == 7 -> "DAILY"
        s.days == Recurrence.WEEKDAYS -> "MON–FRI"
        s.days == setOf(6, 7) -> "WEEKENDS"
        s.days.size <= 2 -> s.days.sorted().joinToString(" · ") { DAY_UPPER[it - 1] }
        else -> "${s.days.size} DAYS"
    }

    /** "Every Sunday at 19:00", "Weekdays at 07:00", "Every day at 21:00", "On payday at 09:00". */
    fun whenLine(s: RoutineSchedule): String {
        val at = LocalClock.formatMinute(s.minute)
        return when {
            s.payday -> "On payday at $at"
            s.days.size == 7 -> "Every day at $at"
            s.days == Recurrence.WEEKDAYS -> "Weekdays at $at"
            s.days == setOf(6, 7) -> "Weekends at $at"
            s.days.size == 1 -> "Every ${CivilDate.DAY_LONG[s.days.first() - 1]} at $at"
            else -> "${s.days.sorted().joinToString(", ") { LocalClock.DAY_SHORT[it - 1] }} at $at"
        }
    }

    /**
     * The next day it runs at or after [today] (today only while [nowMinute] is before its time); null for payday
     * without a known [payday] date.
     */
    fun nextDay(s: RoutineSchedule, today: Long, nowMinute: Int, payday: Long? = null): Long? {
        if (s.payday) return payday?.takeIf { it > today || (it == today && nowMinute < s.minute) }
        for (d in 0..7) {
            val day = today + d
            if (CivilDate.isoDayOfWeek(day) in s.days && (d > 0 || nowMinute < s.minute)) return day
        }
        return null
    }

    /** "Next: tonight", "Next: today", "Next: tomorrow", "Next: Sun 8 Nov", "Next: on payday". */
    fun nextLine(s: RoutineSchedule, today: Long, nowMinute: Int, payday: Long? = null): String {
        val day = nextDay(s, today, nowMinute, payday) ?: return "Next: on payday"
        val word = when (day - today) {
            0L -> if (s.minute >= 18 * 60) "tonight" else "today"
            1L -> "tomorrow"
            else -> CivilDate.shortLabel(day)
        }
        return "Next: $word"
    }

    fun row(r: RoutineItem, today: Long, nowMinute: Int, payday: Long? = null): RoutineRow {
        val time = LocalClock.formatMinute(r.schedule.minute)
        val next = if (r.on) nextLine(r.schedule, today, nowMinute, payday) else null
        val line = if (r.on) r.summary else "Paused · ${r.summary}"
        val state = if (r.on) "On" else "Paused"
        return RoutineRow(
            r.id, r.name, dayTile(r.schedule), time, line, next, r.on,
            switchSpoken = if (r.on) "Pause ${r.name}" else "Switch on ${r.name}",
            spoken = listOfNotNull(r.name, whenLine(r.schedule), r.summary, state, next).joinToString(". "),
        )
    }

    fun starterRow(s: RoutineStarter): RoutineStarterRow = RoutineStarterRow(
        s.key, s.name, dayTile(s.schedule), LocalClock.formatMinute(s.schedule.minute), s.summary, whenLine(s.schedule),
        spoken = "${s.name}. ${whenLine(s.schedule)}. ${s.summary}",
    )

    /** "2 on · 1 paused", "1 on", "All paused", or [EMPTY_LINE]. */
    fun summary(items: List<RoutineItem>): String {
        if (items.isEmpty()) return EMPTY_LINE
        val on = items.count { it.on }
        val off = items.size - on
        return when {
            on == 0 -> "All paused"
            off == 0 -> "$on on"
            else -> "$on on · $off paused"
        }
    }

    const val NEXT_TILE = "Next"
    const val STEPS_TILE = "Steps"
    const val LAST_TILE = "Last run"
    const val STEPS_LABEL = "Steps"
    const val LAST_TIME = "Last time"
    const val NOT_YET = "Not yet"
    const val NO_HISTORY = "It hasn't run yet. What it did will show here after each run."
    const val STEPS_NOTE = "Tap a step's label to change it between Just show, Asks you and Obi does it. Obi does it is offered only where Navilife already acts by itself."
    const val BACK = "Routines"

    /** The labels a step at [place] may have, in the order a tap moves through them. */
    fun modesFor(place: RoutinePlace): List<RoutineStepMode> =
        if (place.obiMayDo) RoutineStepMode.entries else listOf(RoutineStepMode.SHOW, RoutineStepMode.ASK)

    /** The label after [step]'s on a tap, or null when it has only one. */
    fun nextMode(step: RoutineStep): RoutineStepMode? {
        val modes = modesFor(step.place)
        if (modes.size < 2) return null
        return modes[(modes.indexOf(effectiveMode(step)).coerceAtLeast(0) + 1) % modes.size]
    }

    /** The NEXT tile: "Tonight", "Today", "Tomorrow", "Sun 8 Nov", "On payday", "Paused". */
    fun nextTile(r: RoutineItem, today: Long, nowMinute: Int, payday: Long? = null): String {
        if (!r.on) return "Paused"
        val word = nextLine(r.schedule, today, nowMinute, payday).removePrefix("Next: ")
        return word.replaceFirstChar { it.uppercase() }
    }

    fun detail(
        r: RoutineItem, today: Long, nowMinute: Int, payday: Long? = null, nowMs: Long? = null, cal: LocalCalendar = LocalCalendar.UTC,
    ): RoutineDetail {
        val steps = r.steps.mapIndexed { i, st ->
            val mode = effectiveMode(st)
            val next = nextMode(st)
            RoutineStepRow(
                i, "${i + 1}", st.title, st.place.label, mode, mode.label, next?.label,
                spoken = "Step ${i + 1}: ${st.title}, in ${st.place.label}. ${mode.label}" + (next?.let { ". Tap to change to ${it.label}" } ?: ""),
            )
        }
        val card = if (nowMs != null) liveRun(r, nowMs)?.let { runCard(r, it) } else null
        val last = r.lastRun
        return RoutineDetail(
            r.id, r.name, whenLine(r.schedule), nextTile(r, today, nowMinute, payday), "${steps.size}",
            last?.let { lastTile(it.atMs, today, cal) } ?: NOT_YET, steps,
            last?.let { historyLine(it, today, cal) } ?: NO_HISTORY, r.on, STEPS_NOTE,
            lastSteps = last?.log.orEmpty(),
            running = card,
            runNow = if (card == null && r.steps.isNotEmpty()) RUN_NOW else null,
        )
    }

    const val RUN_NOW = "Run now"
    const val RUNNING_NOW = "Running now"
    const val CARRY_ON = "Carry on"
    const val SKIP_STEP = "Skip this step"
    const val STOP = "Stop"
    const val WAITING = "Waiting on you"
    const val HAVE_A_LOOK = "Have a look"

    /** The Running now card for [r]'s [run]. */
    fun runCard(r: RoutineItem, run: RoutineRun): RoutineRunCard {
        val step = r.steps[run.step]
        val waiting = effectiveMode(step) == RoutineStepMode.ASK
        val progress = "Step ${run.step + 1} of ${r.steps.size}"
        val state = if (waiting) WAITING else HAVE_A_LOOK
        return RoutineRunCard(
            r.id, r.name, progress, run.step.toFloat() / r.steps.size, step.title, step.place.label, state, waiting,
            spoken = "$RUNNING_NOW: ${r.name}. $progress: ${step.title}, in ${step.place.label}. $state",
        )
    }

    /** The LAST RUN tile: "Today 19:02", "Yesterday", "Sun 1 Nov". */
    fun lastTile(atMs: Long, today: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(atMs)
        return when (today - day) {
            0L -> "Today ${LocalClock.formatMinute(cal.minuteOfDay(atMs))}"
            1L -> "Yesterday"
            else -> CivilDate.shortLabel(day)
        }
    }

    /** LAST TIME's line: "Today at 19:02 · 3 done, 1 skipped", "Sun 1 Nov at 19:02 · stopped at step 3". */
    fun historyLine(last: RoutineLastRun, today: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(last.atMs)
        val dayWord = when (today - day) { 0L -> "Today"; 1L -> "Yesterday"; else -> CivilDate.shortLabel(day) }
        val at = "$dayWord at ${LocalClock.formatMinute(cal.minuteOfDay(last.atMs))}"
        val done = last.log.count { it.outcome != RoutineOutcome.SKIPPED && it.outcome != RoutineOutcome.NOT_REACHED }
        val skipped = last.log.count { it.outcome == RoutineOutcome.SKIPPED }
        val parts = listOfNotNull("$done done", skipped.takeIf { it > 0 }?.let { "$it skipped" })
        return "$at · ${parts.joinToString(", ")}"
    }

    /**
     * Ask's Daily row: the next routine to run, "Study night · tonight" (lit when it runs today), "All paused", or
     * null with none (the row keeps [ROW_LINE]).
     */
    fun dailyLine(v: RoutinesView): DailyLine? {
        if (v.rows.isEmpty()) return null
        val next = v.rows.firstOrNull { it.on && it.next != null } ?: return DailyLine(v.summary, false)
        val word = next.next!!.removePrefix("Next: ")
        return DailyLine("${next.name} · $word", word == "today" || word == "tonight")
    }

    /**
     * The Routines page: his routines, soonest-running first (paused ones after, by name), and the starters he hasn't
     * added yet (by [RoutineItem.starter], or a routine of his with the same name).
     */
    fun view(
        items: List<RoutineItem>, today: Long, nowMinute: Int, payday: Long? = null, nowMs: Long? = null, cal: LocalCalendar = LocalCalendar.UTC,
    ): RoutinesView {
        fun order(r: RoutineItem): Long {
            if (!r.on) return Long.MAX_VALUE
            val d = nextDay(r.schedule, today, nowMinute, payday) ?: return Long.MAX_VALUE - 1
            return d * 24 * 60 + r.schedule.minute
        }
        val rows = items.sortedWith(compareBy<RoutineItem> { order(it) }.thenBy { it.name.lowercase() }.thenBy { it.id })
            .map { row(it, today, nowMinute, payday) }
        val taken = items.mapNotNull { it.starter }.toSet()
        val names = items.map { it.name.lowercase() }.toSet()
        val starters = STARTERS.filter { it.key !in taken && it.name.lowercase() !in names }.map { starterRow(it) }
        val running = if (nowMs == null) emptyList() else items
            .mapNotNull { r -> liveRun(r, nowMs)?.let { r to it } }
            .sortedWith(compareBy<Pair<RoutineItem, RoutineRun>> { it.second.startedAtMs }.thenBy { it.first.id })
            .map { (r, run) -> runCard(r, run) }
        val runningIds = running.map { it.id }.toSet()
        val shownRows = rows.map { row ->
            val card = running.firstOrNull { it.id == row.id } ?: return@map row
            row.copy(next = "$RUNNING_NOW · ${card.progress.replaceFirstChar { it.lowercase() }}")
        }
        return RoutinesView(
            summary(items), if (runningIds.isEmpty()) rows else shownRows, starters,
            items.map { detail(it, today, nowMinute, payday, nowMs, cal) }, running,
        )
    }
}

/** The routines on a replica. */
class Routines(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<RoutineItem> = RoutineRules.items(replica.entities(EntityTypes.ROUTINE).associate { it.ref.entityId to it.fields })

    fun view(payday: Long? = null): RoutinesView {
        val now = nowMs()
        return RoutineRules.view(items(), calendar.epochDayOf(now), calendar.minuteOfDay(now), payday, now, calendar)
    }

    /**
     * Slice 3: Run now. Starts a run at its first step; Obi does it steps (Inbox autopilot's own work) go by without
     * waiting. False when it isn't there, has no steps or is running already. Nothing a step does can send, pay, book
     * or trade: the steps are read through [RoutineRules.decodeSteps], which drops any that would.
     */
    fun runNow(id: String): Boolean {
        val r = items().firstOrNull { it.id == id } ?: return false
        if (r.steps.isEmpty() || RoutineRules.liveRun(r, nowMs()) != null) return false
        advance(r, RoutineRun(nowMs(), 0, emptyList()))
        return true
    }

    /**
     * Slice 3b: starts each routine whose time has come today ([RoutineRules.dueStart]), as Run now would, stamped with
     * its time. Called on every refresh (the notification governor's wake included); what it started, by id.
     */
    fun startDue(): List<String> {
        val now = nowMs()
        return items().mapNotNull { r ->
            val at = RoutineRules.dueStart(r, now, calendar) ?: return@mapNotNull null
            advance(r, RoutineRun(at, 0, emptyList()))
            r.id
        }
    }

    /** Carry on: the step it's on is done (or seen, for a Just show step) and the run moves on. */
    fun carryOn(id: String): Boolean = step(id, skip = false)

    /** Skip this step: the run moves on without it. */
    fun skipStep(id: String): Boolean = step(id, skip = true)

    /** Stops the run going now; the steps it didn't reach show as Not reached. */
    fun stopRun(id: String): Boolean {
        val r = items().firstOrNull { it.id == id } ?: return false
        val run = RoutineRules.liveRun(r, nowMs()) ?: return false
        val rest = r.steps.drop(run.step).map { RoutineLogLine(it.title, RoutineOutcome.NOT_REACHED) }
        finish(r.id, run.log + rest)
        return true
    }

    private fun step(id: String, skip: Boolean): Boolean {
        val r = items().firstOrNull { it.id == id } ?: return false
        val run = RoutineRules.liveRun(r, nowMs()) ?: return false
        val st = r.steps[run.step]
        val outcome = when {
            skip -> RoutineOutcome.SKIPPED
            RoutineRules.effectiveMode(st) == RoutineStepMode.SHOW -> RoutineOutcome.SHOWN
            else -> RoutineOutcome.DONE
        }
        advance(r, run.copy(step = run.step + 1, log = run.log + RoutineLogLine(st.title, outcome)))
        return true
    }

    /** Moves past Obi does it steps, then writes the run where it waits, or the finished run. */
    private fun advance(r: RoutineItem, start: RoutineRun) {
        var run = start
        while (run.step < r.steps.size && RoutineRules.effectiveMode(r.steps[run.step]) == RoutineStepMode.DOES) {
            run = run.copy(step = run.step + 1, log = run.log + RoutineLogLine(r.steps[run.step].title, RoutineOutcome.LEFT))
        }
        if (run.step >= r.steps.size) return finish(r.id, run.log)
        replica.commitLocal(
            EntityTypes.ROUTINE, r.id,
            mapOf(
                RoutineFields.RUN_AT to run.startedAtMs.fv(),
                RoutineFields.RUN_STEP to run.step.toLong().fv(),
                RoutineFields.RUN_LOG to RoutineRules.encodeLog(run.log).fv(),
            ),
        )
    }

    private fun finish(id: String, log: List<RoutineLogLine>) {
        replica.commitLocal(
            EntityTypes.ROUTINE, id,
            mapOf(
                RoutineFields.RUN_AT to 0L.fv(),
                RoutineFields.RUN_STEP to 0L.fv(),
                RoutineFields.RUN_LOG to "".fv(),
                RoutineFields.LAST_AT to nowMs().fv(),
                RoutineFields.LAST_LOG to RoutineRules.encodeLog(log).fv(),
            ),
        )
    }

    /** Adds the starter [key], switched on. Refused when it's already there; null when there's no such starter. */
    fun addStarter(key: String): RoutineSaid? {
        val s = RoutineRules.STARTERS.firstOrNull { it.key == key } ?: return null
        if (s.steps.any { RoutineRules.refusal(it.title) != null }) return RoutineSaid(RoutineRules.REFUSED_LINE, true)
        val mine = items()
        if (mine.any { it.starter == key || it.name.equals(s.name, ignoreCase = true) }) return RoutineSaid(RoutineRules.ALREADY_LINE, true)
        val id = newId()
        replica.commitLocal(EntityTypes.ROUTINE, id, RoutineRules.fields(s, nowMs()))
        return RoutineSaid("Added ${s.name} · ${RoutineRules.whenLine(s.schedule)}", false, id)
    }

    /** Switches a routine on or pauses it; false when it isn't there or already so. */
    fun setOn(id: String, on: Boolean): Boolean {
        val r = items().firstOrNull { it.id == id } ?: return false
        if (r.on == on) return false
        replica.commitLocal(EntityTypes.ROUTINE, id, mapOf(RoutineFields.ON to on.fv()))
        return true
    }

    /**
     * Gives step [index] of a routine the label [mode] (a tap on its badge); false when the routine or step isn't
     * there, the step has it already, or Obi may not do it there (only where Navilife already acts by itself).
     */
    fun setStepMode(id: String, index: Int, mode: RoutineStepMode): Boolean {
        val r = items().firstOrNull { it.id == id } ?: return false
        val step = r.steps.getOrNull(index) ?: return false
        if (mode !in RoutineRules.modesFor(step.place) || RoutineRules.effectiveMode(step) == mode) return false
        val steps = r.steps.toMutableList().also { it[index] = step.copy(mode = mode) }
        replica.commitLocal(EntityTypes.ROUTINE, id, mapOf(RoutineFields.STEPS to RoutineRules.encodeSteps(steps).fv()))
        return true
    }

    /** Takes a routine off for good; false when it isn't there. */
    fun remove(id: String): Boolean {
        items().firstOrNull { it.id == id } ?: return false
        replica.commitLocal(EntityTypes.ROUTINE, id, mapOf(RoutineFields.DELETED to true.fv()))
        return true
    }
}
