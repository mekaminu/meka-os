package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 2: People's three numbers, Coming up and Everyone, and + Add's field. */
class PeoplePageTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val today = CivilDate.toEpochDay(2026, 10, 12) // Mon 12 Oct 2026
    private val now = cal.toEpochMs(today, 15 * 60)

    private fun promise(id: String, title: String, to: String?, state: DueState = DueState.LATER) =
        PromiseItem(id, title, to, null, 0, null, state, "$to · by Thu 15 Oct", false)

    private fun waiting(id: String, title: String, who: String?, state: DueState = DueState.NONE) =
        WaitingItem(id, title, who, null, 0, null, null, state, "$who · since Sat 10 Oct", false)

    private fun birthday(person: String, m: Int, d: Int, year: Int = 0) =
        Occasion("o-$person", person, OccasionKind.BIRTHDAY, m, d, year, 3, 0, 0)

    private fun view(contacts: List<ContactSeen> = emptyList()) = PeopleRules.view(
        promises = listOf(promise("p1", "Fix the bike", "Theo"), promise("p2", "Lift to football", "Sam", DueState.DUE)),
        waiting = listOf(waiting("w1", "The drill back", "Alex")),
        occasions = listOf(birthday("Theo", 10, 17, 2016), birthday("Mum", 11, 3, 1966), birthday("Jo", 3, 1)),
        contacts = contacts, today = today, nowMs = now, cal = cal,
    )

    @Test
    fun threeNumbersTheNextBirthdayWhatsDueAndHowMany() {
        val p = PeoplePageRules.page(view(), today)
        assertEquals("People", p.title)
        assertEquals(3, p.tileCount)
        assertEquals(PageTile("Sat", "Theo's birthday", true), p.tileAt(0))
        assertEquals(PageTile("1", "due or to chase", true), p.tileAt(1))
        assertEquals(PageTile("5", "people", false), p.tileAt(2))
        assertEquals(listOf("birthday", "promise", "waiting"), p.addChoices.map { it.key })
    }

    @Test
    fun comingUpIsWhatsDueThenBirthdaysInTheMonthSoonestFirst() {
        val p = PeoplePageRules.page(view(), today)
        val coming = p.cardAt(0)
        assertEquals("Coming up", coming.title)
        assertEquals(listOf("Sam", "Theo", "Mum"), coming.rows.map { it.name }) // Jo's birthday is months off
        assertEquals("due", coming.rows[0].whenText)
        assertTrue(coming.rows[0].lit)
        assertEquals("You promised 1, 1 due", coming.rows[0].line)
        assertEquals("Birthday · turns 10", coming.rows[1].line)
        assertEquals("Sat", coming.rows[1].whenText)
        assertEquals("3 Nov", coming.rows[2].whenText)
        assertFalse(coming.rows[2].lit) // three weeks off: not brass
        assertNull(coming.more)
    }

    @Test
    fun everyoneShowsTheLastContactElseWhatsOwedAndSeeAllPastFive() {
        val seen = listOf(ContactSeen("Alex", now - hour, CaptureApp.WHATSAPP, false))
        val p = PeoplePageRules.page(view(seen), today)
        val all = p.cardAt(1)
        assertEquals(5, all.count)
        assertTrue(all.rows.first { it.name == "Alex" }.line.startsWith("Last message"))
        assertEquals("You promised 1", all.rows.first { it.name == "Theo" }.line)
        assertEquals(PeoplePageRules.rowKey("theo"), all.rows.first { it.name == "Theo" }.key)
        assertEquals("Theo", PeoplePageRules.person(view(), "person:theo")?.name)
        assertNull(all.more)
        val many = PeopleRules.view(
            (1..7).map { promise("p$it", "Thing", "Person $it") }, emptyList(), emptyList(), emptyList(), today, now, cal,
        )
        val big = PeoplePageRules.page(many, today).cardAt(1)
        assertEquals(7, big.count)
        assertEquals(5, big.rowCount)
        assertEquals("See all", big.more)
    }

    @Test
    fun anEmptyPageSaysItInWords() {
        val p = PeoplePageRules.page(PeopleView.EMPTY, today)
        assertEquals(listOf("No birthdays", "Nothing", "0"), p.tiles.map { it.number })
        assertEquals(PeoplePageRules.COMING_EMPTY, p.cardAt(0).empty)
        assertEquals(PeoplePageRules.EVERYONE_EMPTY, p.cardAt(1).empty)
        assertTrue(p.cards.all { it.rows.isEmpty() })
    }

    @Test
    fun aBirthdayTodayTomorrowOrNextWeekReadsPlainly() {
        assertEquals("Today", PeoplePageRules.dayWord(today, today))
        assertEquals("Tomorrow", PeoplePageRules.dayWord(today + 1, today))
        assertEquals("Fri", PeoplePageRules.dayWord(today + 4, today))
        assertEquals("19 Oct", PeoplePageRules.dayWord(today + 7, today))
    }

    @Test
    fun addsFieldReadsWhoAndWhat() {
        assertEquals(PeoplePageRules.Owed("Sam", "Fix his bike"), PeoplePageRules.readOwed("  Sam:   fix his bike "))
        assertEquals(PeoplePageRules.Owed(null, "Return the books"), PeoplePageRules.readOwed("return the books"))
        assertNull(PeoplePageRules.readOwed("Sam:  "))
        assertNull(PeoplePageRules.readOwed("   "))
        assertEquals("Added your promise to Sam: Fix his bike", PeoplePageRules.addedLine("promise", PeoplePageRules.Owed("Sam", "Fix his bike")))
        assertEquals("Added to Waiting for: The drill back, from Alex", PeoplePageRules.addedLine("waiting", PeoplePageRules.Owed("Alex", "The drill back")))
        assertEquals("Added your promise: Tidy up", PeoplePageRules.addedLine("promise", PeoplePageRules.Owed(null, "Tidy up")))
    }
}
