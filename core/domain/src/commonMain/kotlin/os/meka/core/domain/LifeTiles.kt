package os.meka.core.domain

/**
 * Ask's Life grid (Fold review 2026-10-12, item 7): the home-and-family places as 2-column tiles, each with a *live*
 * line ("Black bin and food caddy out tonight", "Next Fri 23 Oct", "Chilli tonight", "1 due"). Both apps draw the same
 * tiles in this order; the line is what's happening now when there is something (lit in the accent), else the place's
 * own summary made short, else null (the app shows the tile's fixed words: nothing entered yet).
 */
enum class LifeTile {
    LISTS, GOALS, PEOPLE, DINNERS, BINS, DATE_NIGHT, SCHOOL, APPOINTMENTS, LEAVE, GIFTS, TRIPS, ERRANDS, EMERGENCY, FACTS,
}

/** A tile's live line; [lit] when it's about today or wants a look. */
data class LifeTileLine(val text: String, val lit: Boolean)

/** Every tile's live line (a tile with nothing entered has none). */
data class LifeLines(val lines: Map<LifeTile, LifeTileLine>) {
    fun of(tile: LifeTile): LifeTileLine? = lines[tile]

    companion object { val EMPTY = LifeLines(emptyMap()) }
}

/**
 * What the tiles are made from, as plain words and counts (the facade fills it from the views with [LifeTileRules.inputs]).
 * A summary is null when its place has nothing entered.
 */
data class LifeTileInputs(
    val listsDue: String? = null,
    val toBuy: Int = 0,
    val habits: Int = 0,
    val habitsDone: Int = 0,
    val dinnerTonight: String? = null,
    val dinnerTitle: String? = null,
    val mealsSummary: String? = null,
    val binsTonight: String? = null,
    val binsSummary: String? = null,
    val dateNightToday: String? = null,
    val dateNightSummary: String? = null,
    val schoolToday: String? = null,
    val schoolSummary: String? = null,
    val appointmentsSummary: String? = null,
    val appointmentsDue: Int = 0,
    val leaveSummary: String? = null,
    val giftsToday: String? = null,
    val giftsLit: Boolean = false,
    val giftsSummary: String? = null,
    val tripToday: String? = null,
    val tripLit: Boolean = false,
    val tripsSummary: String? = null,
    val errandsSummary: String? = null,
    val emergencySummary: String? = null,
    val factsSummary: String? = null,
    val peopleSummary: String? = null,
)

object LifeTileRules {
    /** The grid's order on both apps. */
    val ORDER: List<LifeTile> = LifeTile.entries

    /** Stagger steps for the grid: tiles arrive two at a time (a row), 40 ms a row, starting at [first]. */
    fun step(first: Int, index: Int): Int = first + index / 2

    fun lines(i: LifeTileInputs): LifeLines {
        val m = linkedMapOf<LifeTile, LifeTileLine>()
        fun put(t: LifeTile, text: String?, lit: Boolean = false) {
            val s = text?.trim().orEmpty()
            if (s.isNotEmpty()) m[t] = LifeTileLine(s, lit)
        }
        when {
            i.listsDue != null -> put(LifeTile.LISTS, i.listsDue, lit = true)
            i.toBuy > 0 -> put(LifeTile.LISTS, "${i.toBuy} to buy")
        }
        if (i.habits > 0) put(
            LifeTile.GOALS,
            if (i.habitsDone >= i.habits) "All ${i.habits} habit${if (i.habits == 1) "" else "s"} done today"
            else "${i.habitsDone} of ${i.habits} habits done today",
        )
        put(LifeTile.PEOPLE, i.peopleSummary)
        when {
            i.dinnerTonight != null -> put(LifeTile.DINNERS, i.dinnerTonight, lit = true)
            i.dinnerTitle != null -> put(LifeTile.DINNERS, "${i.dinnerTitle} tonight")
            else -> put(LifeTile.DINNERS, i.mealsSummary)
        }
        if (i.binsTonight != null) put(LifeTile.BINS, i.binsTonight, lit = true)
        else put(LifeTile.BINS, i.binsSummary?.let { capitalise(it.removePrefix("Next: ")) })
        if (i.dateNightToday != null) put(LifeTile.DATE_NIGHT, i.dateNightToday, lit = true)
        else put(LifeTile.DATE_NIGHT, i.dateNightSummary?.let { capitalise(it.substringAfterLast(" · ")) })
        if (i.schoolToday != null) put(LifeTile.SCHOOL, i.schoolToday, lit = true)
        else put(LifeTile.SCHOOL, i.schoolSummary)
        put(LifeTile.APPOINTMENTS, i.appointmentsSummary, lit = i.appointmentsDue > 0)
        put(LifeTile.LEAVE, i.leaveSummary)
        if (i.giftsToday != null) put(LifeTile.GIFTS, i.giftsToday, lit = i.giftsLit) else put(LifeTile.GIFTS, i.giftsSummary)
        if (i.tripToday != null) put(LifeTile.TRIPS, i.tripToday, lit = i.tripLit) else put(LifeTile.TRIPS, i.tripsSummary)
        put(LifeTile.ERRANDS, i.errandsSummary)
        put(LifeTile.EMERGENCY, i.emergencySummary)
        put(LifeTile.FACTS, i.factsSummary)
        return LifeLines(m)
    }

    /** The facade's inputs from the views as they stand; a place still on its empty line gives no summary. */
    fun inputs(
        today: Today, lists: ListsView, goals: GoalsView, meals: MealPlanView, bins: BinsView, dateNight: DateNightView,
        school: SchoolView, appointments: AppointmentView, leave: LeaveView, gifts: GiftsView, trips: TripsView,
        errands: ErrandView, emergency: EmergencyView, facts: FactsView, people: PeopleView,
    ): LifeTileInputs = LifeTileInputs(
        listsDue = lists.dueLine,
        toBuy = lists.shopping.toBuy.size,
        // One rule for which habits are today's, the same as Needs you and the Day ring (Fold review 2026-10-12 17:56,
        // item 3: the tile said "0 of 2" while Needs you said "0 of 1").
        habits = DayTileRules.habitsToday(goals.habits),
        habitsDone = DayTileRules.habitsDone(goals.habits),
        dinnerTonight = today.dinner?.text,
        dinnerTitle = meals.week.firstOrNull()?.title,
        mealsSummary = meals.summary.takeIf { meals.favourites.isNotEmpty() },
        binsTonight = today.bins?.text,
        binsSummary = bins.summary.takeIf { !bins.isEmpty },
        dateNightToday = today.dateNight?.text,
        dateNightSummary = dateNight.summary.takeIf { dateNight.on },
        schoolToday = today.school?.text,
        schoolSummary = school.summary.takeIf { !school.isEmpty },
        appointmentsSummary = appointments.summary.takeIf { it != AppointmentRules.EMPTY_SUMMARY },
        appointmentsDue = appointments.nudges.size,
        leaveSummary = leave.summary.takeIf { it != LeaveRules.EMPTY_LINE },
        giftsToday = today.gifts?.text,
        giftsLit = today.gifts?.lit == true,
        giftsSummary = gifts.summary.takeIf { !gifts.isEmpty },
        tripToday = today.trip?.text,
        tripLit = today.trip?.lit == true,
        tripsSummary = trips.summary.takeIf { !trips.isEmpty },
        errandsSummary = errands.summary.takeIf { it != ErrandRules.EMPTY_SUMMARY },
        emergencySummary = emergency.summary.takeIf { emergency.count > 0 },
        factsSummary = facts.summary.takeIf { facts.count > 0 },
        peopleSummary = people.summary.takeIf { !people.isEmpty },
    )

    private fun capitalise(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
