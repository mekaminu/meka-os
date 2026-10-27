package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The payment sheet (Meka 2026-10-26 23:18): chips in his order, the toggles and the moved line. Invented shops (rule 13). */
class MoneyPaymentSheetTest {
    private val row = MoneyTxnRow(
        "t1", "Mon 26 Oct", "Corner Shop", "−£4.20", false, "groceries", "Groceries", "Current account", false, "Corner Shop",
        shared = true,
    )

    @Test
    fun hisOwnPicksFirstThenTheMostUsedAndIncomeAndTransfersLast() {
        val order = MoneyPaymentSheet.order(
            use = mapOf("groceries" to 40, "coffee" to 12, "transfers" to 99, "income" to 50, "shopping" to 12, "fun" to 3),
            taught = setOf("fun"),
        )
        assertEquals(listOf("fun", "groceries", "coffee", "shopping"), order.take(4))
        assertEquals(listOf("income", "transfers"), order.takeLast(2))
        // Every category is offered once.
        assertEquals(MoneyCategory.entries.size, order.toSet().size)
        assertEquals(MoneyCategory.entries.size, order.size)
    }

    @Test
    fun chipsLightTheCurrentCategoryAndFallBackToTheUsualOrder() {
        val chips = MoneyPaymentSheet.chips(emptyList(), "groceries")
        assertEquals(MoneyCategory.entries.map { it.label }, chips.map { it.label })
        assertEquals(listOf("groceries"), chips.filter { it.on }.map { it.key })
        assertEquals("Groceries, chosen", chips.first { it.on }.spoken)
        assertEquals("Put it in Coffee", chips.first { it.key == "coffee" }.spoken)
        // Unknown keys in an order are skipped, never drawn.
        assertEquals(listOf("Coffee"), MoneyPaymentSheet.chips(listOf("coffee", "nope"), "x").map { it.label })
    }

    @Test
    fun theSheetSaysWhenAndWhereAndWhatAPickDoes() {
        assertEquals("Mon 26 Oct · Current account", MoneyPaymentSheet.sub(row))
        assertEquals("Picking one moves every payment from Corner Shop, past and future", MoneyPaymentSheet.ruleLine(row))
        assertEquals("Corner Shop is always Groceries · pick another to move every payment from them",
            MoneyPaymentSheet.ruleLine(row.copy(taught = true)))
        val t = MoneyPaymentSheet.toggles(row)
        assertEquals(listOf("Shared", "Always shared", "Side hustle"), t.map { it.label })
        assertTrue(t[0].on)
        assertFalse(t[1].on)
        assertFalse(t[2].on)
        assertEquals("Count Corner Shop as the side hustle's", t[2].spoken)
    }

    @Test
    fun movingSeveralPaymentsSaysHowMany() {
        assertEquals("Moved 14 payments from Corner Shop to Shopping", MoneyPaymentSheet.movedLine("Corner Shop", MoneyCategory.SHOPPING, 14))
        assertEquals("Corner Shop is Shopping from now on", MoneyPaymentSheet.movedLine("Corner Shop", MoneyCategory.SHOPPING, 1))
    }
}
