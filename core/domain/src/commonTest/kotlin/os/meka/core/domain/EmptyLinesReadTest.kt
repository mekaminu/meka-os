package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-through week 4, A16: an empty page says what to tap in plain words, and its tiles read as sentences. */
class EmptyLinesReadTest {
    private val lines = listOf(
        DateNightPageRules.COMING_EMPTY, ShoppingRules.EMPTY_LINE, MealsPageRules.FAVOURITES_EMPTY,
        TripsPageRules.TRIPS_EMPTY, TripsPageRules.BOOKED_EMPTY, ErrandsPageRules.WAITING_EMPTY,
        KidsPageRules.FEES_EMPTY, KidsPageRules.MONEY_EMPTY, SchoolPageRules.OFF_EMPTY, SchoolPageRules.DATES_EMPTY,
        SchoolPageRules.WEEKLY_EMPTY, SchoolPageRules.COUNCIL_EMPTY, ReadingPageRules.NEXT_EMPTY,
        CarPageRules.PARKED_EMPTY, BinsPageRules.YOURS_EMPTY, BinsPageRules.ONE_OFFS_EMPTY,
    // BinsPageRules.OWN_DAYS left (week 5, A12): it's a row's line, and the row's own sheet holds Use the council's dates.
    ) + EmergencySection.entries.map { EmergencyPageRules.empty(it) }

    @Test
    fun noEmptyLineStitchesAHintOntoAPlaceholder() {
        for (l in lines) {
            assertFalse("→" in l, "\"$l\" still says + Add → …")
            assertTrue("tap + add and pick “" in l.lowercase(), "\"$l\" doesn't say what to tap")
            assertTrue(l.endsWith("."), "\"$l\" isn't a sentence")
        }
    }

    @Test
    fun noneReadsBeforeAWordItGoesWithAndNoBeforeANoun() {
        assertEquals(PageTile("No", "favourites", false), LifePageRules.none("favourites"))
        assertEquals(PageTile("No", "leave to come", false), LifePageRules.none("leave to come"))
        assertEquals(PageTile("None", "to book", false), LifePageRules.none("to book"))
        assertEquals(PageTile("None", "booked yet", false), LifePageRules.none("booked yet"))
        assertFalse(LifePageRules.readsWhole(PageTile("None", "favourites", false)))
        assertTrue(LifePageRules.readsWhole(PageTile("3", "favourites", false)))
    }
}
