package os.meka.core.testing

import os.meka.core.domain.CivilDate
import os.meka.core.domain.TermCalendar
import os.meka.core.domain.TermPeriod

/**
 * An invented council's school holidays for tests (rule 13: no real council in the code; the app reads his from About
 * you). Two school years, 2026–27 and 2027–28, with two training days the council names itself.
 */
object InventedCouncil {
    private fun d(y: Int, m: Int, day: Int) = CivilDate.toEpochDay(y, m, day)

    val OAKFIELD = TermCalendar(
        id = "oakfield",
        name = "Oakfield",
        sourceUrl = "https://www.oakfield.gov.uk/school-term-dates",
        checkedDay = d(2026, 10, 14),
        years = "2026–27 and 2027–28",
        periods = listOf(
            TermPeriod("October half term", d(2026, 10, 26), d(2026, 10, 30)),
            TermPeriod("Christmas holidays", d(2026, 12, 21), d(2027, 1, 4)),
            TermPeriod("February half term", d(2027, 2, 15), d(2027, 2, 19)),
            TermPeriod("Easter holidays", d(2027, 3, 26), d(2027, 4, 9)),
            TermPeriod("Training day", d(2027, 4, 12), d(2027, 4, 12)),
            TermPeriod("May half term", d(2027, 5, 31), d(2027, 6, 4)),
            TermPeriod("Training day", d(2027, 6, 7), d(2027, 6, 7)),
            TermPeriod("Summer holidays", d(2027, 7, 22), d(2027, 8, 31)),
            TermPeriod("October half term", d(2027, 10, 25), d(2027, 10, 29)),
            TermPeriod("Christmas holidays", d(2027, 12, 20), d(2028, 1, 3)),
            TermPeriod("February half term", d(2028, 2, 14), d(2028, 2, 18)),
            TermPeriod("Easter holidays", d(2028, 4, 3), d(2028, 4, 17)),
            TermPeriod("May half term", d(2028, 5, 29), d(2028, 6, 2)),
            TermPeriod("Summer holidays", d(2028, 7, 22), d(2028, 8, 31)),
        ),
    )
}
