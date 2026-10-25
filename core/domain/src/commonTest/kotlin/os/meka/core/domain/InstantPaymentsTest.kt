package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Taps feel instant for Money's payment chips: a category, Shared, Always shared or Business shows in the next frame. */
class InstantPaymentsTest {
    private fun row(id: String, merchant: String, category: String = "groceries", label: String = "Groceries") = MoneyTxnRow(
        id = id, day = "Sat 24 Oct", merchant = merchant, amount = "£12.40", moneyIn = false, category = category,
        categoryLabel = label, account = "Current", taught = false, spoken = "$merchant, £12.40",
    )

    private fun view(vararg rows: MoneyTxnRow) = MoneyView(
        false, "October so far", null,
        MoneyMonth(0, "October so far", "Spent £37.20", null, false, emptyList(), rows.toList(), 0),
        emptyList(), earlier = true, later = false,
    )

    private val v = view(row("1", "Corner Shop"), row("2", "Oakfield Cafe", "coffee", "Coffee"), row("3", "Corner Shop"))

    private fun txn(shown: MoneyView, id: String) = shown.month!!.txns.first { it.id == id }

    @Test
    fun nothingWaitingIsTheCoresViewItself() {
        assertTrue(v === InstantPaymentRules.shown(v, emptyList()))
        // A tap the core already shows, or on a payment not in this month, changes nothing either.
        assertTrue(v === InstantPaymentRules.shown(v, listOf(InstantPaymentRules.categoryTap("1", "groceries"))))
        assertTrue(v === InstantPaymentRules.shown(v, listOf(InstantPaymentRules.sharedTap("99", true))))
        assertTrue(MoneyView.LOCKED === InstantPaymentRules.shown(MoneyView.LOCKED, listOf(InstantPaymentRules.sharedTap("1", true))))
    }

    @Test
    fun aCategoryGoesOnEveryPaymentToThatPayer() {
        val shown = InstantPaymentRules.shown(v, listOf(InstantPaymentRules.categoryTap("1", "eating_out")))
        for (id in listOf("1", "3")) {
            assertEquals("eating_out", txn(shown, id).category)
            assertEquals("Eating out", txn(shown, id).categoryLabel)
            assertTrue(txn(shown, id).taught, "it's his correction now")
        }
        assertEquals(v.month!!.txns[1], txn(shown, "2"), "another payer is untouched")
        // An unknown key draws nothing (the core will refuse it).
        assertTrue(v === InstantPaymentRules.shown(v, listOf(InstantPaymentRules.categoryTap("1", "nope"))))
    }

    @Test
    fun sharedIsTheOnePaymentAlwaysAndBusinessAreThePayers() {
        val shared = InstantPaymentRules.shown(v, listOf(InstantPaymentRules.sharedTap("1", true)))
        assertTrue(txn(shared, "1").shared)
        assertFalse(txn(shared, "3").shared, "Shared is this payment only")

        val always = InstantPaymentRules.shown(v, listOf(InstantPaymentRules.alwaysTap("3", true)))
        for (id in listOf("1", "3")) { assertTrue(txn(always, id).sharedAlways); assertTrue(txn(always, id).shared) }
        assertFalse(txn(always, "2").shared)

        val business = InstantPaymentRules.shown(v, listOf(InstantPaymentRules.businessTap("1", true)))
        assertTrue(txn(business, "1").business && txn(business, "3").business)
        assertFalse(txn(business, "2").business)
    }

    @Test
    fun aNewerTapIsKeptWhenTheOlderOnesCallReturns() {
        val on = InstantPaymentRules.sharedTap("1", true)
        val off = InstantPaymentRules.sharedTap("1", false)
        var taps = InstantPaymentRules.add(emptyList(), on)
        taps = InstantPaymentRules.add(taps, off)
        assertEquals(listOf(off), taps, "the newer tap replaces the older")
        taps = InstantPaymentRules.forget(taps, on)
        assertEquals(listOf(off), taps, "the first call's return can't flash it back")
        taps = InstantPaymentRules.forget(taps, off)
        assertTrue(taps.isEmpty())
        // Different kinds on one payment are kept side by side.
        val both = InstantPaymentRules.add(listOf(on), InstantPaymentRules.categoryTap("1", "coffee"))
        assertEquals(2, both.size)
        val shown = InstantPaymentRules.shown(v, both)
        assertTrue(txn(shown, "1").shared)
        assertEquals("coffee", txn(shown, "1").category)
    }

    @Test
    fun aFailedTapSaysWhatWentBack() {
        assertEquals("Shared didn't go through · it's back: offline",
            InstantPaymentRules.failedLine(InstantPaymentRules.sharedTap("1", true), "offline"))
        assertEquals("The category didn't go through · it's back",
            InstantPaymentRules.failedLine(InstantPaymentRules.categoryTap("1", "coffee"), null))
    }
}
