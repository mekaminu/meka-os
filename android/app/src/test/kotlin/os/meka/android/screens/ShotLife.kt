package os.meka.android.screens

/**
 * One invented life every screen's shots share (the walk-through 2026-11-08, A8's last pairs): where two screens show
 * the same thing (the GP's number on Emergency and Facts, the younger child's birthday on People, Ask and Gifts, the
 * next trip on Ask, Trips and Trip lists, the call assistant on Health and Setup), their shots read it from here, so
 * the shots never disagree with each other the way the app itself never would. Invented data only (rule 13).
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
}
