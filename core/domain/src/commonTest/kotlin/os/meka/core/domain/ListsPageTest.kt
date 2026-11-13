package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 17: Ask → Lists as the page shape (header, + Add, three numbers). */
class ListsPageTest {
    private val today = CivilDate.toEpochDay(2026, 11, 13)

    private fun promise(id: String, state: DueState) = PromiseItem(id, "Send the photos", "Sam", null, 0, today, state, "", false)
    private fun waiting(id: String, state: DueState) = WaitingItem(id, "Parcel", null, null, 0, null, today, state, "", false)
    private fun renewal(id: String, title: String, due: Long, state: RenewalState = RenewalState.LATER, cancelBy: Long? = null) = RenewalItem(
        id = id, title = title, kind = ObligationKind.INSURANCE, subject = null, notes = null, dueDay = due, cancelByDay = cancelBy,
        leadDays = 14, costPence = null, repeats = RenewalRepeat.YEARLY, repeatLabel = "Every year", state = state, meta = "",
        doneLabel = "Renewed", stopLabel = "Stop tracking", hasConflict = false,
    )
    private fun shop(vararg t: String) = ShoppingView(t.mapIndexed { i, s -> ShoppingItem("s$i", s, false, null, "meka", 0) }, emptyList())

    private fun view(
        promised: List<PromiseItem> = emptyList(), waits: List<WaitingItem> = emptyList(),
        attention: List<RenewalItem> = emptyList(), upcoming: List<RenewalItem> = emptyList(), shopping: ShoppingView = ShoppingView.EMPTY,
    ) = ListsView(waits, emptyList(), emptyList(), RenewalsView(attention, upcoming, emptyList(), 0, 0), shopping, promised)

    @Test
    fun emptyListsSayItInWords() {
        val p = ListsPageRules.page(ListsView.EMPTY, today)
        assertEquals("Lists", p.title)
        assertEquals(3, p.tileCount)
        assertEquals(listOf("None", "No dates", "Nothing"), p.tiles.map { it.number })
        assertTrue(p.tiles.none { it.lit })
        assertEquals(0, p.cardCount)
        assertEquals(ListsPageRules.NOTHING_DUE, ListsPageRules.summary(ListsView.EMPTY))
    }

    @Test
    fun promisesDueOrToChaseAreLit() {
        val t = ListsPageRules.tiles(view(listOf(promise("p", DueState.DUE)), listOf(waiting("w", DueState.DUE), waiting("w2", DueState.NONE))), today)[0]
        assertEquals("2", t.number)
        assertEquals("due or to chase", t.label)
        assertTrue(t.lit)
        val calm = ListsPageRules.tiles(view(listOf(promise("p", DueState.NONE))), today)[0]
        assertEquals("1", calm.number)
        assertEquals("promise either way", calm.label)
        assertFalse(calm.lit)
    }

    @Test
    fun theNextRenewalIsHowSoonAndWhat() {
        val t = ListsPageRules.tiles(view(upcoming = listOf(renewal("b", "Boiler service", today + 40), renewal("c", "Car insurance", today + 12))), today)[1]
        assertEquals("12 days", t.number)
        assertEquals("to Car insurance", t.label)
        assertFalse(t.lit)
    }

    @Test
    fun aCancelByOrOverdueRenewalLeadsLit() {
        val cancel = renewal("s", "Streaming", today + 20, RenewalState.CANCEL_BY, cancelBy = today + 3)
        val t = ListsPageRules.tiles(view(attention = listOf(cancel), upcoming = listOf(renewal("c", "Car insurance", today + 5))), today)[1]
        assertEquals("3 days", t.number)
        assertEquals("to cancel Streaming", t.label)
        assertTrue(t.lit)
        val over = ListsPageRules.tiles(view(attention = listOf(renewal("o", "TV licence", today - 2, RenewalState.OVERDUE))), today)[1]
        assertEquals("Overdue", over.number)
        assertEquals("TV licence", over.label)
        assertTrue(over.lit)
    }

    @Test
    fun shoppingCountsWhatsToBuy() {
        assertEquals("3", ListsPageRules.tiles(view(shopping = shop("Milk", "Bread", "Eggs")), today)[2].number)
        assertEquals("Nothing", ListsPageRules.tiles(ListsView.EMPTY, today)[2].number)
    }

    @Test
    fun addHoldsEveryWayInAndEachLandsOnItsTab() {
        val p = ListsPageRules.page(ListsView.EMPTY, today)
        assertEquals(6, p.addChoiceCount)
        assertEquals(
            listOf("waiting", "waiting", "someday", "decisions", "renewals", "shopping"),
            p.addChoices.map { ListsPageRules.tabFor(it.key) },
        )
        assertTrue(p.addChoices.all { it.hint.isNotBlank() })
    }
}
