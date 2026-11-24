package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 7: Errands' three numbers, its cards, + Add's choice and the row sheets. */
class ErrandsPageTest {
    private val tesco = ErrandGroup("Tesco", "tesco", listOf(ErrandRow("a", "milk", null, false), ErrandRow("b", "batteries", null, false)))
    private val post = ErrandGroup("Post office", "post office", listOf(ErrandRow("c", "parcel", null, false)))
    private val stamps = ErrandRow("d", "buy stamps", "Post office · done today", true)
    private val view = ErrandView(listOf(tesco, post), listOf(stamps), "3 errands at 2 places", 3)

    @Test
    fun threeNumbersWaitingPlacesAndDoneThisWeek() {
        val p = ErrandsPageRules.page(view)
        assertEquals("Errands", p.title)
        assertEquals(
            listOf(PageTile("3", "errands waiting", false), PageTile("2", "places", false), PageTile("1", "done this week", false)),
            p.tiles,
        )
        // Nothing waiting but one ticked off (a blank page has no tiles at all).
        val empty = ErrandsPageRules.page(ErrandView(emptyList(), listOf(stamps), ErrandRules.EMPTY_SUMMARY, 0))
        assertEquals(listOf("No", "No", "1"), empty.tiles.map { it.number })
        assertTrue(empty.tiles.all(LifePageRules::readsWhole))
        assertEquals(listOf("things"), p.addChoices.map { it.key })
        assertEquals(ErrandRules.HINT, ErrandsPageRules.hint(ErrandsPageRules.ADD_THINGS))
    }

    @Test
    fun aRowAPlaceWithWhatsThereAndHowMany() {
        val p = ErrandsPageRules.page(view)
        assertEquals(listOf("Waiting", "Done lately"), p.cards.map { it.title })
        val first = p.cardAt(0).rowAt(0)
        assertEquals("Tesco", first.name)
        assertEquals("Milk, batteries", first.line)
        assertEquals("2 to do", first.whenText)
        assertEquals("1 to do", p.cardAt(0).rowAt(1).whenText)
        val done = p.cardAt(1).rowAt(0)
        assertEquals("Buy stamps", done.name)
        assertEquals("Post office · done today", done.line)
    }

    @Test
    fun waitingsPillCountsTheErrandsTheTileDoes() {
        // Found and fixing, week 5, A6: "Waiting 3" (places) sat beside "6 errands waiting".
        val p = ErrandsPageRules.page(view)
        assertEquals(p.tileAt(0).number, "${p.cardAt(0).count}")
        assertEquals(3, p.cardAt(0).count)
        assertEquals(2, p.cardAt(0).rowCount)
    }

    @Test
    fun aBlankPageSaysItOnceWithNoTilesCardsOrNote() {
        // Walk-through 2026-11-22 A15: the blank page said it four ways (the summary, three "None" tiles, Waiting's line
        // naming "+ Add", the colon note); now only the header's one line and its button.
        val v = ErrandView.EMPTY
        val p = ErrandsPageRules.page(v)
        assertTrue(ErrandsPageRules.blank(v))
        assertFalse(ErrandsPageRules.showsTiles(v))
        assertFalse(ErrandsPageRules.showsNote(v))
        assertEquals(0, p.tileCount)
        assertEquals(0, p.cards.size)
        assertEquals(ErrandsPageRules.EMPTY_LINE, ErrandsPageRules.summary(v))
        assertFalse("+ Add" in ErrandsPageRules.EMPTY_LINE)
        assertEquals("Add an errand", ErrandsPageRules.ADD_FIRST)
        assertEquals(listOf(ErrandsPageRules.ADD_THINGS), p.addChoices.map { it.key })
        // The Fold's near-me card waits too: it reminds only at a waiting errand's place.
        val off = ErrandPlaceRules.setting(false, false, false, 0)
        val fold = ErrandsPageRules.page(v, off)
        assertEquals(0, fold.cards.size)
        assertEquals(ErrandsPageRules.INFO_NEAR, fold.info)
        // One errand brings the tiles, the cards and the note back.
        assertFalse(ErrandsPageRules.blank(view))
        assertTrue(ErrandsPageRules.showsNote(view))
        assertEquals(view.summary, ErrandsPageRules.summary(view))
        assertEquals(3, ErrandsPageRules.page(view).tileCount)
    }

    @Test
    fun onlyDoneErrandsIsNotBlankAndWaitingSaysWhatToDo() {
        val v = ErrandView(emptyList(), listOf(stamps), ErrandRules.EMPTY_SUMMARY, 0)
        assertFalse(ErrandsPageRules.blank(v))
        val p = ErrandsPageRules.page(v)
        assertEquals(3, p.tileCount)
        assertEquals(ErrandsPageRules.WAITING_EMPTY, p.cardAt(0).empty)
        assertEquals(ErrandRules.EMPTY_SUMMARY, ErrandsPageRules.summary(v))
        val done = ErrandsPageRules.page(view.copy(done = emptyList()))
        assertEquals(ErrandsPageRules.DONE_EMPTY, done.cardAt(1).empty)
    }

    @Test
    fun theFoldHasTheNearMeCardTheMacDoesNot() {
        val off = ErrandPlaceRules.setting(false, false, false, 0)
        val p = ErrandsPageRules.page(view, off)
        assertEquals(listOf("Waiting", "Done lately", "Near-me reminders"), p.cards.map { it.title })
        assertEquals(ErrandsPageRules.INFO_NEAR, p.info)
        val row = p.cardAt(2).rowAt(0)
        assertEquals(ErrandsPageRules.KEY_NEAR, row.key)
        assertEquals("Off", row.whenText)
        val needs = ErrandsPageRules.page(view, ErrandPlaceRules.setting(true, false, false, 0)).cardAt(2).rowAt(0)
        assertEquals("On", needs.whenText)
        assertTrue(needs.lit)
        assertEquals(ErrandsPageRules.INFO, ErrandsPageRules.page(view).info)
    }

    @Test
    fun moreThanFivePlacesHaveSeeAll() {
        val many = (1..7).map { ErrandGroup("Shop $it", "shop $it", listOf(ErrandRow("e$it", "thing", null, false))) }
        val v = ErrandView(many, emptyList(), "7 errands at 7 places", 7)
        val card = ErrandsPageRules.page(v).cardAt(0)
        assertEquals(7, card.count)
        assertEquals(5, card.rowCount)
        assertEquals(LifePageRules.SEE_ALL, card.more)
        assertEquals(7, ErrandsPageRules.allRows(v, ErrandsPageRules.WAITING).size)
    }

    @Test
    fun aRowsSheetFindsItsPlaceOrErrandAsItIsNow() {
        assertEquals(tesco, ErrandsPageRules.place(view, ErrandsPageRules.placeKey("tesco")))
        assertNull(ErrandsPageRules.place(view, ErrandsPageRules.placeKey("boots")))
        assertEquals(stamps, ErrandsPageRules.errand(view, ErrandsPageRules.doneKey("d")))
        // Put back: the sheet still finds it, now waiting.
        val back = ErrandView(listOf(post.copy(rows = post.rows + stamps.copy(meta = null, done = false))), emptyList(), "", 2)
        assertEquals(false, ErrandsPageRules.errand(back, ErrandsPageRules.doneKey("d"))?.done)
        assertNull(ErrandsPageRules.errand(ErrandView.EMPTY, ErrandsPageRules.doneKey("d")))
    }
}
