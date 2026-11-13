package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 3: Gifts' three numbers, Coming up and the whole list, and + Add's field. */
class GiftsPageTest {
    private val today = CivilDate.toEpochDay(2026, 10, 12) // Mon 12 Oct 2026

    private fun occ(id: String, person: String, m: Int, d: Int, year: Int = 0, kind: OccasionKind = OccasionKind.BIRTHDAY, sortedFor: Long = 0, ideas: List<String> = emptyList()) =
        Occasion(id, person, kind, m, d, year, 3, sortedFor, 0, ideas.mapIndexed { i, t -> GiftIdea("$id-i$i", id, t, 0) })

    private fun view(vararg o: Occasion) = GiftRules.view(o.toList(), today)

    @Test
    fun threeNumbersTheNextOccasionWhatToSortAndTheIdeas() {
        val p = GiftsPageRules.page(
            view(occ("a", "Theo", 10, 17, 2016, ideas = listOf("Lego", "A ball")), occ("b", "Mum", 11, 3), occ("c", "Jo", 3, 1, ideas = listOf("Book"))),
            today,
        )
        assertEquals("Gifts", p.title)
        assertEquals(3, p.tileCount)
        assertEquals(PageTile("Sat", "Theo's birthday", true), p.tileAt(0))
        assertEquals(PageTile("1", "gift to sort soon", true), p.tileAt(1))
        assertEquals(PageTile("3", "gift ideas noted", false), p.tileAt(2))
        assertEquals(listOf("birthday", "anniversary"), p.addChoices.map { it.key })
    }

    @Test
    fun comingUpIsTheNextTwoMonthsSoonestFirstWithWhatToDo() {
        val theo = CivilDate.toEpochDay(2026, 10, 17)
        val p = GiftsPageRules.page(
            view(occ("c", "Jo", 3, 1), occ("b", "Mum", 11, 3), occ("a", "Theo", 10, 17, sortedFor = theo)),
            today,
        )
        val coming = p.cardAt(0)
        assertEquals("Coming up", coming.title)
        assertEquals(listOf("Theo's birthday", "Mum's birthday"), coming.rows.map { it.name }) // Jo's is months off
        assertEquals("✓ sorted", coming.rows[0].whenText)
        assertEquals("Gift sorted", coming.rows[0].line)
        assertEquals("3 Nov", coming.rows[1].whenText)
        assertTrue(coming.rows[1].line.startsWith("Order by "))
        val all = p.cardAt(1)
        assertEquals("Birthdays and anniversaries", all.title)
        assertEquals(3, all.count)
        assertNull(all.more)
        // A sorted gift two weeks out isn't one to sort.
        assertEquals(PageTile("All sorted", "for the next two weeks", false), p.tileAt(1))
    }

    @Test
    fun moreThanFiveOccasionsShowFiveWithSeeAll() {
        val many = (1..7).map { occ("o$it", "Person$it", 12, it) }.toTypedArray()
        val all = GiftsPageRules.page(view(*many), today).cardAt(1)
        assertEquals(7, all.count)
        assertEquals(5, all.rowCount)
        assertEquals("See all", all.more)
    }

    @Test
    fun emptySaysItInWordsNeverADash() {
        val p = GiftsPageRules.page(GiftsView.EMPTY, today)
        assertEquals(PageTile("No dates", "added yet", false), p.tileAt(0))
        assertEquals(PageTile("Nothing", "to order yet", false), p.tileAt(1))
        assertEquals(PageTile("No ideas", "noted yet", false), p.tileAt(2))
        assertTrue(p.cards.all { it.rows.isEmpty() && it.empty != null })
    }

    @Test
    fun aRowOpensItsOccasion() {
        val v = view(occ("a", "Theo", 10, 17))
        val key = GiftsPageRules.page(v, today).cardAt(0).rowAt(0).key
        assertEquals("gift:a", key)
        assertNotNull(GiftsPageRules.occasion(v, key))
        assertNull(GiftsPageRules.occasion(v, "gift:gone"))
        assertNull(GiftsPageRules.occasionId("person:a"))
    }

    @Test
    fun anAnniversaryChoiceAddsItsWordOnlyWhenMissing() {
        assertEquals("Alex and Sam 3 May anniversary", GiftsPageRules.entry("anniversary", " Alex and Sam 3 May "))
        assertEquals("Wedding 20 Aug 2015", GiftsPageRules.entry("anniversary", "Wedding 20 Aug 2015"))
        assertEquals("Sam 14 Nov", GiftsPageRules.entry("birthday", "Sam 14 Nov"))
        val read = GiftRules.read(GiftsPageRules.entry("anniversary", "Wedding 20 Aug 2015"), 2026)
        assertEquals(OccasionKind.ANNIVERSARY, read?.kind)
        assertEquals(OccasionKind.ANNIVERSARY, GiftRules.read(GiftsPageRules.entry("anniversary", "Alex and Sam 3 May"), 2026)?.kind)
    }
}
