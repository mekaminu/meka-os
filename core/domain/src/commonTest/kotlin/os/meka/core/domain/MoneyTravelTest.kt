package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTravelTest {
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val names = mapOf("a-card" to "Credit card", "a-current" to "Current account", "a-travel" to "Travel card")

    private fun l(acc: String, m: Int, d: Int, pence: Long, text: String, c: MoneyCategory = MoneyCategoryRules.categoryOf(text, pence)) =
        MoneyTravelLine(acc, CivilDate.toEpochDay(2026, m, d), pence, text, c)

    private val trip = listOf(
        l("a-card", 7, 20, -10_000, "RESTAURANTE SOL MADRID EUR 116.20 VISA RATE 1.1620"),
        l("a-card", 7, 20, -299, "NON-STERLING TRANSACTION FEE", MoneyCategory.FEES),
        l("a-card", 7, 21, -5_000, "MERCADO SAN MIGUEL 58.10 EUR"),
        l("a-card", 7, 21, -150, "Non-sterling transaction fee", MoneyCategory.FEES),
        l("a-travel", 7, 22, -4_000, "TAPAS BAR €46.50"),
        l("a-travel", 7, 22, -2_000, "METRO DE MADRID EUR 23.00"),
        l("a-travel", 7, 23, -1_000, "CAFE CENTRAL EUR 11.60"),
        l("a-current", 9, 1, -4_500, "TESCO STORES 2041"),
    )

    @Test
    fun foreignLinesAndFeesAreToldApart() {
        assertTrue(MoneyTravel.isAbroad("RESTAURANTE SOL EUR 116.20"))
        assertTrue(MoneyTravel.isAbroad("AMAZON.COM \$24.99"))
        assertTrue(MoneyTravel.isAbroad("SHOP 58.10 USD"))
        assertTrue(MoneyTravel.isAbroad("HOTEL ROMA @ 1.1534"))
        assertFalse(MoneyTravel.isAbroad("NON-STERLING TRANSACTION FEE"))
        assertFalse(MoneyTravel.isAbroad("VISA RATE 1.1534 NON-STERLING TRANSACTION FEE"))
        assertFalse(MoneyTravel.isAbroad("TESCO STORES 2041"))
        assertFalse(MoneyTravel.isAbroad("EUROSTAR INTERNATIONAL LONDON"))
        assertEquals("about 3%", MoneyTravel.rate(300, 10_000))
        assertEquals("about 2.9%", MoneyTravel.rate(290, 10_000))
    }

    @Test
    fun theCardWithNoFeesIsTheOneToUse() {
        val v = assertNotNull(MoneyTravel.view(trip, names, today))
        assertEquals("PAYING ABROAD", v.head)
        assertEquals(
            "Abroad, pay with Travel card: no foreign fees on your 3 payments abroad in the last year. Credit card charged £4.49 in fees (about 3%).",
            v.tip,
        )
        assertTrue(v.tipLit)
        assertEquals(listOf("Travel card", "Credit card"), v.rows.map { it.name })
        assertEquals("No foreign fees · 3 payments abroad · £70", v.rowAt(0).line)
        assertTrue(v.rowAt(0).good)
        assertEquals("£4.49 in foreign fees on 2 payments · about 3% of £150", v.rowAt(1).line)
        assertFalse(v.rowAt(1).good)
        assertNull(v.banner)
    }

    @Test
    fun abroadOrATripNearPutsTheBannerFirst() {
        val abroad = MoneyTravel.view(trip, names, today, abroadNow = true)!!
        assertEquals("You're abroad · pay with Travel card: no foreign fees there before", abroad.banner)
        val soon = MoneyTravel.view(trip, names, today, trip = "Barcelona" to today + 3)!!
        assertEquals("Barcelona in 3 days · if it's abroad, pay with Travel card: no foreign fees there before", soon.banner)
        val far = MoneyTravel.view(trip, names, today, trip = "Barcelona" to today + 30)!!
        assertNull(far.banner)
        // Only fee-charging cards: say so, and what it would have saved.
        val feesOnly = MoneyTravel.view(trip.filter { it.account != "a-travel" }, names, today, abroadNow = true)!!
        assertEquals("You're abroad · your cards charged foreign fees before (about 3%)", feesOnly.banner)
        assertEquals(
            "Every account you've paid with abroad charged foreign fees: £4.49 in the last year. A card with no foreign fees would have saved that.",
            feesOnly.tip,
        )
    }

    @Test
    fun nothingAbroadShowsNothingUnlessHeIsGoing() {
        val home = trip.filter { it.account == "a-current" }
        assertNull(MoneyTravel.view(home, names, today))
        val going = MoneyTravel.view(home, names, today, abroadNow = true)!!
        assertEquals(MoneyTravel.NOTHING, going.tip)
        assertEquals("You're abroad · nothing paid abroad in your statements yet", going.banner)
        assertTrue(going.rows.isEmpty())
        // Over a year old doesn't count.
        assertNull(MoneyTravel.view(trip, names, CivilDate.toEpochDay(2027, 9, 1)))
    }

    @Test
    fun askAnswersWhichCardToUseAbroad() {
        assertTrue(MoneyAskRules.isAbout("which card should I use abroad?"))
        assertTrue(MoneyAskRules.isAbout("how much have I paid in foreign fees"))
        assertTrue(MoneyAskRules.isAbout("which card for my holiday"))
        assertFalse(MoneyAskRules.isAbout("how do I add a trip abroad"))
        val v = MoneyTravel.view(trip, names, today)
        val d = MoneyAskData(emptyList(), today, emptyList(), null, v)
        val a = MoneyAskRules.answer("which card should I use abroad?", d)
        assertTrue(a.startsWith("Abroad, pay with Travel card"), a)
        assertTrue("Credit card: £4.49 in foreign fees" in a, a)
        assertTrue(a.endsWith(MoneyAskRules.NOT_ADVICE), a)
        assertEquals(MoneyTravel.NOTHING, MoneyTravel.answer(null))
    }

    @Test
    fun trips() {
        val t = listOf(
            TripItem("t1", "Lisbon", today + 20, today + 24, 0),
            TripItem("t2", "Barcelona", today + 5, today + 9, 0),
            TripItem("t0", "Rome", today - 10, today - 5, 0),
        )
        assertEquals("Barcelona" to today + 5, MoneyTravel.tripNear(t, today))
        assertNull(MoneyTravel.tripNear(t.take(1), today))
    }
}
