package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign, Ask Obi (slice 2): what goes to Obi's AI and how his answer is checked. Invented numbers only. */
class MoneyChatObiTest {
    private val today = CivilDate.toEpochDay(2026, 10, 28) // a Wednesday
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, text: String) =
        MoneyLine(day, pence, MoneyCategoryRules.merchantKey(text), MoneyCategoryRules.categoryOf(text, pence))

    private val lines = listOf(
        line(d(9, 30), 250_000, "ACME PAYROLL"),
        line(d(10, 2), -4_000, "TESCO STORES 2041"), line(d(10, 9), -2_500, "TESCO STORES 2041"),
        line(d(10, 14), -350, "COSTA COFFEE"), line(d(9, 18), -1_800, "DELIVEROO"),
        line(d(8, 5), -6_000, "TESCO STORES 2041"),
        // A move between his own accounts and a payee naming an account number never go.
        MoneyLine(d(10, 3), -50_000, "Transfer to savings", MoneyCategory.TRANSFERS),
        MoneyLine(d(10, 4), -1_000, "SENT TO 12345678 401234", MoneyCategory.OTHER),
        // Older than three months: not in the totals.
        line(d(5, 1), -9_900, "ARGOS"),
    )
    private val upcoming = listOf(MoneyBill("Netflix", d(10, 30), 1_099), MoneyBill("Gym", d(12, 20), 3_000))
    private fun data() = MoneyChatData(MoneyAskData(lines, today, upcoming, null), null, null)

    @Test
    fun onlyWhatTheRulesCantAnswerGoesToObi() {
        assertTrue(MoneyChatObi.needsObi("Is Deliveroo becoming a habit?", data()))
        assertTrue(MoneyChatObi.needsObi("Why was September so expensive?", data()))
        assertFalse(MoneyChatObi.needsObi("What if I save £300 a month for 6 months?", data()))
        assertFalse(MoneyChatObi.needsObi("Can I afford £300 on Saturday?", data()))
        assertFalse(MoneyChatObi.needsObi("hi", data()))
        // Nothing to go on: no one is asked.
        assertFalse(MoneyChatObi.needsObi("Why was September so expensive?", MoneyChatData(MoneyAskData(emptyList(), today, emptyList(), null), null, null)))
    }

    @Test
    fun theFactsAreTotalsPayeesAndWhatsToGoOut() {
        val f = MoneyChatObi.facts(data())
        assertEquals("Today: Wed 28 Oct 2026", f.first())
        assertTrue(f.any { it == "October 2026 so far: spent £78.50, came in £0" }, f.joinToString("\n"))
        assertTrue(f.any { it == "September 2026: spent £18, came in £2,500" }, f.joinToString("\n"))
        assertTrue(f.any { it.startsWith("August 2026: spent £60") }, f.joinToString("\n"))
        assertFalse(f.any { it.startsWith("May 2026") })
        val payees = f.first { it.startsWith("Biggest payees") }
        assertTrue(payees.contains("(3 payments)"), payees)
        assertTrue(f.any { it.startsWith("Still to go out, next 31 days: Netflix £10.99 Fri 30 Oct") }, f.joinToString("\n"))
        // Not the gym (too far off), not the transfer, never a number that looks like an account.
        val all = f.joinToString("\n")
        assertFalse("Gym" in all)
        assertFalse("12345678" in all)
        assertFalse("savings" in all.lowercase())
        assertFalse("£500" in all)
    }

    @Test
    fun aPayeeWithAnAccountNumberNeverGoes() {
        assertNull(MoneyChatObi.payee("SENT TO 12345678 401234"))
        assertNull(MoneyChatObi.payee("GB29 NWBK 6016 1331 9268 19"))
        assertEquals("Costa Coffee", MoneyChatObi.payee("  Costa   Coffee "))
    }

    @Test
    fun onlyNumbersThatWereSentShow() {
        val sent = listOf("October 2026 so far: spent £68.50, came in £0", "Groceries £1,187.40", "Question: why so much?")
        assertEquals("You've spent £68.50 so far this October, most of it on groceries.",
            MoneyChatObi.check("You've spent £68.50 so far this October, most of it on groceries.", sent))
        // Pence may be dropped; small counts, days and years may stand alone.
        assertTrue(MoneyChatObi.check("Groceries were about £1,187 across 3 weeks, by 28 Oct 2026.", sent) != null)
        // A worked-out or made-up figure never shows.
        assertNull(MoneyChatObi.check("That's £120 more than usual.", sent))
        assertNull(MoneyChatObi.check("Groceries are 64% of it.", sent))
        assertNull(MoneyChatObi.check("Only £5 left.", sent))
        // No links or markup, nothing over-long.
        assertNull(MoneyChatObi.check("See https://example.com for more.", sent))
        assertNull(MoneyChatObi.check("**Groceries** led.", sent))
        assertNull(MoneyChatObi.check("x".repeat(MoneyChatObi.MAX_ANSWER + 1), sent))
    }

    @Test
    fun theTurnSaysWhoWroteIt() {
        val ok = MoneyChatObi.turn("Why?", "October so far is £68.50.", listOf("£68.50"))
        assertTrue(ok.byObi)
        assertFalse(ok.refused)
        val made = MoneyChatObi.turn("Why?", "It's £999.", listOf("£68.50"))
        assertEquals(MoneyChatObi.UNCHECKED, made.text)
        assertTrue(made.refused)
        assertFalse(made.byObi)
        assertEquals(MoneyChatObi.NOTHING, MoneyChatObi.turn("Why?", null, emptyList()).text)
    }
}
