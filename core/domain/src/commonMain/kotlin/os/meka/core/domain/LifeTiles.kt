package os.meka.core.domain

/**
 * Ask's Life grid (Fold review 2026-10-12, item 7): the home-and-family places as 2-column tiles, each with a *live*
 * line ("Black bin and food caddy out tonight", "Next Fri 23 Oct", "Chilli tonight", "1 due"). Both apps draw the same
 * tiles in this order; the line is what's happening now when there is something (lit in the accent), else the place's
 * own summary made short, else a short setup prompt ("Set your bin days"). Fold review 2026-10-12 17:56, item 2: no tile
 * shows its fixed description any more, and tiles with something today float to the top ([LifeTileRules.ordered]).
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
    /** The next day off ("Half term") and how many days until it starts (0 = today). */
    val schoolNextOff: String? = null,
    val schoolNextOffIn: Long? = null,
    val appointmentsSummary: String? = null,
    val appointmentsDue: Int = 0,
    /** The first appointment that's due ("Dentist"). */
    val appointmentDueTitle: String? = null,
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
    /** The first person on People (lit first): their name and the line that says most about them. */
    val personName: String? = null,
    val personLine: String? = null,
    val personLit: Boolean = false,
    val peopleCount: Int = 0,
)

object LifeTileRules {
    /** The grid's order on both apps. */
    val ORDER: List<LifeTile> = LifeTile.entries

    /** Stagger steps for the grid: tiles arrive two at a time (a row), 40 ms a row, starting at [first]. */
    fun step(first: Int, index: Int): Int = first + index / 2

    /** What a tile says while its place has nothing in it: a short prompt, never the tile's description. */
    fun prompt(tile: LifeTile): String = when (tile) {
        LifeTile.LISTS -> "Nothing due"
        LifeTile.GOALS -> "Add a habit"
        LifeTile.PEOPLE -> "Fills from promises and birthdays"
        LifeTile.DINNERS -> "Plan this week"
        LifeTile.BINS -> "Set your bin days"
        LifeTile.DATE_NIGHT -> "Set up date night"
        LifeTile.SCHOOL -> "Add the term dates"
        LifeTile.APPOINTMENTS -> "Add the dentist and others"
        LifeTile.LEAVE -> "Add your leave"
        LifeTile.GIFTS -> "Add a birthday"
        LifeTile.TRIPS -> "No trips planned"
        LifeTile.ERRANDS -> "No errands waiting"
        LifeTile.EMERGENCY -> "Not filled in yet"
        LifeTile.FACTS -> "Tell MEKA your car reg"
    }

    /**
     * The grid's order for these lines: tiles lit about today first, then the rest, each group keeping [ORDER] (so the
     * grid only reshuffles when something becomes today's).
     */
    fun ordered(lines: LifeLines): List<LifeTile> {
        val (lit, rest) = ORDER.partition { lines.of(it)?.lit == true }
        return lit + rest
    }

    /** Lines clamp to this many on both apps, so every tile is the same height. */
    const val MAX_LINES = 2

    fun lines(i: LifeTileInputs): LifeLines {
        val m = linkedMapOf<LifeTile, LifeTileLine>()
        fun put(t: LifeTile, text: String?, lit: Boolean = false) {
            val s = text?.trim().orEmpty()
            if (s.isNotEmpty() && t !in m) m[t] = LifeTileLine(s, lit)
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
        if (i.personName != null) {
            val more = if (i.peopleCount > 1) " · +${i.peopleCount - 1}" else ""
            put(LifeTile.PEOPLE, i.personName + (i.personLine?.let { " · " + lowerFirst(it) } ?: "") + more, lit = i.personLit)
        }
        put(LifeTile.PEOPLE, i.peopleSummary)
        when {
            i.dinnerTonight != null -> put(LifeTile.DINNERS, i.dinnerTonight, lit = true)
            i.dinnerTitle != null -> put(LifeTile.DINNERS, "${i.dinnerTitle} tonight")
            i.mealsSummary == MealRules.NOTHING_PLANNED -> put(LifeTile.DINNERS, prompt(LifeTile.DINNERS))
            else -> put(LifeTile.DINNERS, i.mealsSummary)
        }
        if (i.binsTonight != null) put(LifeTile.BINS, i.binsTonight, lit = true)
        else put(LifeTile.BINS, i.binsSummary?.let { capitalise(it.removePrefix("Next: ")) })
        if (i.dateNightToday != null) put(LifeTile.DATE_NIGHT, i.dateNightToday, lit = true)
        else put(LifeTile.DATE_NIGHT, i.dateNightSummary?.let { capitalise(it.substringAfterLast(" · ")) })
        if (i.schoolToday != null) put(LifeTile.SCHOOL, i.schoolToday, lit = true)
        else if (i.schoolNextOff != null && i.schoolNextOffIn != null) put(LifeTile.SCHOOL, schoolLine(i.schoolNextOff, i.schoolNextOffIn))
        else put(LifeTile.SCHOOL, i.schoolSummary)
        if (i.appointmentDueTitle != null) {
            val more = if (i.appointmentsDue > 1) " · +${i.appointmentsDue - 1}" else ""
            put(LifeTile.APPOINTMENTS, "${i.appointmentDueTitle} due$more", lit = true)
        }
        put(LifeTile.APPOINTMENTS, i.appointmentsSummary, lit = i.appointmentsDue > 0)
        // "14 of 25 days booked · 11 days left" → "11 days left"; over the allowance it keeps the whole line.
        put(LifeTile.LEAVE, i.leaveSummary?.let { if (it.endsWith(" left")) capitalise(it.substringAfterLast(" · ")) else it })
        if (i.giftsToday != null) put(LifeTile.GIFTS, i.giftsToday, lit = i.giftsLit)
        else put(LifeTile.GIFTS, i.giftsSummary?.removePrefix("Next: "))
        if (i.tripToday != null) put(LifeTile.TRIPS, i.tripToday, lit = i.tripLit) else put(LifeTile.TRIPS, i.tripsSummary)
        put(LifeTile.ERRANDS, i.errandsSummary)
        put(LifeTile.EMERGENCY, i.emergencySummary)
        put(LifeTile.FACTS, i.factsSummary)
        // Nothing in the place yet: a short prompt, unlit.
        ORDER.forEach { put(it, prompt(it)) }
        return LifeLines(ORDER.associateWith { m.getValue(it) })
    }

    /** "Half term in 14 days", "Half term tomorrow", "Half term starts today". */
    fun schoolLine(title: String, inDays: Long): String = when {
        inDays <= 0 -> "$title starts today"
        inDays == 1L -> "$title tomorrow"
        else -> "$title in $inDays days"
    }

    /** The facade's inputs from the views as they stand; a place still on its empty line gives no summary. */
    fun inputs(
        todayDay: Long, today: Today, lists: ListsView, goals: GoalsView, meals: MealPlanView, bins: BinsView, dateNight: DateNightView,
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
        schoolNextOff = school.nextOffTitle,
        schoolNextOffIn = school.nextOffDay?.let { it - todayDay },
        appointmentsSummary = appointments.summary.takeIf { it != AppointmentRules.EMPTY_SUMMARY },
        appointmentsDue = appointments.nudges.size,
        appointmentDueTitle = appointments.nudges.firstOrNull()?.title,
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
        personName = people.rows.firstOrNull()?.name,
        personLine = people.rows.firstOrNull()?.let { it.owedLine ?: it.lastContact ?: it.birthday },
        personLit = people.rows.firstOrNull()?.lit == true,
        peopleCount = people.rows.size,
    )

    private fun lowerFirst(s: String): String =
        if (s.length > 1 && s[1].isUpperCase()) s else s.replaceFirstChar { it.lowercaseChar() }

    private fun capitalise(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
