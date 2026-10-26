package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign (B2): the Spending tab's ring, middle, tiles, category rows and latest payments by day. Invented data. */
class MoneySpendingTest {
    private fun cat(c: MoneyCategory, pounds: Long, over: Boolean = false, under: Boolean = false) =
        MoneySpendCat(c, pounds * 100, "3 payments", over, under)

    private val cats = listOf(
        cat(MoneyCategory.OTHER, 424), cat(MoneyCategory.GROCERIES, 402, under = true), cat(MoneyCategory.BILLS, 388),
        cat(MoneyCategory.SHOPPING, 252), cat(MoneyCategory.TRANSPORT, 160, over = true), cat(MoneyCategory.COFFEE, 115),
        cat(MoneyCategory.EATING_OUT, 68), cat(MoneyCategory.SUBSCRIPTIONS, 31), cat(MoneyCategory.FEES, 12), cat(MoneyCategory.KIDS, 8),
    )
    private val spent = cats.sumOf { it.pence }
    private val today = CivilDate.toEpochDay(2026, 10, 24)

    private fun row(id: String, day: Long, pence: Long, category: MoneyCategory) = MoneySpendTxn(
        MoneyTxnRow(id, CivilDate.shortLabel(day), id, MoneyRules.signed(pence), pence > 0, category.key, category.label, "Current ••80", false, id),
        day, pence,
    )

    private fun view(partial: Boolean = true, avg: Long? = 200_000, txns: List<MoneySpendTxn> = emptyList(), all: Int = txns.size) =
        MoneySpending.view(partial, "October", spent, 320_000, cats, avg, "usual", 24, 31, 41_000, 95_000, txns, all, today)

    @Test
    fun theRingGoesBiggestFirstWithOtherAndTheRestQuiet() {
        val v = view()
        // Other is biggest but always quiet; the next seven categories get the seven colours in turn.
        val toned = v.slices.filter { it.tone != MoneySpending.REST }
        assertEquals(listOf("groceries", "bills", "shopping", "transport", "coffee", "eating_out", "subscriptions"), toned.map { it.key })
        assertEquals((0..6).toList(), toned.map { it.tone })
        // Fees and Kids fold into one quiet "Everything else" slice at the end; Other keeps its own.
        assertEquals(MoneySpending.REST_KEY, v.slices.last().key)
        assertEquals(MoneySpending.REST, v.slices.first { it.key == "other" }.tone)
        // The slices tile the circle without gaps or overlaps.
        var at = 0f
        v.slices.forEach { s -> assertTrue(kotlin.math.abs(s.start - at) < 1e-4f, s.key); at += s.sweep }
        assertTrue(kotlin.math.abs(at - 1f) < 1e-3f)
    }

    @Test
    fun rowsShowShareBarAndNote() {
        val v = view()
        val g = v.rows.first { it.key == "groceries" }
        assertEquals("22%", g.pct)
        assertEquals(MoneyTone.GOOD, g.noteTone)
        assertEquals(MoneyIcon.CART, g.icon)
        assertEquals(1f, v.rows.first().bar)
        assertEquals(MoneyTone.OVER, v.rows.first { it.key == "transport" }.noteTone)
        assertEquals("All 10 ›", MoneySpending.allLabel(v.rows.size, open = false))
        assertEquals(MoneySpending.FEWER, MoneySpending.allLabel(v.rows.size, open = true))
        assertNull(MoneySpending.allLabel(5, open = false))
        assertEquals(5, MoneySpending.shownRows(v, open = false).size)
        assertEquals("<1%", MoneySpending.pct(0.001))
    }

    @Test
    fun theMiddleComparesThisMonthAtTodaysPace() {
        // Usual £2,000 a month; by the 24th of 31 that's £1,548; £1,860 spent is £312 over.
        val c = view().centre
        assertEquals("Spent so far", c.top)
        assertEquals("£1,860", c.big)
        assertEquals("£312 over usual by the 24th", c.sub)
        assertEquals(MoneyTone.OVER, c.tone)
        val whole = view(partial = false, avg = 200_000).centre
        assertEquals("Spent in October", whole.top)
        assertEquals("£140 under usual", whole.sub)
        assertEquals(MoneyTone.GOOD, whole.tone)
        assertNull(view(avg = null).centre.sub)
        assertEquals("about usual", MoneySpending.centre(false, "May", 100_000, 100_500, "usual", 31, 31).sub)
    }

    @Test
    fun pickingACategoryShowsItInTheMiddleAndFadesTheOthers() {
        val v = view()
        val f = MoneySpending.focus(v, "coffee")
        assertEquals("Coffee", f.top)
        assertEquals("£115", f.big)
        assertEquals("6% of spending", f.sub)
        assertEquals(v.centre, MoneySpending.focus(v, null))
        assertEquals(v.centre, MoneySpending.focus(v, "gone"))
        assertTrue(MoneySpending.lit(v.slices.first { it.key == "coffee" }, "coffee", v))
        assertTrue(!MoneySpending.lit(v.slices.first { it.key == "bills" }, "coffee", v))
        // A category folded into the rest lights the rest's slice.
        assertTrue(MoneySpending.lit(v.slices.last(), "kids", v))
        assertEquals(MoneySpending.REST, MoneySpending.txnTone(v, "kids"))
        assertEquals(0, MoneySpending.txnTone(v, "groceries"))
    }

    @Test
    fun tilesSayCameInBillsToComeAndLeft() {
        val v = view()
        assertEquals(listOf("Came in" to "£3,200", "Bills to come" to "£410", "Left" to "£950"), v.tiles.map { it.label to it.value })
        val noPayday = MoneySpending.view(false, "May", spent, 0, cats, null, "usual", 31, 31, null, null, emptyList(), 0, today)
        assertEquals(listOf("Came in"), noPayday.tiles.map { it.label })
    }

    @Test
    fun theLatestPaymentsGoByDayWithTheDaysMoneyOut() {
        val txns = listOf(
            row("tesco", today, -4_250, MoneyCategory.GROCERIES), row("coffee", today, -570, MoneyCategory.COFFEE),
            row("pay", today, 320_000, MoneyCategory.INCOME), row("train", today - 1, -2_740, MoneyCategory.TRANSPORT),
            row("shop", today - 2, -83_825, MoneyCategory.OTHER), row("sixth", today - 3, -100, MoneyCategory.OTHER),
        )
        val v = view(txns = txns, all = 42)
        assertEquals(listOf("Today", "Yesterday", CivilDate.shortLabel(today - 2)), v.latest.map { it.label })
        assertEquals("−£48.20", v.latest[0].total)
        assertEquals(listOf("tesco", "coffee", "pay"), v.latest[0].rows.map { it.id })
        assertEquals(5, v.latest.sumOf { it.rows.size })
        assertEquals("See all 42 ›", v.seeAll)
        assertNull(view(txns = txns.take(3)).seeAll)
        // A day with only money in has no total.
        assertNull(MoneySpending.days(listOf(row("pay", today, 100, MoneyCategory.INCOME)), today).single().total)
    }

    @Test
    fun ordinals() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map { MoneySpending.ordinal(it) })
    }

    @Test
    fun theMoneyScreenCarriesItAndInstantTapsReachTheLatest() {
        val acct = MoneyAccount("a1", "Current account", "csv", null)
        val day = CivilDate.toEpochDay(2026, 10, 20)
        val lines = listOf(
            MoneyTxn("t1", "a1", day, -4_000, "TESCO STORES"), MoneyTxn("t2", "a1", day - 1, -1_000, "UNKNOWN SHOP"),
            MoneyTxn("t3", "a1", day - 30, -9_000, "TESCO STORES"),
        )
        val v = MoneyRules.view(listOf(acct), lines, emptyMap(), day, LocalCalendar.UTC, 0)
        val sp = v.month!!.spending!!
        assertEquals("Spent so far", sp.centre.top)
        assertEquals("£50", sp.centre.big)
        assertEquals(listOf("groceries", "other"), sp.rows.map { it.key })
        assertEquals(listOf("Today", "Yesterday"), sp.latest.map { it.label })
        val tapped = InstantPaymentRules.shown(v, listOf(InstantPaymentRules.categoryTap("t2", MoneyCategory.HOME.key)))
        assertEquals(MoneyCategory.HOME.key, tapped.month!!.spending!!.latest[1].rows.single().category)
    }
}
