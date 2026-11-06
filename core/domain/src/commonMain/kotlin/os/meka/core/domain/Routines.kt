package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Routines (build plan "Take first", Meka approved the mockup 2026-11-06 21:34), slice 1: jobs Obi repeats for you,
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
}

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
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        val EMPTY = RoutinesView(RoutineRules.EMPTY_LINE, emptyList(), emptyList())
    }
}

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
        )
    }

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
    fun view(items: List<RoutineItem>, today: Long, nowMinute: Int, payday: Long? = null): RoutinesView {
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
        return RoutinesView(summary(items), rows, starters)
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
        return RoutineRules.view(items(), calendar.epochDayOf(now), calendar.minuteOfDay(now), payday)
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

    /** Takes a routine off for good; false when it isn't there. */
    fun remove(id: String): Boolean {
        items().firstOrNull { it.id == id } ?: return false
        replica.commitLocal(EntityTypes.ROUTINE, id, mapOf(RoutineFields.DELETED to true.fv()))
        return true
    }
}
