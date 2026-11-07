package os.meka.core.domain

/**
 * Routines, slice 5 (2026-11-07): IDEAS FROM YOUR WEEK, the approved mockup's dashed tiles under the list. Each idea is a
 * routine Obi noticed from what Meka already does in Navilife: his bin days ("Bin night"), the Fridays he ticks tasks
 * off late ("Friday wind-down") and the evenings his study sessions sit in ("Study evenings"). A tap opens New routine
 * with the idea's sentence filled in, so he sees its WHEN and steps and changes anything before Save routine.
 *
 * Plain, non-AI rules on the device over his own synced data; nothing is sent anywhere. An idea is never offered once
 * he has saved it, or has a routine that already does its job. **The hard rule holds:** every idea's sentence is read by
 * [RoutineSentence.draft], so a step that would send, pay, book or trade can't come from one (checked by the tests).
 */
data class RoutineIdea(
    /** "bin-night", "friday-wind-down", "study-evenings"; saved as the routine's starter "idea:<key>". */
    val key: String,
    val name: String,
    /** Why Obi offers it, in a few words: "Bins go out on Tuesdays". */
    val why: String,
    /** The sentence New routine opens with: "Every Monday at 19:00, show which bins go out tomorrow, called \"Bin night\"". */
    val sentence: String,
    val spoken: String,
) {
    /** What the saved routine's starter field holds, so the idea isn't offered again. */
    val starter: String get() = RoutineIdeas.STARTER_PREFIX + key
}

/**
 * What his week shows, worked out by the core from data already on the device: the ISO weekdays his bins are collected
 * ([binDays]) and the evening reminder's minute ([binMinute]); his task ticks over the last four weeks ([ticksMs],
 * epoch ms); the study sessions in his calendar around today ([studySessions]: ISO weekday to start minute).
 */
data class RoutineWeek(
    val binDays: Set<Int> = emptySet(),
    val binMinute: Int = BinRules.NOTICE_MIN,
    val ticksMs: List<Long> = emptyList(),
    val studySessions: List<Pair<Int, Int>> = emptyList(),
) {
    companion object { val EMPTY = RoutineWeek() }
}

object RoutineIdeas {
    const val TITLE = "Ideas from your week"
    const val STARTER_PREFIX = "idea:"
    const val MAX = 4
    /** Ticks at or after this minute on a Friday count as late. */
    const val LATE_FRIDAY_MIN = 16 * 60 + 30
    /** Fridays with late ticks (of the last four) before Friday wind-down is offered. */
    const val LATE_FRIDAYS = 2
    /** Ticks at a late Friday before it counts. */
    const val LATE_TICKS = 2
    /** Study sessions starting at or after this minute are evening ones. */
    const val EVENING_MIN = 17 * 60
    const val STUDY_SESSIONS = 2
    const val LOOK_BACK_DAYS = 28
    const val NOTE = "Tap one to see it in New routine first. Nothing is saved until you tap Save routine."

    private val DAY_PLURAL = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")

    private fun dayList(days: List<Int>, words: List<String>): String = when (days.size) {
        1 -> words[days[0] - 1]
        else -> days.dropLast(1).joinToString(", ") { words[it - 1] } + " and " + words[days.last() - 1]
    }

    /** The Fridays (epoch days) in [ticksMs] with at least [LATE_TICKS] ticks at or after [LATE_FRIDAY_MIN]. */
    fun lateFridays(ticksMs: List<Long>, today: Long, cal: LocalCalendar): Int = ticksMs
        .filter { val d = cal.epochDayOf(it); d in (today - LOOK_BACK_DAYS)..today && CivilDate.isoDayOfWeek(d) == 5 && cal.minuteOfDay(it) >= LATE_FRIDAY_MIN }
        .groupingBy { cal.epochDayOf(it) }.eachCount().count { it.value >= LATE_TICKS }

    /**
     * The ideas for his week, at most [MAX]: none he saved already (by its starter or name), none a routine of his
     * already does (a bin step on those evenings; a Friday routine from 15:00; any study step).
     */
    fun ideas(items: List<RoutineItem>, week: RoutineWeek, today: Long, cal: LocalCalendar): List<RoutineIdea> {
        val taken = items.mapNotNull { it.starter }.toSet()
        val names = items.map { it.name.lowercase() }.toSet()
        val out = mutableListOf<RoutineIdea>()

        // Bin night: the evening before each collection day, at his bin reminder's time.
        if (week.binDays.isNotEmpty()) {
            val collect = week.binDays.filter { it in 1..7 }.sorted()
            val nights = collect.map { if (it == 1) 7 else it - 1 }.sorted()
            val covered = items.any { r ->
                !r.schedule.payday && r.schedule.days.containsAll(nights) &&
                    r.steps.any { RoutineSentence.placeOf(it.title) == RoutinePlace.HOME && Regex("""\bbins?\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.title) }
            }
            if (!covered && collect.isNotEmpty()) {
                val at = LocalClock.formatMinute(week.binMinute.coerceIn(0, 24 * 60 - 1))
                out += idea(
                    "bin-night", "Bin night", "Bins go out on ${dayList(collect, DAY_PLURAL)}",
                    "Every ${dayList(nights, CivilDate.DAY_LONG)} at $at, show which bins go out tomorrow",
                )
            }
        }

        // Friday wind-down: he ticks tasks off late on Fridays.
        if (lateFridays(week.ticksMs, today, cal) >= LATE_FRIDAYS) {
            val covered = items.any { r -> !r.schedule.payday && 5 in r.schedule.days && r.schedule.minute >= 15 * 60 }
            if (!covered) {
                out += idea(
                    "friday-wind-down", "Friday wind-down", "You tick tasks off late on Fridays",
                    "Every Friday at 16:30, show what's left today and plan next week",
                )
            }
        }

        // Study evenings: the evenings his study sessions are in.
        val evenings = week.studySessions.filter { it.second in EVENING_MIN until 24 * 60 && it.first in 1..7 }
        if (evenings.size >= STUDY_SESSIONS && items.none { r -> r.steps.any { it.place == RoutinePlace.STUDY } }) {
            val days = evenings.map { it.first }.distinct().sorted()
            val start = evenings.minOf { it.second } / 15 * 15
            val short = days.joinToString(" and ") { LocalClock.DAY_SHORT[it - 1] }
            val when_ = if (days.size == 7) "Every day" else "Every ${dayList(days, CivilDate.DAY_LONG)}"
            out += idea(
                "study-evenings", "Study evenings", "You study on $short evenings",
                "$when_ at ${LocalClock.formatMinute(start)}, open my lessons and tick the lessons I finished",
            )
        }

        return out.filter { it.starter !in taken && it.name.lowercase() !in names }.take(MAX)
    }

    private fun idea(key: String, name: String, why: String, body: String): RoutineIdea {
        val sentence = "$body, called \"$name\""
        return RoutineIdea(key, name, why, sentence, spoken = "Idea: $name. $why. Opens New routine with it filled in")
    }
}
