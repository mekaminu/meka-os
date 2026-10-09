package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskFieldTest {
    private fun hit(id: String, kind: SearchKind, detail: String? = null, target: SearchTarget = SearchTarget.TASK, score: Int = 5) =
        SearchHit(id, kind, "Title $id", detail, null, target, null, score)

    private fun view(query: String, vararg groups: Pair<SearchKind, List<SearchHit>>): SearchView {
        val gs = groups.map { (k, hits) -> SearchGroup(k, k.label, hits, 0) }
        return SearchView(query, gs, gs.sumOf { it.hits.size })
    }

    @Test
    fun matchesTakeOneOfEachKindInTurn() {
        val tasks = (1..6).map { hit("t$it", SearchKind.TASK, "Planned today 14:00") }
        val events = listOf(hit("e1", SearchKind.EVENT, "Fri 9 Oct · 15:00–16:00", SearchTarget.INFO))
        val waiting = listOf(hit("w1", SearchKind.WAITING, null, SearchTarget.LISTS_WAITING))
        val m = AskFieldRules.matches(view("den", SearchKind.TASK to tasks, SearchKind.EVENT to events, SearchKind.WAITING to waiting), "den", true)!!
        assertEquals(listOf("t1", "t2", "t3", "e1", "w1"), m.rows.map { it.hit.id })
        assertEquals("See all 8 matches", m.seeAll)
        assertNull(m.empty)
        assertEquals("Task · Planned today 14:00", m.rows[0].line)
        assertEquals("Task: Title t1, Planned today 14:00", m.rows[0].spoken)
        assertEquals("Waiting for", m.rows[4].line)
        assertTrue(m.rows[0].opens)
        assertFalse(m.rows[3].opens, "an event is only shown")
        assertTrue(m.rows[4].opens)
    }

    @Test
    fun doneTasksStayInTheFullSearch() {
        val m = AskFieldRules.matches(view("gym", SearchKind.TASK to listOf(hit("t", SearchKind.TASK)),
            SearchKind.DONE to listOf(hit("d", SearchKind.DONE))), "gym", true)!!
        assertEquals(listOf("t"), m.rows.map { it.hit.id })
        assertEquals("See all 2 matches", m.seeAll)
    }

    @Test
    fun fewMatchesNeedNoSeeAll() {
        val m = AskFieldRules.matches(view("milk", SearchKind.TASK to listOf(hit("t", SearchKind.TASK))), "milk", true)!!
        assertNull(m.seeAll)
    }

    @Test
    fun noMatchesSayWhatReturnDoes() {
        assertEquals("No matches · Return asks MEKA", AskFieldRules.matches(view("what's on"), "what's on", true)!!.empty)
        assertEquals("No matches for “boiler”", AskFieldRules.matches(view("boiler"), "boiler ", false)!!.empty)
    }

    @Test
    fun staleResultsAreNotShownForANewQuery() {
        assertNull(AskFieldRules.matches(view("den"), "dentist", true))
        // The same words typed with other spacing or case are the same query.
        assertTrue(AskFieldRules.matches(view("Den "), "den", true) != null)
        assertNull(AskFieldRules.matches(view("d"), "d", true), "one letter searches nothing")
    }

    @Test
    fun matchesHideOnceAskedAndReturnWhenTypingAgain() {
        assertFalse(AskFieldRules.showMatches("", null))
        assertFalse(AskFieldRules.showMatches("a", null))
        assertTrue(AskFieldRules.showMatches("dentist", null))
        assertFalse(AskFieldRules.showMatches("what's on  today", "what's on today"))
        assertTrue(AskFieldRules.showMatches("what's on tomorrow", "what's on today"))
    }

    @Test
    fun returnAsksOrSearches() {
        assertEquals(AskReturn.ASK, AskFieldRules.onReturn("move dentist to friday", true))
        assertEquals(AskReturn.SEARCH, AskFieldRules.onReturn("dentist", false))
        assertEquals(AskReturn.NOTHING, AskFieldRules.onReturn("  ", true))
        assertEquals(AskReturn.NOTHING, AskFieldRules.onReturn("a", false))
        assertEquals(AskReturn.ASK, AskFieldRules.onReturn("a", true))
    }

    @Test
    fun theFieldSaysWhatItDoes() {
        assertEquals("Ask or search…", AskFieldRules.placeholder(true))
        assertEquals("Search everything…", AskFieldRules.placeholder(false))
        assertTrue(AskFieldRules.idleLine(true).contains("Return asks MEKA"))
        assertTrue(AskFieldRules.idleLine(true, mac = true).endsWith("click."))
        assertTrue(AskFieldRules.idleLine(false, mac = true).contains("this Mac"))
    }
}
