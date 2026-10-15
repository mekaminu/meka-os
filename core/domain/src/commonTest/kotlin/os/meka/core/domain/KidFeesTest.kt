package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kids' admin, slice 2: club fees read from one typed line, shown per child, lit and in Bills due (invented children). */
class KidFeesTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 10, 15) // Thu 15 Oct 2026
    private fun day(m: Int, d: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, d)
    private val alex = AboutChild("Alex", 7, "Oakfield School")
    private val sam = AboutChild("Sam", 10, null)

    private fun fee(id: String, title: String, due: Long, pence: Long?, repeat: RenewalRepeat, child: String?, subject: String? = "Alex") = RenewalItem(
        id = id, title = title, kind = ObligationKind.BILL, subject = subject, notes = null, dueDay = due, cancelByDay = null,
        leadDays = 7, costPence = pence, repeats = repeat, repeatLabel = RenewalRules.repeatLabel(repeat),
        state = RenewalRules.state(due, null, 7, today), meta = "", doneLabel = "Paid", stopLabel = "Stop tracking",
        hasConflict = false, forChild = child,
    )

    @Test
    fun aTypedFeeIsRead() {
        val (a, _) = KidsRules.readFee("Football £35 monthly from 1 Nov", today)
        assertEquals(KidFeeDraft("Football", 3500, RenewalRepeat.MONTHLY, day(11, 1)), a)
        val (b, _) = KidsRules.readFee("swimming £60.50 on the 5th every month", today)
        assertEquals(KidFeeDraft("Swimming", 6050, RenewalRepeat.MONTHLY, day(11, 5)), b)
        val (c, _) = KidsRules.readFee("Club subs £120 yearly 1 Sep", today)
        assertEquals(KidFeeDraft("Club subs", 12000, RenewalRepeat.YEARLY, day(9, 1, 2027)), c)
        val (d, _) = KidsRules.readFee("School trip £15 due 20 Nov", today)
        assertEquals(KidFeeDraft("School trip", 1500, RenewalRepeat.NONE, day(11, 20)), d)
        val (e, _) = KidsRules.readFee("Swimming lessons every 3 months £90 from 5 Jan", today)
        assertEquals(KidFeeDraft("Swimming lessons", 9000, RenewalRepeat.QUARTERLY, day(1, 5, 2027)), e)
        // The 15th is today: still this month's.
        assertEquals(day(10, 15), KidsRules.readFee("Gym £20 on the 15th of each month", today).first?.dueDay)
        assertEquals(RenewalRepeat.MONTHLY, KidsRules.readFee("Gym £20 on the 15th of each month", today).first?.repeat)
        // The 31st in a 30-day month takes the last day.
        assertEquals(day(10, 31), KidsRules.readFee("Karate £25 monthly on the 31st", today).first?.dueDay)
        assertEquals(day(11, 30), KidsRules.nextDayOfMonth(31, day(11, 1)))
        // No amount is fine (not known yet).
        assertNull(KidsRules.readFee("Football kit 3 Nov", today).first?.costPence)
    }

    @Test
    fun aLineMissingSomethingIsTurnedAwayWithWhy() {
        assertEquals(KidsRules.FEE_NO_DATE, KidsRules.readFee("Football £35 monthly", today).second)
        assertEquals(KidsRules.FEE_NO_TITLE, KidsRules.readFee("£35 monthly from 1 Nov", today).second)
        assertEquals(KidsRules.FEE_BAD_COST, KidsRules.readFee("Football £ monthly 1 Nov", today).second)
        assertEquals(KidsRules.FEE_NO_DATE, KidsRules.readFee("Holiday club £80 26–30 Oct", today).second)
        assertEquals(KidsRules.FEE_NO_TITLE, KidsRules.readFee("   ", today).second)
    }

    @Test
    fun feesShowOnTheirChildsCardSoonestFirstAndLightThreeDaysBefore() {
        val radar = listOf(
            fee("f1", "Football", day(11, 1), 3500, RenewalRepeat.MONTHLY, "c-alex"),
            fee("f2", "Swimming", day(10, 17), 6000, RenewalRepeat.QUARTERLY, "c-alex"),
            fee("f3", "Chess", day(12, 1), null, RenewalRepeat.NONE, "c-sam", "Sam"),
            fee("x", "Council tax", day(10, 16), 18000, RenewalRepeat.MONTHLY, null, null),
        )
        val v = KidsRules.view(listOf(alex, sam), emptyMap(), today, cal, radar)
        val a = v.kids[0].fees
        assertEquals(listOf("Swimming", "Football"), a.map { it.title })
        assertEquals("£60 · every 3 months · due Sat 17 Oct", a[0].line)
        assertTrue(a[0].due)
        assertEquals("£35 · every month · next Sun 1 Nov", a[1].line)
        assertFalse(a[1].due)
        assertEquals(listOf("Chess"), v.kids[1].fees.map { it.title })
        assertEquals("once · due Tue 1 Dec", v.kids[1].fees[0].line)
        // The tile leads with the fee that's due, lit, over everything else.
        assertEquals(LifeTileLine("Alex's Swimming · £60 due Sat 17 Oct", true), v.tile)
        // Overdue says so.
        val late = KidsRules.feeRow(fee("f4", "Football", day(10, 12), 3500, RenewalRepeat.MONTHLY, "c-alex"), today)
        assertEquals("£35 · every month · was due Mon 12 Oct", late.line)
        assertTrue(late.due)
    }

    @Test
    fun withNoSizesTheTileNamesTheNextFeeUnlit() {
        val v = KidsRules.view(listOf(alex), emptyMap(), today, cal, listOf(fee("f1", "Football", day(11, 1), 3500, RenewalRepeat.MONTHLY, "c-alex")))
        assertEquals(LifeTileLine("Alex's Football · next Sun 1 Nov", false), v.tile)
    }

    @Test
    fun billsDueRemindsOnceAndSaysWhoseFeeItIs() {
        val f = fee("f1", "Football", day(10, 17), 3500, RenewalRepeat.MONTHLY, "c-alex")
        val n = BillRules.notices(listOf(f), cal.toEpochMs(today, 8 * 60), cal)
        assertEquals(1, n.size)
        assertEquals("Alex's Football due Sat 17 Oct · £35", n[0].title)
    }

    @Test
    fun addedAndPaidLines() {
        val d = KidFeeDraft("Football", 3500, RenewalRepeat.MONTHLY, day(11, 1))
        assertEquals("Added Football · £35 · every month · due Sun 1 Nov · reminds you 3 days before", KidsRules.feeAddedLine(d, today))
        assertEquals("Paid Football · next Tue 1 Dec", KidsRules.feePaidLine("Football", day(12, 1)))
        assertEquals("Paid Trip · done", KidsRules.feePaidLine("Trip", null))
    }
}
