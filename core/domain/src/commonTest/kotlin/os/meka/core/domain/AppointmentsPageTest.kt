package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 10: Ask → Appointments as the page shape (both apps). */
class AppointmentsPageTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val today = d(11, 13)
    private fun item(
        id: String, title: String, weeks: Int = 0, months: Int = 0, last: Long? = null, booked: Long? = null, minute: Int? = null, later: Long? = null,
    ) = AppointmentItem(id, title, weeks, months, last, booked, minute, later, 1L)

    // Due: last went 13 May, every 6 months → due 13 Nov (today).
    private val dentist = item("a1", "Dentist", months = 6, last = d(5, 13))
    // Overdue: haircut every 6 weeks, last 1 Sep.
    private val haircut = item("a2", "Haircut", weeks = 6, last = d(9, 1))
    // No visit noted.
    private val physio = item("a3", "Physio", months = 3)
    // Booked for Tue 17 Nov 09:30.
    private val hygienist = item("a4", "Hygienist", months = 6, last = d(5, 1), booked = d(11, 17), minute = 9 * 60 + 30)
    // Later: optician every 2 years, last March 2026.
    private val optician = item("a5", "Optician", months = 24, last = d(3, 10))

    private fun view(items: List<AppointmentItem> = listOf(dentist, haircut, physio, hygienist, optician)) = AppointmentRules.view(items, today)

    @Test
    fun theThreeNumbersAreDueToBookTheNextBookedVisitAndHowManyHeKeepsUpWith() {
        val p = AppointmentsPageRules.page(view(), today)
        assertEquals("Appointments", p.title)
        assertEquals(listOf("2", "4 days", "5"), p.tiles.map { it.number })
        assertEquals(listOf("due to book", "to Hygienist", "kept up with"), p.tiles.map { it.label })
        assertEquals(listOf(true, true, false), p.tiles.map { it.lit })
        assertEquals(listOf(AppointmentsPageRules.TO_BOOK, AppointmentsPageRules.BOOKED, AppointmentsPageRules.NEXT_DUE), p.cards.map { it.key })
    }

    @Test
    fun toBookHoldsTheOverdueTheDueAndTheUndatedLitWithWhenOnTheRight() {
        val p = AppointmentsPageRules.page(view(), today)
        val toBook = p.cards.first { it.key == AppointmentsPageRules.TO_BOOK }.rows
        assertEquals(listOf("Haircut", "Dentist", "Physio"), toBook.map { it.name })
        assertEquals(listOf(AppointmentsPageRules.OVERDUE, "Today", AppointmentsPageRules.ADD_A_DATE), toBook.map { it.whenText })
        assertTrue(toBook.all { it.lit })
        assertEquals("appt:a2", toBook[0].key)
        // Only how often on the page (one line on the cover screen); the last visit is in the sheet.
        assertEquals(listOf("Every 6 weeks", "Every 6 months", "Every 3 months"), toBook.map { it.line })
    }

    @Test
    fun bookedShowsTheDayAndTimeAndNextDueTheMonthWhenFarOff() {
        val p = AppointmentsPageRules.page(view(), today)
        val booked = p.cards.first { it.key == AppointmentsPageRules.BOOKED }.rows.single()
        assertEquals("Hygienist", booked.name)
        assertEquals("Tue 17 Nov 09:30", DateGlue.plain(booked.whenText))
        assertTrue(booked.lit)
        val later = p.cards.first { it.key == AppointmentsPageRules.NEXT_DUE }.rows.single()
        assertEquals("Optician", later.name)
        assertEquals("Mar 2028", DateGlue.plain(later.whenText))
        assertFalse(later.lit)
    }

    @Test
    fun notYetSaysSoAndIsNotCountedAsDue() {
        val p = AppointmentsPageRules.page(view(listOf(dentist.copy(laterDay = today + 14))), today)
        val row = p.cards.first().rows.single()
        assertEquals(AppointmentsPageRules.NOT_YET, row.whenText)
        assertEquals("None", p.tiles[0].number)
    }

    @Test
    fun emptySaysHowToStartAndOffersTheCommonOnes() {
        val p = AppointmentsPageRules.page(AppointmentView.EMPTY, today)
        assertEquals(listOf("None", "None", "None"), p.tiles.map { it.number })
        assertEquals(listOf(AppointmentsPageRules.TO_BOOK), p.cards.map { it.key })
        assertEquals(AppointmentsPageRules.EMPTY, p.cards.single().empty)
        assertEquals(listOf(AppointmentsPageRules.ADD_KEPT, AppointmentsPageRules.ADD_BOOKED, AppointmentsPageRules.ADD_COMMON), p.addChoices.map { it.key })
        assertEquals(AppointmentRules.HINT, AppointmentsPageRules.hint(AppointmentsPageRules.ADD_KEPT))
        assertNull(AppointmentsPageRules.hint(AppointmentsPageRules.ADD_COMMON))
    }

    @Test
    fun allInHandSaysSoAndTheCommonChoiceGoesOnceEveryPresetIsIn() {
        val all = AppointmentRules.PRESETS.mapIndexed { i, pr -> item("p$i", pr.title, pr.everyWeeks, pr.everyMonths, last = today - 1) }
        val p = AppointmentsPageRules.page(view(all), today)
        assertEquals(AppointmentsPageRules.TO_BOOK_EMPTY, p.cards.first().empty)
        assertTrue(p.cards.first().rows.isEmpty())
        assertTrue(p.addChoices.none { it.key == AppointmentsPageRules.ADD_COMMON })
        // Six on Next due: five shown, See all for the rest.
        val next = p.cards.first { it.key == AppointmentsPageRules.NEXT_DUE }
        assertEquals(6, next.count)
        assertEquals(LifePageRules.MAX_ROWS, next.rows.size)
        assertEquals(LifePageRules.SEE_ALL, next.more)
        assertEquals(6, AppointmentsPageRules.allRows(view(all), AppointmentsPageRules.NEXT_DUE, today).size)
    }

    @Test
    fun aRowsKeyFindsItAndTheLastWentFieldTypesALineForIt() {
        val v = view()
        assertEquals("Physio", AppointmentsPageRules.row(v, "appt:a3")?.title)
        assertNull(AppointmentsPageRules.row(v, "appt:gone"))
        assertTrue(AppointmentsPageRules.asksLastWent(AppointmentsPageRules.row(v, "appt:a3")!!))
        assertFalse(AppointmentsPageRules.asksLastWent(AppointmentsPageRules.row(v, "appt:a1")!!))
        assertEquals("Physio last 12 Apr", AppointmentsPageRules.lastWentLine("Physio", "12 Apr"))
        assertEquals("Physio last March", AppointmentsPageRules.lastWentLine("Physio", " last March"))
        // The typed line updates the one on the list (the same thing by name).
        val parsed = AppointmentRules.parse(AppointmentsPageRules.lastWentLine("Physio", "12 Apr"), today, 12 * 60)
        assertEquals(d(4, 12), parsed?.lastDay)
    }
}
