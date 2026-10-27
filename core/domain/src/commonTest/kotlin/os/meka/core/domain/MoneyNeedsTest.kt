package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money: needs and wants (the payment sheet's Need toggle, Spending's "Needs vs wants"). Invented shops (rule 13). */
class MoneyNeedsTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private var opN = 0
    private var clockMs = 1_000L
    private fun replica(): Replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }

    private val file = "Date,Description,Amount\n" +
        "01/10/2026,CORNER NEWS,-8.00\n05/10/2026,CORNER NEWS,-9.50\n06/10/2026,TESCO,-20.00\n" +
        "07/10/2026,MYSTERY LTD,-5.00\n10/10/2026,SALARY OAKFIELD LTD,1000.00\n"

    private fun money(r: Replica = replica()): Money = Money(r) { oct16 * CivilDate.DAY_MS }.also { m -> r.addListener { m.changed(it) }; m.import("Current account", file) }

    private fun view(m: Money): MoneyView = MoneyRules.view(
        m.accounts(), m.txns(), m.rules(), oct16, LocalCalendar.UTC, 0,
        own = m.ownCategories(), ownRules = m.ownRules(), needMerchants = m.needMerchants(),
    )

    @Test
    fun categoriesHaveADefault() {
        assertTrue(MoneyNeeds.byDefault(MoneyCategory.GROCERIES))
        assertTrue(MoneyNeeds.byDefault(MoneyCategory.BILLS))
        assertTrue(MoneyNeeds.byDefault(MoneyCategory.TRANSPORT))
        assertFalse(MoneyNeeds.byDefault(MoneyCategory.COFFEE))
        assertFalse(MoneyNeeds.byDefault(MoneyCategory.OTHER))
        // His own categories are wants until he marks a payee; his mark always wins.
        assertFalse(MoneyNeeds.byDefault(MoneyCategory.GROCERIES, own = true))
        assertTrue(MoneyNeeds.isNeed(MoneyCategory.COFFEE, true))
        assertFalse(MoneyNeeds.isNeed(MoneyCategory.GROCERIES, false))
        assertEquals("Tesco counts as a need", MoneyNeeds.said("Tesco", true))
        assertEquals(listOf(MoneyNeeds.VIEW_CATEGORIES to true, MoneyNeeds.VIEW_NEEDS to false), MoneyNeeds.views(false).map { it.label to it.on })
        assertTrue(MoneyNeeds.views(true).single { it.on }.needs)
    }

    @Test
    fun theViewSplitsTheMonthInTwoAndNamesTheBiggest() {
        val v = MoneyNeeds.view(
            listOf(
                MoneyNeedLine("Groceries", 40_00, true), MoneyNeedLine("Bills", 50_00, true),
                MoneyNeedLine("Coffee", 5_00, false), MoneyNeedLine("Shopping", 3_00, false), MoneyNeedLine("Fun", 1_00, false),
                MoneyNeedLine("Other", 1_00, false), MoneyNeedLine("Coffee", 5_00, false),
            ),
            105_00,
        )!!
        val (needs, wants) = v.rows
        assertEquals("Needs", needs.label)
        assertEquals("£90", needs.amount)
        assertEquals("86%", needs.pct)
        assertEquals("Bills, Groceries", needs.note)
        assertEquals(1f, needs.bar)
        assertEquals("£15", wants.amount)
        assertEquals("Coffee, Shopping, Fun and 1 more", wants.note)
        assertEquals(MoneyNeeds.NEEDS_TONE, v.slices[0].tone)
        assertEquals(MoneyNeeds.WANTS_TONE, v.slices[1].tone)
        assertTrue(kotlin.math.abs(1f - (v.slices[0].sweep + v.slices[1].sweep)) < 0.0001f)
        assertNull(MoneyNeeds.view(emptyList(), 0))
    }

    @Test
    fun theNeedToggleMarksThePayeePastAndFutureWithUndo() {
        val m = money()
        val before = view(m)
        val sp = before.month!!.spending!!
        val needs = sp.needs!!
        assertEquals("£20", needs.rows[0].amount)
        assertEquals("£22.50", needs.rows[1].amount)
        // The view chip swaps the ring and rows; Categories keeps them.
        assertEquals(needs.rows, MoneyNeeds.shown(sp, true).rows)
        assertEquals(sp.rows, MoneyNeeds.shown(sp, false).rows)
        val news = before.month!!.txns.first { it.merchant == "Corner News" }
        assertFalse(news.need)
        val toggle = MoneyPaymentSheet.toggles(news).first()
        assertEquals(MoneySheetMark.NEED, toggle.mark)
        assertFalse(toggle.on)
        // Money in has no Need toggle.
        val salary = before.month!!.txns.first { it.moneyIn }
        assertTrue(MoneyPaymentSheet.toggles(salary).none { it.mark == MoneySheetMark.NEED })

        val saved = m.setNeedMerchant(news.id, true)
        assertFalse(saved.refused)
        assertEquals("Corner News counts as a need", saved.line)
        val after = view(m)
        assertTrue(after.month!!.txns.filter { it.merchant == "Corner News" }.all { it.need && it.needMarked })
        assertEquals("£37.50", after.month!!.spending!!.needs!!.rows[0].amount)
        assertEquals("£5", after.month!!.spending!!.needs!!.rows[1].amount)
        // No total changes.
        assertEquals(before.month!!.spentLine, after.month!!.spentLine)

        // Tesco marked a want moves it the other way.
        val tesco = after.month!!.txns.first { it.merchant == "Tesco" }
        assertTrue(tesco.need)
        m.setNeedMerchant(tesco.id, false)
        assertEquals("£17.50", view(m).month!!.spending!!.needs!!.rows[0].amount)

        assertTrue(m.undoCategory(saved.undo!!))
        val undone = view(m)
        assertTrue(undone.month!!.txns.filter { it.merchant == "Corner News" }.none { it.need || it.needMarked })
        assertEquals("£0", undone.month!!.spending!!.needs!!.rows[0].amount)
    }

    @Test
    fun aTapShowsInTheSameFrame() {
        val m = money()
        val v = view(m)
        val news = v.month!!.txns.first { it.merchant == "Corner News" }
        val shown = InstantPaymentRules.shown(v, listOf(InstantPaymentRules.needTap(news.id, true)))
        assertTrue(shown.month!!.txns.filter { it.merchant == "Corner News" }.all { it.need })
        assertTrue(shown.month!!.txns.filter { it.merchant != "Corner News" && !it.moneyIn && it.category != "groceries" }.none { it.need })
        assertTrue(InstantPaymentRules.failedLine(InstantPaymentRules.needTap(news.id, true), null).startsWith("Need"))
    }
}
