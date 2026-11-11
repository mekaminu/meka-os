package os.meka.android.screens

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

    /** The next trip: the one Trips lists first and Trip lists packs for. */
    const val NEXT_TRIP = "Seville 28–31 Oct"

    /** A full set's Work mode switch: Setup's full shot says the call assistant is on, so Health's full one does too. */
    const val CALL_ASSISTANT_ON_WHEN_FULL = true

    /** One event of the shots' week: on 2026-10-[day], [startMin]–[endMin] minutes after midnight in London. */
    data class WeekEvent(
        val id: String, val title: String, val day: Int, val startMin: Int, val endMin: Int,
        val calendar: String, val place: String? = null,
    )

    val SWIM = WeekEvent("ev-swim", "Swimming lesson", 21, 18 * 60 + 30, 19 * 60 + 15, "Kids", "Oakfield Leisure Centre")
    val PLANNING = WeekEvent(
        "ev-planning", "Quarterly planning with the design and engineering leads", 21, 10 * 60, 11 * 60 + 30,
        "Personal", "Harbour House, Dock Street",
    )
    val DENTIST = WeekEvent("ev-dentist", "Dentist", 21, 13 * 60 + 45, 14 * 60 + 15, "Personal", "Oakfield Dental")
    val CALL = WeekEvent("ev-call", "Call with Priya", 21, 17 * 60, 17 * 60 + 45, "Personal")
    val PARENTS_EVENING = WeekEvent("ev-parents", "Parents' evening at Oakfield School", 22, 9 * 60 + 30, 10 * 60, "Kids")

    /**
     * Wednesday's and Thursday's timed events as every screen shows them (the walk-through 2026-11-08, A8: the brief and the
     * bedside had the planning meeting on Wednesday while the Calendar had it on Thursday; only the bedside knew Thursday's
     * parents' evening, only the Calendar Wednesday's call). The swim first, as a screen's "one event" shots keep only it.
     */
    val WEEK: List<WeekEvent> = listOf(SWIM, PLANNING, DENTIST, CALL, PARENTS_EVENING)

    /**
     * The fast every screen shows on Wed 21 Oct: started at 03:45 that morning (Fasting's shot at 17:15 is 13½ hours in,
     * ends in 2:30 on 16:8; the brief at 07:40 is 3 h 55 m in). The brief had one started at 19:40 the evening before.
     */
    const val FAST_STARTED_MIN = 3 * 60 + 45

    /** Minutes the shots' fast has run at [nowMin] minutes after midnight on Wed 21 Oct. */
    fun fastMinutesAt(nowMin: Int): Int = nowMin - FAST_STARTED_MIN
}
