package os.meka.android.screens

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv

/**
 * One invented life every screen's shots share (the walk-through 2026-11-08, A8's last pairs): where two screens show
 * the same thing (the GP's number on Emergency and Facts, the younger child's birthday on People, Ask and Gifts, the
 * next trip on Ask, Trips and Trip lists, the call assistant on Health and Setup), their shots read it from here, so
 * the shots never disagree with each other the way the app itself never would. The week's events and the fast
 * (Brief, Calendar, Bedside and Fasting all stand on Wed 21 Oct 2026 in London) are here too. Invented data only (rule 13).
 */
object ShotLife {
    /** The family GP, kept under Emergency → Call and as a Fact. */
    const val GP = "GP: Dr Patel, 01632 960123"

    /** Sam's birthday as People and Ask take it (no year) and as Gifts keeps it (with the year he was born). */
    const val SAM_BIRTHDAY = "Sam's birthday 3 Nov"
    const val SAM_BIRTHDAY_WITH_YEAR = "Sam's birthday 3 Nov 2016"

    /** The older child's birthday, the one Gifts' single-occasion shots keep and its gift ideas go on. */
    const val ALEX_BIRTHDAY = "Alex's birthday 31 Oct 2019"

    /**
     * Every birthday a full set knows, as Gifts keeps them and People reads them from Gifts (the walk-through 2026-11-15
     * A13: People's full shot had Priya and Sam, Gifts' had Alex, Sam and Grandma Rose). Soonest first from Wed 21 Oct.
     */
    val BIRTHDAYS: List<String> = listOf(
        "Priya's birthday 24 Oct 1990",
        ALEX_BIRTHDAY,
        SAM_BIRTHDAY_WITH_YEAR,
        "Grandma Rose's eightieth birthday party at the community hall 6 Nov 1946",
    )

    /** The one occasion a full Gifts set has that isn't a birthday (People lists birthdays only). */
    const val ANNIVERSARY = "Wedding anniversary 20 Aug 2015"

    /** The next trip: the one Trips lists first and Trip lists packs for. */
    const val NEXT_TRIP = "Seville 28–31 Oct"

    /** The trip after it, as Trips' by-hand shot types it and Trip lists starts from Seville (walk-through 2026-11-15 A13). */
    const val LATER_TRIP = "Rome 20–22 Nov"

    /** A full set's Work mode switch: Setup's full shot says the call assistant is on, so Health's full one does too. */
    const val CALL_ASSISTANT_ON_WHEN_FULL = true

    /**
     * Obi's Face setting in every shot (the walk-through 2026-11-08, A32: Obi's voice showed Face On while Health's voice
     * row said "Face · off"). Health reads the device's own setting, off on a new phone, so Obi's voice shows it off too.
     */
    const val FACE_ON = false

    /** One event of the shots' week: on 2026-10-[day], [startMin]–[endMin] minutes after midnight in London. */
    data class WeekEvent(
        val id: String, val title: String, val day: Int, val startMin: Int, val endMin: Int,
        val calendar: String, val place: String? = null, val allDay: Boolean = false,
    )

    val SWIM = WeekEvent("ev-swim", "Swimming lesson", 21, 18 * 60 + 30, 19 * 60 + 15, "Kids", "Oakfield Leisure Centre")
    val PLANNING = WeekEvent(
        "ev-planning", "Quarterly planning with the design and engineering leads", 21, 10 * 60, 11 * 60 + 30,
        "Personal", "Harbour House, Dock Street",
    )
    val DENTIST = WeekEvent("ev-dentist", "Dentist", 21, 13 * 60 + 45, 14 * 60 + 15, "Personal", "Oakfield Dental")
    val CALL = WeekEvent("ev-call", "Call with Priya", 21, 17 * 60, 17 * 60 + 45, "Personal")
    /**
     * Thursday's one event, in the evening (the walk-through 2026-11-15 A13: it sat at 09:30, an "evening" in the morning
     * inside work, and was the bedside's "first thing" while Tomorrow said the day starts with the gym at 07:40).
     */
    val PARENTS_EVENING = WeekEvent("ev-parents", "Parents' evening at Oakfield School", 22, 18 * 60, 19 * 60, "Kids")

    /**
     * Wednesday's and Thursday's timed events as every screen shows them (the walk-through 2026-11-08, A8: the brief and the
     * bedside had the planning meeting on Wednesday while the Calendar had it on Thursday; only the bedside knew Thursday's
     * parents' evening, only the Calendar Wednesday's call). The swim first, as a screen's "one event" shots keep only it.
     */
    val WEEK: List<WeekEvent> = listOf(SWIM, PLANNING, DENTIST, CALL, PARENTS_EVENING)

    /** Wednesday's all-day event (the school's book fair) and its late dinner, as Today's Schedule card and the brief show them. */
    val BOOK_FAIR = WeekEvent("ev-bookfair", "Book fair week", 21, 0, 24 * 60, "Kids", allDay = true)
    val DINNER = WeekEvent("ev-dinner", "Dinner with Grandma Rose", 21, 20 * 60, 21 * 60 + 30, "Personal")

    /**
     * Wednesday as Today and the brief both show it (the walk-through 2026-11-22, A2: the brief had the planning
     * meeting, the dentist, the call and the swimming lesson while Today's Schedule had a "Training" at Oakfield Rec in
     * the swim's 18:30 slot and none of the others): [WEEK]'s Wednesday, the book fair all day and the dinner.
     */
    val WEDNESDAY: List<WeekEvent> = listOf(BOOK_FAIR) + WEEK.filter { it.day == 21 }.sortedBy { it.startMin } + DINNER

    /**
     * Writes [events] (by default [WEDNESDAY]) into [store] as the server's calendar mirror does, before the core opens
     * it, so a screen that times things around the day (the Day list's Fit times, the walk-through 2026-11-22 A3) sees
     * the same swimming lesson and dinner the Calendar and Today show. [nowMs] stamps the writes.
     */
    fun seedEvents(store: ReplicaStore, nowMs: Long, events: List<WeekEvent> = WEDNESDAY) {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, min: Int, zone: TimeZone) =
            LocalDateTime(2026, 10, day, min / 60, min % 60).toInstant(zone).toEpochMilliseconds()
        var n = 0
        val mirror = Replica("hh", "server", HlcClock("server", { nowMs }), store, MekaSchema) { "life${n++}" }
        events.forEach { e ->
            mirror.commitLocal(EntityTypes.EVENT, e.id, buildMap {
                put(EventFields.TITLE, e.title.fv())
                if (e.allDay) {
                    put(EventFields.START_AT, at(e.day, 0, TimeZone.UTC).fv())
                    put(EventFields.END_AT, at(e.day + 1, 0, TimeZone.UTC).fv())
                    put(EventFields.ALL_DAY, true.fv())
                } else {
                    put(EventFields.START_AT, at(e.day, e.startMin, london).fv())
                    put(EventFields.END_AT, at(e.day, e.endMin, london).fv())
                }
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "jordan@example.com".fv())
                put(EventFields.CALENDAR, e.calendar.fv())
                e.place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        }
    }

    /** One of Wednesday's tasks: its title, its day ([dayOffset] from Wednesday) and its size in minutes. */
    data class DayTask(val title: String, val dayOffset: Int, val minutes: Int?)

    /** The task Today finishes by 17:15 (it is still open in the 07:40 brief). */
    const val DONE_BY_EVENING = "Reply to Oakfield School"

    /**
     * Wednesday's tasks as Today and the brief both have them (A2: the brief's Focus was "…football run with Sam" while
     * Today's was "…with Alex", and the brief left out the window cleaner that Today's "1 of 6" counts). Yesterday's
     * bins first (overdue), then today's in the order they were added.
     */
    val WEDNESDAY_TASKS: List<DayTask> = listOf(
        DayTask("Order new bins", -1, null),
        DayTask("Book the MOT", 0, 30),
        DayTask(DONE_BY_EVENING, 0, 15),
        DayTask("Fix the gate latch", 0, 60),
        DayTask("Pay the window cleaner", 0, 15),
        DayTask("Plan Saturday's football run with Alex", 0, 120),
    )

    /** Adds [WEDNESDAY_TASKS] to [core] ([today] the epoch day of Wed 21 Oct) and returns their ids by title. */
    suspend fun addWednesdayTasks(core: os.meka.core.facade.MekaCore, today: Long): Map<String, String?> =
        WEDNESDAY_TASKS.associate { it.title to core.addOnBoardSized(it.title, today + it.dayOffset, it.minutes) }

    /**
     * Thursday's first thing: the task dated tomorrow at 07:00 that Tomorrow, the bedside and the brief all lead with
     * (the walk-through 2026-11-15, A22: it sat at 07:40, the minute he leaves for work, so "before work" couldn't happen).
     */
    const val GYM = "Gym before work"
    const val GYM_MIN = 7 * 60

    /** Thursday's untimed task and the bill due that day (Lists → Renewals), as Tomorrow and the bedside count them. */
    const val RING_SCHOOL = "Ring the school about the trip"
    const val WATER_BILL = "Water bill"

    /**
     * Adds Thursday's tasks and bill to [core] ([tomorrow] the epoch day of Thu 22 Oct) and returns the two task ids
     * (gym, school), so every screen that looks at tomorrow sees the same day: [GYM] at 07:00 first, [RING_SCHOOL],
     * the [WATER_BILL] due and, from [WEEK], [PARENTS_EVENING] at 18:00.
     */
    suspend fun addThursday(core: os.meka.core.facade.MekaCore, tomorrow: Long): Pair<String, String> {
        val gym = core.addTask(GYM)
        core.setWhen(gym, tomorrow, GYM_MIN)
        val school = core.addTask(RING_SCHOOL)
        core.setWhen(school, tomorrow, null)
        core.addRenewal(WATER_BILL, os.meka.core.domain.ObligationKind.BILL, tomorrow, os.meka.core.domain.RenewalRepeat.MONTHLY, "32.50", null)
        return gym to school
    }

    /**
     * The fast every screen shows on Wed 21 Oct: started at 03:45 that morning (Fasting's shot at 17:15 is 13½ hours in,
     * ends in 2:30 on 16:8; the brief at 07:40 is 3 h 55 m in). The brief had one started at 19:40 the evening before.
     */
    const val FAST_STARTED_MIN = 3 * 60 + 45

    /** Minutes the shots' fast has run at [nowMin] minutes after midnight on Wed 21 Oct. */
    fun fastMinutesAt(nowMin: Int): Int = nowMin - FAST_STARTED_MIN

    /** One habit as the Habits screen keeps it: its name, Do or Avoid, and its list. */
    data class Habit(val name: String, val avoid: Boolean, val list: String)

    /**
     * The habits every screen shows (the walk-through 2026-11-15, A13: Goals had Read 20 pages, Run and Stretch while
     * its own "Open Habits ›" led to eight others). Goals and Habits both add these, in this order, six days before
     * Wed 21 Oct, then mark them with [markHabits].
     */
    val HABITS: List<Habit> = listOf(
        Habit("Brush teeth", false, "Morning"), Habit("Drink water", false, "Morning"),
        Habit("Walk 20 minutes", false, "All day"), Habit("No shop coffee", true, "All day"),
        Habit("No biscuits", true, "All day"), Habit("No cake", true, "Night time"),
        Habit("Floss", false, "Night time"), Habit("Read 20 pages", false, "Night time"),
    )

    /** Adds [HABITS] to [core] and returns their ids by name. */
    suspend fun addHabits(core: os.meka.core.facade.MekaCore): Map<String, String> =
        HABITS.associate { it.name to core.addHabitToList(it.name, it.avoid, it.list) }

    /**
     * The week's marks, [today] being the epoch day of Wed 21 Oct: Brush teeth and No shop coffee kept all seven days
     * (a 7-day streak each), Drink water today, No biscuits missed yesterday, Read 20 pages missed two days ago. So
     * 3 of 8 are done today.
     */
    suspend fun markHabits(core: os.meka.core.facade.MekaCore, ids: Map<String, String>, today: Long) {
        val done = os.meka.core.domain.HabitMark.DONE
        val missed = os.meka.core.domain.HabitMark.MISSED
        for (back in 0..6) {
            core.setHabitMark(ids.getValue("Brush teeth"), today - back, done)
            core.setHabitMark(ids.getValue("No shop coffee"), today - back, done)
        }
        core.setHabitMark(ids.getValue("Drink water"), today, done)
        core.setHabitMark(ids.getValue("No biscuits"), today - 1, missed)
        core.setHabitMark(ids.getValue("Read 20 pages"), today - 2, missed)
    }
}
