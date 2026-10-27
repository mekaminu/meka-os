package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Money review (Meka 2026-10-26 22:12): "N payments without a category · Sort them" and the quick sorter, one payee at a
 * time. Invented shops and names (rule 13).
 */
class MoneySorterTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private var opN = 0
    private var clockMs = 1_000L
    private fun replica(): Replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }

    private val file = "Date,Description,Amount\n" +
        "01/10/2026,QWERTY HOLDINGS,-8.00\n05/10/2026,QWERTY HOLDINGS,-9.50\n06/10/2026,TESCO,-20.00\n" +
        "07/10/2026,ZEBRA PLOVER,-30.00\n09/10/2026,XANTHO WIDGETS,-2.00\n10/10/2026,SALARY OAKFIELD LTD,1000.00\n" +
        "12/10/2026,ZEBRA PLOVER REFUND,5.00\n"

    private fun money(r: Replica = replica()): Money = Money(r) { oct16 * CivilDate.DAY_MS }.also { m -> r.addListener { m.changed(it) }; m.import("Current account", file) }

    private fun view(m: Money): MoneyView =
        MoneyRules.view(m.accounts(), m.txns(), m.rules(), oct16, LocalCalendar.UTC, 0, own = m.ownCategories(), ownRules = m.ownRules())

    @Test
    fun theMonthsUncategorisedPaymentsAreCountedByPayeeBiggestFirst() {
        val u = assertNotNull(view(money()).month!!.unsorted)
        // Groceries, pay and money in never count; the three unknown payees do.
        assertEquals(listOf("Zebra Plover", "Qwerty Holdings", "Xantho Widgets"), u.items.map { it.row.merchant })
        assertEquals(4, u.count)
        assertEquals("4 payments without a category", u.line)
        val q = u.items[1]
        assertEquals(2, q.count)
        assertEquals("−£17.50", q.total)
        assertEquals("2 payments this month · −£17.50", q.line)
        // The sheet opens on the payee's newest payment.
        assertEquals("Mon 5 Oct", q.row.day)
        assertTrue(MoneySection.UNSORTED in MoneyTabs.sections(MoneyTab.SPENDING, view(money())))
    }

    @Test
    fun aPickSortsThePayeeAndTheNextOneComesUp() {
        val m = money()
        val first = MoneySorter.current(view(m).month!!.unsorted, emptySet())!!
        assertEquals("Zebra Plover", first.row.merchant)
        assertTrue(!m.setCategory(first.row.id, "fun").refused)
        val after = view(m).month!!.unsorted!!
        assertEquals(listOf("Qwerty Holdings", "Xantho Widgets"), after.items.map { it.row.merchant })
        assertEquals(3, after.count)
        // Skip leaves one for later; a pick still on its way is passed over too.
        assertEquals("Xantho Widgets", MoneySorter.current(after, skipped = setOf(after.items[0].key))!!.row.merchant)
        assertNull(MoneySorter.current(after, skipped = setOf(after.items[0].key), done = setOf(after.items[1].key)))
        assertEquals(1, MoneySorter.left(after, skipped = setOf(after.items[0].key)))
        // His own category and his own choice of Other both count as sorted.
        m.addOwnCategory("Gadgets", 2, MoneyIcon.BAG, after.items[0].row.id)
        m.setCategory(after.items[1].row.id, "other")
        assertNull(view(m).month!!.unsorted)
        assertTrue(MoneySection.UNSORTED !in MoneyTabs.sections(MoneyTab.SPENDING, view(m)))
    }

    @Test
    fun theSortersWords() {
        assertEquals("1 payment without a category", MoneySorter.line(1))
        assertEquals("SORT YOUR PAYMENTS · 12 LEFT", MoneySorter.head(12))
        assertEquals(MoneySorter.HEAD, MoneySorter.head(1))
        assertNull(MoneySorter.view(emptyList()))
        assertEquals(MoneySorter.ALL_DONE, MoneySorter.doneLine(0))
        assertEquals("Sorted for now · 2 payees left for later", MoneySorter.doneLine(2))
    }
}
