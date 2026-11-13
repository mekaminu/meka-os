package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 15: Ask → Kids as the page shape (invented children). */
class KidsPageTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 10, 15)
    private val alex = AboutChild("Alex", 7, "Oakfield School")
    private val sam = AboutChild("Sam", 10, null)

    private fun record(id: String, checkedDaysAgo: Long, vararg sizes: Pair<KidSize, String>) =
        KidRecord(id, sizes.toMap(), cal.toEpochMs(today - checkedDaysAgo, 12 * 60))

    private fun fee(id: String, title: String, inDays: Long) =
        KidFeeRow(id, title, "£35 · every month · next ${CivilDate.shortLabel(today + inDays)}", inDays <= 3, today + inDays, title)

    private fun money(pocket: String?, toPay: Long, lit: Boolean = false, chores: Int = 0) = KidMoney(
        shown = true, pocketValue = pocket,
        chores = (0 until chores).map { KidChoreRow("ch$it", "Chore $it", "£1 each · not done yet this week", emptyList(), "Chore $it") },
        weekLine = null, paidLine = null, payLabel = if (toPay > 0) "Paid ${KidChoreRules.fmt(toPay)}" else null, toPayPence = toPay, lit = lit,
    )

    @Test
    fun noChildrenIsOneCardPointingAtAboutYouAndThreeWords() {
        val p = KidsPageRules.page(KidsView.EMPTY, today)
        assertEquals(3, p.tiles.size)
        assertTrue(p.tiles.none { it.number.isBlank() || it.number == "—" })
        assertEquals(listOf("Children"), p.cards.map { it.title })
        assertEquals(KidsPageRules.CHILDREN_EMPTY, p.cards[0].empty)
        assertEquals("Add to Kids", p.addTitle)
        assertEquals(KidsPageRules.ADD_CHOICES, p.addChoices)
    }

    @Test
    fun childrenFeesAndMoneyMakeFourCardsWithTheirNumbers() {
        val a = KidsRules.card(alex, record("c-alex", 120, KidSize.SHOES to "13"), today, cal,
            fees = listOf(fee("f1", "Football", 2)), money = money("£5", 800, lit = true, chores = 2))
        val s = KidsRules.card(sam, null, today, cal, fees = listOf(fee("f2", "Swimming", 20)))
        val week = KidWeek("3 things this week", listOf(
            KidWeekDay(today + 1, "Tomorrow", listOf(KidWeekItem("Alex: PE kit", true, "Alex: PE kit"), KidWeekItem("Sam: Training 16:55", false, "Sam"))),
            KidWeekDay(today + 3, "Sun 18 Oct", listOf(KidWeekItem("Alex's Football · £35 due", true, "fee"))),
        ))
        val v = KidsView("summary", listOf(a, s), null, null, week)
        val p = KidsPageRules.page(v, today)
        assertEquals(listOf("This week", "Children", "Club fees", "Chores and pocket money"), p.cards.map { it.title })

        // The numbers: three things this week (lit: kit for tomorrow), Alex's football in 2 days (lit), a size check due.
        assertEquals("3", p.tiles[0].number); assertTrue(p.tiles[0].lit)
        assertEquals("2 days", p.tiles[1].number); assertEquals("to Alex's football", p.tiles[1].label); assertTrue(p.tiles[1].lit)
        assertEquals("1", p.tiles[2].number); assertTrue(p.tiles[2].lit)

        val weekRows = p.cards[0].rows
        assertEquals(listOf("Alex: PE kit", "Sam: Training 16:55", "Alex's Football · £35 due"), weekRows.map { it.name })
        assertEquals("Tomorrow", weekRows[0].whenText)
        assertEquals(KidsPageRules.weekKey(today + 1, 1), weekRows[1].key)

        val kids = p.cards[1].rows
        assertEquals(listOf("Alex", "Sam"), kids.map { it.name })
        assertEquals(KidsPageRules.CHECK, kids[0].whenText); assertTrue(kids[0].lit)
        assertEquals("", kids[1].whenText); assertFalse(kids[1].lit)
        assertEquals(KidsRules.NO_SIZES.substringBefore(" · "), kids[1].line)

        val fees = p.cards[2].rows
        assertEquals(listOf("Football", "Swimming"), fees.map { it.name })
        assertEquals("Alex · £35", fees[0].line)
        assertEquals("2 days", fees[0].whenText); assertTrue(fees[0].lit)
        assertEquals("2 wks", fees[1].whenText); assertFalse(fees[1].lit)

        val m = p.cards[3].rows.single()
        assertEquals("Alex", m.name)
        assertEquals("2 chores · £5 a week", m.line)
        assertEquals("${KidChoreRules.fmt(800)} to pay", m.whenText)
        assertTrue(m.whenText.endsWith("to pay")); assertTrue(m.lit)
    }

    @Test
    fun keysOpenWhatIsThereNowAndNothingOnceItsGone() {
        val a = KidsRules.card(alex, null, today, cal, fees = listOf(fee("f1", "Football", 9)), money = money("£5", 0))
        val week = KidWeek("1 thing", listOf(KidWeekDay(today, "Today", listOf(KidWeekItem("Alex: PE kit", true, "PE")))))
        val v = KidsView("s", listOf(a), null, null, week)
        assertEquals("Alex", KidsPageRules.kid(v, KidsPageRules.kidKey("Alex"))?.name)
        assertEquals("Alex", KidsPageRules.kid(v, KidsPageRules.moneyKey("Alex"))?.name)
        assertTrue(KidsPageRules.isMoney(KidsPageRules.moneyKey("Alex")))
        assertEquals("Football", KidsPageRules.fee(v, KidsPageRules.feeKey("f1"))?.second?.title)
        assertEquals("Alex: PE kit", KidsPageRules.weekItem(v, KidsPageRules.weekKey(today, 0))?.second?.text)
        assertEquals("Today", KidsPageRules.sheetTitle(v, KidsPageRules.weekKey(today, 0)))
        assertEquals("Alex", KidsPageRules.feeOwner(v, KidsPageRules.feeKey("f1"))?.name)
        assertEquals("f1", KidsPageRules.feeRow(v, KidsPageRules.feeKey("f1"))?.id)
        assertEquals("Alex: PE kit", KidsPageRules.weekThing(v, KidsPageRules.weekKey(today, 0))?.text)
        assertNull(KidsPageRules.kid(v, KidsPageRules.kidKey("Sam")))
        assertNull(KidsPageRules.fee(v, KidsPageRules.feeKey("gone")))
        assertNull(KidsPageRules.weekItem(v, KidsPageRules.weekKey(today, 4)))
        assertEquals(KidsPageRules.TITLE, KidsPageRules.sheetTitle(v, "fee:gone"))
        // Pocket money paid up: the third number says so in words.
        val p = KidsPageRules.page(v, today)
        assertEquals("Paid", p.tiles[2].number)
        assertEquals("", p.cards.last().rows.single().whenText)
    }

    @Test
    fun aTypedLineSaysWhoseItIsOrIsTheOnlyChilds() {
        val two = listOf("Alex", "Sam Lee")
        assertEquals("Alex" to "Football £35 monthly from 1 Nov", KidsPageRules.split("alex: Football £35 monthly from 1 Nov", two))
        assertEquals("Sam Lee" to "Tidy room £1", KidsPageRules.split("Sam: Tidy room £1", two))
        assertEquals("Sam Lee" to "£5", KidsPageRules.split("Sam Lee £5", two))
        assertEquals("Alex" to "Tidy room £1", KidsPageRules.split("Alex Tidy room £1", two))
        assertEquals(null to "Tidy room £1", KidsPageRules.split("Tidy room £1", two))
        assertEquals("Alex" to "Tidy room £1", KidsPageRules.split("Tidy room £1", listOf("Alex")))
        assertEquals("Alex" to "Swimming: £60 on the 5th", KidsPageRules.split("Swimming: £60 on the 5th", listOf("Alex")))
        assertTrue(KidsPageRules.whoseFirst(two).contains("Alex: …"))
    }

    @Test
    fun aLongSizesLineIsShortenedOnTheCard() {
        val a = KidsRules.card(alex, record("c-alex", 1, KidSize.SHOES to "13", KidSize.BOOTS to "1½", KidSize.TOPS to "7–8 years",
            KidSize.TROUSERS to "7–8 years"), today, cal)
        val row = KidsPageRules.page(KidsView("s", listOf(a), null, null), today).cards.first { it.key == KidsPageRules.CHILDREN }.rows.single()
        assertTrue(row.line.length <= KidsPageRules.SHORT_LINE + 1, row.line)
        assertNotNull(a.sizesLine)
    }
}
