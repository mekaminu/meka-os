package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Money review (Meka 2026-10-26 22:12): Worth a look as compact one-line rows, three shown. Invented shops (rule 13). */
class MoneyLookTest {
    private fun alert(title: String, line: String) = MoneyAlert("w-$title", "large", title, line, false, "$title. $line")

    @Test
    fun theAmountComesOffTheTitleOrOutOfTheLine() {
        val big = MoneyLook.row(alert("Bigger than usual · Gadget Barn £480", "Usually about £45 · Tue 13 Oct · Current account"))
        assertEquals("Bigger than usual · Gadget Barn", big.name)
        assertEquals("£480", big.amount)
        val fees = MoneyLook.row(alert("Foreign transaction fees · £4.12", "3 fees on Credit card in the last 30 days"))
        assertEquals("Foreign transaction fees", fees.name)
        assertEquals("£4.12", fees.amount)
        val first = MoneyLook.row(alert("First payment to Acme Ltd · £1,320.50", "Tue 13 Oct · Is this you?"))
        assertEquals("First payment to Acme Ltd", first.name)
        assertEquals("£1,320.50", first.amount)
        val twice = MoneyLook.row(alert("Possibly charged twice · Corner Shop", "£23.40 on Tue 13 Oct and again on Wed 14 Oct · Current account"))
        assertEquals("Possibly charged twice · Corner Shop", twice.name)
        assertEquals("£23.40", twice.amount)
        val none = MoneyLook.row(alert("Something odd", "No amount here"))
        assertEquals("Something odd", none.name)
        assertEquals("", none.amount)
    }

    @Test
    fun threeShowThenMore() {
        val rows = MoneyLook.rows((1..5).map { alert("Late payment fee · £$it", "Card") })
        assertEquals(3, MoneyLook.shown(rows, open = false).size)
        assertEquals(5, MoneyLook.shown(rows, open = true).size)
        assertEquals("2 more ›", MoneyLook.moreLabel(5, open = false))
        assertEquals(MoneyLook.FEWER, MoneyLook.moreLabel(5, open = true))
        assertNull(MoneyLook.moreLabel(3, open = false))
    }
}
