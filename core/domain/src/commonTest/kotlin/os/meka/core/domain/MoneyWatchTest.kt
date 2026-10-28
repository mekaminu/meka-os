package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Money manager, slice 3: the watchdog. Invented statements only. */
class MoneyWatchTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val names = mapOf("a1" to "Current account", "a2" to "Credit card")
    private var n = 0
    private fun line(day: Long, pence: Long, text: String, account: String = "a1"): MoneyWatchLine {
        val c = MoneyCategoryRules.categoryOf(text, pence)
        return MoneyWatchLine("t${n++}", account, day, pence, MoneyCategoryRules.merchantKey(text), c, text)
    }

    @Test
    fun theSameAmountTwiceInADayIsAPossibleDoubleCharge() {
        val ls = listOf(line(d(10, 13), -2_340, "AMAZON MARKETPLACE"), line(d(10, 14), -2_340, "AMAZON MARKETPLACE"))
        val a = MoneyWatch.doubles(ls, oct16, names)
        assertEquals(listOf("Possibly charged twice · Amazon Marketplace"), a.map { it.title })
        assertEquals("£23.40 on Tue 13 Oct and again on Wed 14 Oct · Current account", a[0].line)
        assertTrue(a[0].lit)
        // Same day reads "twice on".
        val same = MoneyWatch.doubles(listOf(line(d(10, 13), -2_340, "AMAZON"), line(d(10, 13), -2_340, "AMAZON")), oct16, names)
        assertEquals("£23.40 twice on Tue 13 Oct · Current account", same.single().line)
        // Three days apart, different accounts, small amounts, coffee and bus fares: nothing.
        assertTrue(MoneyWatch.doubles(listOf(line(d(10, 10), -2_340, "AMAZON"), line(d(10, 13), -2_340, "AMAZON")), oct16, names).isEmpty())
        assertTrue(MoneyWatch.doubles(listOf(line(d(10, 13), -2_340, "AMAZON"), line(d(10, 13), -2_340, "AMAZON", "a2")), oct16, names).isEmpty())
        assertTrue(MoneyWatch.doubles(listOf(line(d(10, 13), -399, "ARGOS"), line(d(10, 13), -399, "ARGOS")), oct16, names).isEmpty())
        assertTrue(MoneyWatch.doubles(listOf(line(d(10, 13), -650, "COSTA COFFEE"), line(d(10, 13), -650, "COSTA COFFEE")), oct16, names).isEmpty())
        assertTrue(MoneyWatch.doubles(listOf(line(d(10, 13), -880, "TFL TRAVEL CH"), line(d(10, 13), -880, "TFL TRAVEL CH")), oct16, names).isEmpty())
        // Older than 30 days: nothing.
        assertTrue(MoneyWatch.doubles(listOf(line(d(9, 1), -2_340, "AMAZON"), line(d(9, 1), -2_340, "AMAZON")), oct16, names).isEmpty())
    }

    @Test
    fun aPaymentMuchBiggerThanUsualOrAFirstBigOneIsFlagged() {
        val usual = (5..9).map { m -> line(d(m, 5), -4_500, "CURRYS PC WORLD") }
        val big = line(d(10, 13), -48_000, "CURRYS PC WORLD")
        val a = MoneyWatch.large(usual + big, oct16, names)
        assertEquals(listOf("Bigger than usual · Currys PC World £480"), a.map { it.title })
        assertEquals("Usually about £45 · Tue 13 Oct · Current account", a[0].line)
        // A normal one isn't.
        assertTrue(MoneyWatch.large(usual + line(d(10, 13), -8_000, "CURRYS PC WORLD"), oct16, names).isEmpty())
        // A first £320 to a new shop, with months of statements before: "Is this you?".
        val first = MoneyWatch.large(usual + line(d(10, 12), -32_000, "ACME HOLDINGS"), oct16, names)
        assertEquals("First payment to Acme Holdings · £320", first.single().title)
        assertEquals("Mon 12 Oct · Current account · Is this you?", first.single().line)
        // Without 60 days of statements to know it's new, or under £250: nothing.
        assertTrue(MoneyWatch.large(listOf(line(d(10, 1), -500, "TESCO"), line(d(10, 12), -32_000, "ACME HOLDINGS")), oct16, names).isEmpty())
        assertTrue(MoneyWatch.large(usual + line(d(10, 12), -20_000, "ACME HOLDINGS"), oct16, names).isEmpty())
    }

    @Test
    fun aSinglePaymentOverTwoThousandIsCheckThis() {
        val month = (1..10).map { line(d(9, it), -15_000, "SUPERMART") } // £1,500 of other spending in September
        val big = line(d(9, 20), -240_000, "RAILWAYS ONLINE")
        val a = MoneyWatch.large(month + big, d(10, 16), names)
        assertEquals(listOf("Check this · Railways Online · £2,400"), a.map { it.title })
        assertEquals("61% of September's spending · Sun 20 Sep · Current account · ${MoneyWatch.CHECK_ASK}", a.single().line)
        assertTrue(a.single().lit)
        // The row on Spending: name and amount apart.
        val row = MoneyLook.row(a.single())
        assertEquals("Check this · Railways Online", row.name)
        assertEquals("£2,400", row.amount)
        // The same amount back within a month reads as a likely refund or move.
        val back = MoneyWatch.large(month + big + line(d(9, 25), 240_000, "RAILWAYS REFUND"), d(10, 16), names)
        assertTrue(back.single().line.endsWith("£2,400 came back on Fri 25 Sep · a refund?"), back.single().line)
        // Even where the shop's usual is just as big, £2,000+ is still Check this; under £2,000 it isn't.
        val rent = (5..9).map { m -> line(d(m, 1), -210_000, "LANDLORD CO") }
        assertEquals(1, MoneyWatch.large(rent + line(d(10, 1), -210_000, "LANDLORD CO"), d(10, 16), names).size)
        assertTrue(MoneyWatch.large(month + line(d(10, 2), -199_999, "RAILWAYS ONLINE"), d(10, 16), names).isEmpty())
        // Its id is the large alert's, so one put away as "That's fine" before stays away.
        assertEquals(1, MoneyWatch.alerts(month + big, d(10, 16), names).size)
        assertTrue(MoneyWatch.alerts(month + big, d(10, 16), names, fine = setOf(a.single().id)).isEmpty())
    }

    @Test
    fun feesAreAddedUpOrCalledOut() {
        val ls = listOf(
            line(d(10, 2), -103, "NON-STERLING TRANSACTION FEE", "a2"),
            line(d(10, 3), -210, "NON-STERLING TRANSACTION FEE", "a2"),
            line(d(10, 9), -99, "NON-STERLING TRANSACTION FEE", "a2"),
            line(d(10, 11), -1_200, "LATE PAYMENT FEE", "a2"),
            line(d(10, 12), -1_834, "INTEREST CHARGED", "a2"),
        )
        val a = MoneyWatch.fees(ls, oct16, names)
        assertEquals(listOf("Interest charged · £18.34", "Late payment fee · £12", "Foreign transaction fees · £4.12"), a.map { it.title })
        assertEquals("3 fees on Credit card in the last 30 days", a[2].line)
        assertEquals("Credit card · Sun 11 Oct", a[1].line)
    }

    @Test
    fun putAwayAlertsStayAwayAndTheScreenShowsTheRest() {
        val ls = listOf(line(d(10, 13), -2_340, "AMAZON"), line(d(10, 13), -2_340, "AMAZON"), line(d(10, 11), -1_200, "LATE PAYMENT FEE", "a2"))
        val all = MoneyWatch.alerts(ls, oct16, names)
        assertEquals(listOf("double", "fees"), all.map { it.kind })
        val rest = MoneyWatch.alerts(ls, oct16, names, fine = setOf(all[0].id))
        assertEquals(listOf("fees"), rest.map { it.kind })
        // The id doesn't change from one look to the next (so "That's fine" holds).
        assertEquals(all.map { it.id }, MoneyWatch.alerts(ls, oct16, names).map { it.id })
        assertEquals("WORTH A LOOK · 2", MoneyWatch.head(2))
    }
}
