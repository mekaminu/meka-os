package os.meka.core.domain

/**
 * Ask's Life grid (Fold review 2026-10-12, item 7): the home-and-family places as 2-column tiles, each with a *live*
 * line ("Black bin and food caddy out tonight", "Next Fri 23 Oct", "Chilli tonight", "1 due"). Both apps draw the same
 * tiles in this order; the line is what's happening now when there is something (lit in the accent), else the place's
 * own summary made short, else a short setup prompt ("Set your bin days"). Fold review 2026-10-12 17:56, item 2: no tile
 * shows its fixed description any more, and tiles with something today float to the top ([LifeTileRules.ordered]).
 */
enum class LifeTile {
    LISTS, GOALS, PEOPLE, DINNERS, BINS, DATE_NIGHT, SCHOOL,

    /** Kids' admin, slice 1: a check of the children's sizes when due, else the first child's shoe size. */
    KIDS,
    APPOINTMENTS, LEAVE, GIFTS, TRIPS, ERRANDS,

    /** Car and parking, slice 1: where the car is parked (the Fold), else the next of the cars' dates. */
    CAR,

    /** Money manager, slice 1: the accounts and when a statement last came in; never an amount (it's outside the fingerprint). */
    MONEY,
    EMERGENCY, FACTS,
}

/** A tile's live line; [lit] when it's about today or wants a look. */
data class LifeTileLine(val text: String, val lit: Boolean)

/**
 * Every tile's live line (a tile with nothing entered has none). [toSetUp]: the tiles still on their set-up prompt
 * ("Set your bin days"), which sink under "To set up" (Ask polish, slice 1).
 */
data class LifeLines(val lines: Map<LifeTile, LifeTileLine>, val toSetUp: Set<LifeTile> = emptySet()) {
    fun of(tile: LifeTile): LifeTileLine? = lines[tile]

    fun needsSetUp(tile: LifeTile): Boolean = tile in toSetUp

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
    /** Kids' admin ([KidsView.tile]): a due size check (lit), else the first child's shoe size; null with nothing kept. */
    val kidsLine: LifeTileLine? = null,
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
    /** The next of the cars' dates ([ParkingRules.carLine]); null with nothing entered. */
    val carLine: LifeTileLine? = null,
    /** Money manager ([MoneyRules.tile]): "2 accounts · updated Fri 16 Oct"; null before anything is imported. */
    val moneyLine: LifeTileLine? = null,
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
        LifeTile.KIDS -> "Add the kids' sizes"
        LifeTile.APPOINTMENTS -> "Add the dentist and others"
        LifeTile.LEAVE -> "Add your leave"
        LifeTile.GIFTS -> "Add a birthday"
        LifeTile.TRIPS -> "No trips planned"
        LifeTile.ERRANDS -> "No errands waiting"
        LifeTile.CAR -> "Add your car's dates"
        LifeTile.MONEY -> "Import a statement"
        LifeTile.EMERGENCY -> "Not filled in yet"
        LifeTile.FACTS -> "Tell Navilife your car reg"
    }

    /**
     * The grid's order for these lines: tiles lit about today first, then the rest, each group keeping [ORDER] (so the
     * grid only reshuffles when something becomes today's).
     */
    fun ordered(lines: LifeLines): List<LifeTile> {
        val (lit, rest) = ORDER.partition { lines.of(it)?.lit == true }
        return lit + rest
    }

    /**
     * Tiles whose empty prompt asks him to set something up (Ask polish, slice 1, Meka 2026-10-12 20:53). Lists, People,
     * Trips and Errands say "nothing yet" when empty — that's a live state, so they stay in the grid.
     */
    val SET_UP_TILES: Set<LifeTile> = ORDER.toSet() - setOf(LifeTile.LISTS, LifeTile.PEOPLE, LifeTile.TRIPS, LifeTile.ERRANDS)

    /** The grid's tiles: lit ones first, then the rest with something in them; set-up tiles are left out. */
    fun live(lines: LifeLines): List<LifeTile> = ordered(lines).filter { !lines.needsSetUp(it) }

    /** The tiles under "To set up", in the usual order; a tile leaves the moment it has something live. */
    fun toSetUp(lines: LifeLines): List<LifeTile> = ORDER.filter { lines.needsSetUp(it) }

    /** The collapsible label over the set-up tiles. */
    fun setUpLabel(count: Int): String = "To set up · $count"

    /** First run (nothing at all set up): one line over the open set-up group, not an empty grid. */
    const val WELCOME = "Nothing set up yet · pick one to start; each takes a minute"

    fun welcome(lines: LifeLines): String? = if (lines.toSetUp.containsAll(SET_UP_TILES)) WELCOME else null

    /**
     * Whether "To set up" is open: what he chose on this device ([stored], remembered per device), else open on the
     * first run (the welcome shows) and closed once anything is set up.
     */
    fun setUpOpen(stored: Boolean?, lines: LifeLines): Boolean = stored ?: (welcome(lines) != null)

    /** Columns: 3 on a wide pane (the open Fold, ≥ 600 dp), else 2. */
    const val WIDE_DP = 600f

    fun columns(widthDp: Float): Int = if (widthDp >= WIDE_DP) 3 else 2

    /** Stagger steps for a grid of [columns]: a row at a time, 40 ms a row. */
    fun step(first: Int, index: Int, columns: Int): Int = first + index / columns.coerceAtLeast(1)

    /** People shows first names ("Alex" for "Alex Morgan"); a one-word name or a number stays as it is. */
    fun firstName(name: String): String {
        val t = name.trim()
        if (t.isEmpty() || t.first().isDigit() || t.first() == '+') return t
        return t.substringBefore(' ')
    }

    /** Lines clamp to this many on both apps, so every tile is the same height. */
    const val MAX_LINES = 2

    /**
     * A tile's name wraps to at most this many lines (the quality gate's Ask shots, 2026-10-22: on the cover screen's
     * two columns "Goals and habits" was cut to "Goals and…" on one line); a row's tiles share the tallest's height.
     */
    const val NAME_LINES = 2

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
            else "${i.habitsDone} of ${i.habits} habit${if (i.habits == 1) "" else "s"} done today",
        )
        if (i.personName != null) {
            val more = if (i.peopleCount > 1) " · +${i.peopleCount - 1}" else ""
            put(LifeTile.PEOPLE, firstName(i.personName) + (i.personLine?.let { " · " + lowerFirst(it) } ?: "") + more, lit = i.personLit)
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
        i.kidsLine?.let { put(LifeTile.KIDS, it.text, lit = it.lit) }
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
        i.carLine?.let { put(LifeTile.CAR, it.text, lit = it.lit) }
        i.moneyLine?.let { put(LifeTile.MONEY, it.text, lit = it.lit) }
        put(LifeTile.EMERGENCY, i.emergencySummary)
        put(LifeTile.FACTS, i.factsSummary)
        // Nothing in the place yet: a short prompt, unlit; the ones that ask for setting up sink under "To set up".
        val setUp = ORDER.filter { it !in m && it in SET_UP_TILES }.toSet()
        ORDER.forEach { put(it, prompt(it)) }
        return LifeLines(ORDER.associateWith { m.getValue(it) }, setUp)
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
