package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board redesign, Option B, slice 3: the colour categories and the Side hustle and House lists. */
class BoardAreaTest {
    private fun task(
        title: String, id: String = "t1", area: String? = null, kind: SomedayKind? = null, someday: Boolean = false, source: String? = null,
    ) = Task(
        id, title, null, if (someday) Lifecycle.SOMEDAY else Lifecycle.ACTIVE, null, null, null, 0, null, kind, 0, null, false,
        source = source, area = area,
    )

    private fun card(title: String, id: String = "t1", area: String? = null, kind: SomedayKind? = null, someday: Boolean = false) =
        BoardCard(task(title, id, area, kind, someday), title, null, null, false, false)

    @Test
    fun navilifeGuessesFromPlainWordsInAFixedOrder() {
        assertEquals(BoardArea.FAMILY, BoardAreaRules.guess(task("Reading book back to school")))
        assertEquals(BoardArea.HOUSE, BoardAreaRules.guess(task("Boiler service before December")))
        assertEquals(BoardArea.HOUSE, BoardAreaRules.guess(task("Garden office quotes")))
        assertEquals(BoardArea.HOUSE, BoardAreaRules.guess(task("Book MOT")))
        assertEquals(BoardArea.SIDE_HUSTLE, BoardAreaRules.guess(task("Send Alex the quote")))
        assertEquals(BoardArea.SIDE_HUSTLE, BoardAreaRules.guess(task("Invoice 0142 — chase")))
        assertEquals(BoardArea.MONEY, BoardAreaRules.guess(task("Move £ to savings pot")))
        assertEquals(BoardArea.MONEY, BoardAreaRules.guess(task("Pay the gym")))
        assertEquals(BoardArea.WORK, BoardAreaRules.guess(task("Team stand-up notes")))
        assertEquals(BoardArea.FAMILY, BoardAreaRules.guess(task("Pay the school trip")))
        // Whole words only: "motivation" isn't an MOT, "billing" isn't a bill, "paying" isn't "pay".
        assertNull(BoardAreaRules.guess(task("Motivation playlist")))
        assertNull(BoardAreaRules.guess(task("Read about billing systems")))
        assertNull(BoardAreaRules.guess(task("Call Sam")))
        // A stem at a word's start: decorating, decorator.
        assertEquals(BoardArea.HOUSE, BoardAreaRules.guess(task("Decorating the hall")))
    }

    @Test
    fun whereItCameFromAndItsSomedayKindComeFirst() {
        assertEquals(BoardArea.FAMILY, BoardAreaRules.guess(task("Sign the form", id = "st9")))
        assertEquals(BoardArea.HOUSE, BoardAreaRules.guess(task("Shed", kind = SomedayKind.HOME_IMPROVEMENT)))
        assertEquals(BoardArea.MONEY, BoardAreaRules.guess(task("New strategy", kind = SomedayKind.TRADING)))
    }

    @Test
    fun whatHePickedWinsAndNoAreaStopsTheGuess() {
        assertEquals(BoardArea.WORK, BoardAreaRules.of(task("Boiler service", area = "WORK")))
        assertNull(BoardAreaRules.of(task("Boiler service", area = BoardAreaRules.NONE)))
        assertEquals(BoardArea.HOUSE, BoardAreaRules.of(task("Boiler service", area = "SOMETHING_NEWER")))
        assertEquals("House", BoardAreaRules.cardLabel(card("Boiler service")))
        assertNull(BoardAreaRules.cardLabel(card("Call Sam")))
        assertTrue(card("Boiler service").accessibilityLabel.contains("house"))
    }

    @Test
    fun theMenuListsTheFiveTheOneShownTickedThenNoArea() {
        val rows = BoardAreaRules.choices(card("Boiler service"))
        assertEquals(listOf("Family", "Work", "Side hustle", "Money", "✓ House", "No area"), rows.map { it.label })
        assertEquals(BoardArea.HOUSE, rows.single { it.selected }.area)
        assertNull(rows.last().area)
        assertEquals(5, BoardAreaRules.choices(card("Call Sam")).size)
        assertEquals("FAMILY", BoardAreaRules.raw(BoardArea.FAMILY))
        assertEquals(BoardAreaRules.NONE, BoardAreaRules.raw(null))
        assertEquals("“Boiler service” · House", BoardAreaRules.line("Boiler service", BoardArea.HOUSE))
        assertEquals("“Boiler service” · no area", BoardAreaRules.line("Boiler service", null))
    }

    @Test
    fun aDropOnAListStoresOnlyWhatItNeeds() {
        assertEquals("SIDE_HUSTLE", BoardAreaRules.listRaw(task("Update rates"), BoardArea.SIDE_HUSTLE))
        assertNull(BoardAreaRules.listRaw(task("Boiler service"), BoardArea.HOUSE))
        // Onto Someday: Side hustle and House come off; any other area stays.
        assertEquals(BoardAreaRules.NONE, BoardAreaRules.listRaw(task("Boiler service"), null))
        assertNull(BoardAreaRules.listRaw(task("Pay the gym"), null))
        assertNull(BoardAreaRules.listRaw(task("Call Sam"), null))
        assertEquals("Moved “Update rates” to Side hustle", BoardAreaRules.listLine("Update rates", BoardArea.SIDE_HUSTLE))
        assertEquals("Moved “Boiler service” to Someday", BoardAreaRules.listLine("Boiler service", null))
    }

    @Test
    fun somedaySplitsIntoSomedaySideHustleAndHouse() {
        val week = BoardWeek(20_000, "This week", "19–25 Oct", emptyList())
        val someday = listOf(
            card("Garden office quotes", "a", someday = true),
            card("Update rate card", "b", area = "SIDE_HUSTLE", someday = true),
            card("Learn the banjo", "c", someday = true),
            card("Boiler service", "d", someday = true),
        )
        val lists = BoardWeekLayoutRules.lists(week, WeekBoard(20_002, listOf(week), someday))
        assertEquals(listOf("No day yet", "Someday · 1", "Side hustle · 1", "House · 2"), lists.map { it.title })
        assertEquals(listOf(null, null, BoardArea.SIDE_HUSTLE, BoardArea.HOUSE), lists.map { it.area })
        assertEquals(listOf("c"), lists[1].cards.map { it.id })
        assertEquals(listOf("a", "d"), lists[3].cards.map { it.id })
        assertEquals(4, lists.map { it.key }.toSet().size)
        val empty = BoardWeekLayoutRules.lists(week, WeekBoard(20_002, listOf(week), emptyList()))
        assertEquals("Side hustle, empty. Drop a task here", empty[2].accessibilityLabel)
        assertEquals(BoardWeekLayoutRules.HOUSE_EMPTY, empty[3].empty)
        // The quality gate's nonsense check finds nothing in the words it adds.
        (empty.flatMap { listOf(it.title, it.empty) } + BoardArea.entries.map(BoardAreaRules::label)).forEach {
            assertTrue(ScreenSenseRules.problemsIn(it).isEmpty(), it)
        }
    }
}
