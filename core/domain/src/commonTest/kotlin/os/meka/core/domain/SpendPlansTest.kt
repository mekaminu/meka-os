package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spending plans, slice 1: plans, people and things added up, Can I afford it?, month tiles, another currency. */
class SpendPlansTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val thu = d(11, 12)
    private val nov = 2026 * 12 + 10
    private val dec = nov + 1
    private val jan = nov + 2
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val pFold = SpendPlans(fold.replica, { "p${n++}" }, { world.clock.nowMs }, cal)
    private val pMac = SpendPlans(mac.replica, { "p${n++}" }, { world.clock.nowMs }, cal)
    /** £3,200 in a month, £2,560 out: £640 left over. */
    private val money = SpendMonthMoney(320_000, 256_000)

    init { world.clock.nowMs = cal.toEpochMs(thu, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun view(p: SpendPlans = pFold, m: SpendMonthMoney? = money) = SpendPlanRules.view(p.plans(), p.things(), m, thu)

    @Test
    fun aPlanIsNamedWithItsDayInOneLine() {
        assertEquals("Christmas gifts" to d(12, 20), SpendPlanRules.readPlan("Christmas gifts 20 Dec", thu))
        assertEquals("Holiday" to d(2, 1, 2027), SpendPlanRules.readPlan("Holiday 1 Feb", thu))
        assertEquals("Money home" to -1L, SpendPlanRules.readPlan("money home", thu))
        val r = pFold.add("Christmas gifts 20 Dec", "GBP")
        assertFalse(r.refused)
        assertEquals("Added Christmas gifts", r.line)
        assertTrue(pFold.add("20 Dec", "GBP").refused)
        assertTrue(pFold.add("   ", "GBP").refused)
        val v = view()
        assertEquals(listOf("Christmas gifts"), v.chips.map { it.name })
        assertEquals("needed by 20 Dec", v.plan(null)!!.by)
        assertEquals("Christmas gifts · £0 to go · 20 Dec", v.rowLine)
        assertEquals(SpendPlanRules.ROW_EMPTY, SpendPlanRules.view(emptyList(), emptyList(), money, thu).rowLine)
    }

    @Test
    fun amountsAndRatesAreRead() {
        assertEquals(4_500L, SpendPlanRules.amount("45"))
        assertEquals(4_500L, SpendPlanRules.amount("£45"))
        assertEquals(1_250L, SpendPlanRules.amount("12.50"))
        assertEquals(5_000_000L, SpendPlanRules.amount("50,000"))
        assertEquals(5_000_000L, SpendPlanRules.amount("₦50,000"))
        assertEquals(5_000_000L, SpendPlanRules.amount("50k"))
        assertNull(SpendPlanRules.amount("lots"))
        assertNull(SpendPlanRules.amount("0"))
        assertNull(SpendPlanRules.amount("5,00"))
        assertEquals(2_000_000L, SpendPlanRules.rate("2,000"))
        assertEquals(1_170L, SpendPlanRules.rate("1.17"))
        assertNull(SpendPlanRules.rate("abc"))
        assertEquals("2,000", SpendPlanRules.rateText(2_000_000))
        assertEquals("1.17", SpendPlanRules.rateText(1_170))
        assertEquals("£1,240", SpendPlanRules.pounds(124_049))
        assertEquals("₦50,000", SpendPlanRules.local(5_000_000, "NGN"))
    }

    @Test
    fun thingsAddUpWithABoughtBarAndCanIAffordIt() {
        val id = pFold.add("Christmas gifts 20 Dec", "GBP").id!!
        assertFalse(pFold.addThing(id, "Football boots", "For Sam", "45", nov).refused)
        pFold.addThing(id, "Lego set", "For Alex", "60", dec)
        pFold.addThing(id, "Perfume", "", "85", dec)
        assertTrue(pFold.addThing(id, "Coat", "", "", dec).refused)
        assertTrue(pFold.addThing(id, "Coat", "", "lots", dec).refused)
        val boots = pFold.things().first { it.name == "Football boots" }
        assertTrue(pFold.setDone(boots.id, true))
        val p = view().plan(id)!!
        assertEquals("£190", p.total)
        assertEquals("3 things", p.totalSub)
        assertEquals("£45 bought · £145 to go", p.spentLine)
        assertTrue(kotlin.math.abs(45f / 190f - p.bar) < 0.001f)
        assertEquals("For Sam · buy in Nov", p.rows.first().note)
        assertEquals("buy in Dec", p.rows.last().note)
        // £640 left over; £145 still to buy fits this month.
        val one = p.afford.first { it.months == 1 }
        assertEquals(listOf("£3,200", "− £2,560", "£640", "− £145"), one.rows.map { it.value })
        assertEquals("Fits · £495 left this month", one.verdict)
        assertTrue(one.fits)
        assertEquals("Still to buy ÷ 2 months", p.afford.first { it.months == 2 }.rows.last().label)
        // Month tiles: what's still to buy in Nov, Dec, Jan.
        assertEquals(listOf("Nov", "Dec", "Jan"), p.months.map { it.label })
        assertEquals(listOf("£0", "£145", "£0"), p.months.map { it.total })
        assertEquals(listOf(SpendTone.NONE, SpendTone.FITS, SpendTone.NONE), p.months.map { it.tone })
    }

    @Test
    fun tooMuchIsShortAndSpreadingItHelps() {
        val id = pFold.add("Holiday 1 Feb", "GBP").id!!
        pFold.addThing(id, "Flights", "4 people", "620", dec)
        pFold.addThing(id, "Hotel", "5 nights", "540", jan)
        val p = view().plan(id)!!
        val one = p.afford.first { it.months == 1 }
        assertEquals("£520 short this month · spread it or trim it", one.verdict)
        assertFalse(one.fits)
        assertEquals("Fits · £60 left each month", p.afford.first { it.months == 2 }.verdict)
        assertEquals(listOf("nothing", "fits", "fits"), p.months.map { it.verdict })
        // Without Money's average month: no verdict, the reason instead; tiles keep their totals.
        val none = view(m = null).plan(id)!!
        assertTrue(none.afford.isEmpty())
        assertEquals(SpendPlanRules.AFFORD_NOT_READY, none.affordNote)
        assertEquals(listOf("£0", "£620", "£540"), none.months.map { it.total })
        assertEquals(SpendPlanRules.money(null), null)
    }

    @Test
    fun anotherCurrencyHasTheSameForEveryoneAndHisRate() {
        val id = pFold.add("Money home", "NGN").id!!
        listOf("Mum", "Aunty", "Cousin").forEach { pFold.addThing(id, it, "", "", 0) }
        pFold.addThing(id, "Church", "", "100,000", 0)
        assertFalse(pFold.setEach(id, "50,000").refused)
        var p = view().plan(id)!!
        assertEquals("₦250,000", p.total)
        assertEquals("Type today's rate to see it in pounds", p.spentLine)
        assertTrue(p.afford.isEmpty())
        assertEquals("₦50,000 each · type today's rate to see it in pounds", p.foreign!!.rateLine)
        assertTrue(pFold.setRate(id, "nonsense").refused)
        assertEquals("₦2,000 to £1", pFold.setRate(id, "2,000").line)
        p = view().plan(id)!!
        assertEquals("£125", p.total)
        assertEquals("₦250,000 · 4 people", p.totalSub)
        assertEquals("₦50,000 ≈ £25 each · your rate, ₦2,000 to £1", p.foreign!!.rateLine)
        assertEquals("People", p.listTitle)
        assertEquals(listOf("£25", "£25", "£25", "£50"), p.rows.map { it.gbp })
        assertEquals("Same as everyone", p.rows.first().note)
        assertEquals("Own amount", p.rows.last().note)
        assertEquals("All sent · nothing left to find", SpendPlanRules.detail(
            pFold.plans().first(), pFold.things().map { it.copy(done = true) }, money, thu).afford.first().verdict)
        // The stepper moves ₦5,000 at a time and never under one step.
        assertTrue(pFold.stepEach(id, up = true))
        assertEquals("₦55,000", view().plan(id)!!.foreign!!.each)
        repeat(20) { pFold.stepEach(id, up = false) }
        assertEquals("₦5,000", view().plan(id)!!.foreign!!.each)
        // With no month given, a thing goes in the month the plan's needed by, else this month.
        assertEquals(nov, pFold.things().first().month)
    }

    @Test
    fun ticksEditsAndRemovalsSyncToTheMac() {
        val id = pFold.add("Christmas gifts 20 Dec", "GBP").id!!
        pFold.addThing(id, "Perfume", "", "85", 0)
        val t = pFold.things().first()
        assertEquals(dec, t.month)
        sync()
        assertTrue(pMac.setDone(t.id, true))
        assertFalse(pMac.setDone(t.id, true))
        assertFalse(pMac.editThing(t.id, "Perfume", "For his wife", "90", dec).refused)
        sync()
        val r = view(pFold).plan(id)!!.rows.single()
        assertTrue(r.done)
        assertEquals("£90", r.gbp)
        assertEquals("90", r.amountText)
        assertEquals("For his wife · buy in Dec", r.note)
        assertTrue(pFold.removeThing(t.id))
        sync()
        assertTrue(pMac.things().isEmpty())
        pMac.addThing(id, "Wrapping paper", "", "5", nov)
        assertTrue(pMac.remove(id))
        sync()
        assertTrue(pFold.plans().isEmpty())
        assertTrue(pFold.things().isEmpty())
        assertTrue(pFold.addThing(id, "Card", "", "3", nov).refused)
    }

    @Test
    fun aMonthGoneByCountsInThisOne() {
        val id = pFold.add("Gifts", "GBP").id!!
        pFold.addThing(id, "Card", "", "3", nov - 2)
        assertEquals(nov, pFold.things().single().month)
        assertEquals("£3", view().plan(id)!!.months.first().total)
        assertEquals(12, view().monthChoices.size)
        assertEquals("Nov", view().monthChoices.first().label)
    }
}
