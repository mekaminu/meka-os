package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 2: tips from his own numbers and his regular payments. Invented statements only. */
class MoneyTipsTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory) = MoneyLine(day, pence, merchant, c)

    @Test
    fun aCoffeeHabitSaysWhatHalvingSaves() {
        // 19 coffees at £3.75 in the last 30 days = £71.25; halving saves £427.50 a year → "about £430".
        val coffees = (0 until 19).map { line(oct16 - it, -375, "COSTA COFFEE", MoneyCategory.COFFEE) }
        val tips = MoneyTips.habits(coffees, oct16)
        assertEquals(1, tips.size)
        assertEquals("Coffee 19× · £71.25 in the last 30 days", tips[0].title)
        assertEquals("Halving it would save about £430 a year", tips[0].line)
        assertEquals("habit", tips[0].kind)
        // Seven, or older than 30 days, say nothing.
        assertTrue(MoneyTips.habits(coffees.take(7), oct16).isEmpty())
        assertTrue(MoneyTips.habits(coffees.map { it.copy(day = it.day - 40) }, oct16).isEmpty())
    }

    @Test
    fun aCategoryWellOverItsAverageIsLit() {
        // Clothes about £80 a month for six months, £260 so far in October.
        val before = (4..9).map { m -> line(d(m, 10), -8_000, "NEXT", MoneyCategory.CLOTHES) }
        val now = listOf(line(d(10, 3), -26_000, "NEXT", MoneyCategory.CLOTHES))
        val tips = MoneyTips.over(before + now, oct16)
        assertEquals(listOf("Clothes £180 over your 6-month average"), tips.map { it.title })
        assertEquals("£260 so far in October · 15 days to go", tips[0].line)
        assertTrue(tips[0].lit)
        // Within the average: nothing. With only one month before: nothing to compare.
        assertTrue(MoneyTips.over(before + line(d(10, 3), -9_000, "NEXT", MoneyCategory.CLOTHES), oct16).isEmpty())
        assertTrue(MoneyTips.over(listOf(before.last()) + now, oct16).isEmpty())
        // In the month's first week, last month's too.
        val nov3 = CivilDate.toEpochDay(2026, 11, 3)
        val last = MoneyTips.over(before + now, nov3)
        assertEquals("Clothes was £180 over your 6-month average in October", last.single().title)
    }

    @Test
    fun regularPaymentsAndAPriceRise() {
        val netflix = listOf(d(6, 6), d(7, 6), d(8, 6), d(9, 6)).map { line(it, -1099, "NETFLIX.COM", MoneyCategory.SUBSCRIPTIONS) } +
            line(d(10, 6), -1249, "NETFLIX.COM", MoneyCategory.SUBSCRIPTIONS)
        val gym = listOf(d(8, 1), d(9, 1), d(10, 1)).map { line(it, -3500, "PUREGYM", MoneyCategory.HEALTH) }
        // The weekly shop is never "regular", and a one-off isn't either.
        val shop = (0 until 10).map { line(oct16 - it * 7, -6000, "TESCO STORES", MoneyCategory.GROCERIES) }
        val oneOff = listOf(line(d(9, 20), -4000, "ARGOS", MoneyCategory.SHOPPING))
        val all = netflix + gym + shop + oneOff
        val regular = MoneyTips.regular(all, oct16)
        assertEquals(listOf("PUREGYM", "NETFLIX.COM"), regular.map { it.key })
        val view = assertNotNull(MoneyTips.regularView(regular))
        assertEquals("2 regular payments · about £47.49 a month · £569.88 a year", view.head)
        val n = view.rows.first { it.key == "NETFLIX.COM" }
        assertEquals("£12.49 a month", n.amount)
        assertEquals("Subscriptions · last Tue 6 Oct · next about Fri 6 Nov · up £1.50", n.line)
        assertTrue(n.lit)
        val rise = MoneyTips.rises(regular).single()
        assertEquals("Netflix.com went up £1.50", rise.title)
        assertEquals("£12.49 a month from Tue 6 Oct · £18 more a year", rise.line)
        // Stopped two months ago: no longer regular.
        assertTrue(MoneyTips.regular(gym, CivilDate.toEpochDay(2026, 12, 20)).isEmpty())
        // A bill that moves about isn't a "rise".
        val energy = listOf(-9000L, -9800L, -9400L, -9900L).mapIndexed { i, p -> line(d(7 + i, 12), p, "OCTOPUS", MoneyCategory.BILLS) }
        assertEquals(0L, MoneyTips.regular(energy, oct16).single().rise)
    }

    @Test
    fun hiddenKindsStayHiddenOnTheScreen() {
        var clockMs = 1L
        var opN = 0
        val replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }
        val money = Money(replica) { 1L }
        val csv = "Date,Description,Amount\n15/09/2026,TESCO STORES,-10.00\n" + (0 until 10).joinToString("") { i ->
            val day = CivilDate.fromEpochDay(oct16 - i)
            "${day.day}/${day.month}/2026,COSTA COFFEE,-4.00\n"
        }
        assertFalse(money.import("Current account", csv).refused)
        fun view() = MoneyRules.view(money.accounts(), money.txns(), money.rules(), oct16, LocalCalendar.UTC, 0, money.hiddenTips())
        assertEquals(listOf("habit"), view().tips.map { it.kind })
        assertNull(view().hiddenTips)
        val hid = money.hideTips("habit")
        assertEquals("Tips on habits hidden · on both devices", hid.line)
        assertTrue(view().tips.isEmpty())
        assertEquals("Hidden: tips on habits", view().hiddenTips)
        // Undo shows them again; so does Show them again.
        assertTrue(money.undoCategory(hid.undo!!))
        assertEquals(1, view().tips.size)
        money.hideTips("habit")
        money.showTips()
        assertEquals(1, view().tips.size)
        // A month back shows no tips (they're about now).
        assertTrue(MoneyRules.view(money.accounts(), money.txns(), money.rules(), oct16, LocalCalendar.UTC, 1).tips.isEmpty())
        assertTrue(money.hideTips("nonsense").refused)
    }
}
