package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsYouGroupsTest {
    private val R = NeedsYouGroupRules

    @Test fun toDecideAndMessagesStartOpen() {
        assertEquals(setOf(NeedsYouGroup.DECIDE, NeedsYouGroup.MESSAGES), R.DEFAULT_OPEN)
        assertEquals(R.DEFAULT_OPEN, R.decode(null))
    }

    @Test fun whatHeLeftOpenIsKept() {
        val open = setOf(NeedsYouGroup.FYI, NeedsYouGroup.TIDIED)
        assertEquals("fyi,tidy", R.encode(open))
        assertEquals(open, R.decode("fyi,tidy"))
        // All closed is kept as closed, never back to the defaults.
        assertEquals(emptySet(), R.decode(""))
        assertEquals(setOf(NeedsYouGroup.MAIL), R.decode("mail, gone ,"))
    }

    @Test fun aTapOpensOrCloses() {
        val once = R.toggle(R.DEFAULT_OPEN, NeedsYouGroup.FYI)
        assertTrue(NeedsYouGroup.FYI in once)
        assertFalse(NeedsYouGroup.FYI in R.toggle(once, NeedsYouGroup.FYI))
    }

    @Test fun openAllAndCloseAll() {
        val shown = listOf(NeedsYouGroup.DECIDE, NeedsYouGroup.MESSAGES, NeedsYouGroup.FYI)
        assertEquals(R.CLOSE_ALL, R.allLabel(R.DEFAULT_OPEN, shown))
        val closed = R.all(R.DEFAULT_OPEN, shown)
        assertEquals(emptySet(), closed)
        assertEquals(R.OPEN_ALL, R.allLabel(closed, shown))
        assertEquals(shown.toSet(), R.all(closed, shown))
    }

    @Test fun aChipOpensJustItsGroups() {
        assertTrue(R.shows(NeedsYouFilter.FYI, NeedsYouGroup.CHATS))
        assertFalse(R.shows(NeedsYouFilter.FYI, NeedsYouGroup.MESSAGES))
        assertTrue(R.shows(NeedsYouFilter.MAIL, NeedsYouGroup.RENEWALS))
        val opened = R.pick(emptySet(), NeedsYouFilter.MAIL)
        assertEquals(setOf(NeedsYouGroup.MAIL, NeedsYouGroup.RENEWALS, NeedsYouGroup.TIDIED), opened)
        assertEquals(emptySet(), R.pick(emptySet(), NeedsYouFilter.ALL))
    }

    @Test fun longGroupsShowFiveAPage() {
        val first = R.page(14, 0)
        assertEquals("1–5 of 14", first.label)
        assertEquals(3, first.pages)
        assertFalse(first.hasPrev)
        assertTrue(first.hasNext)
        val last = R.page(14, 9)
        assertEquals(2, last.page)
        assertEquals("11–14 of 14", last.label)
        assertEquals(listOf(11, 12, 13, 14), last.of((1..14).toList()))
        assertEquals("", R.page(5, 0).label)
        assertEquals(1, R.page(0, 3).pages)
    }

    @Test fun peeksSayWhatIsInside() {
        assertEquals("Alex, Card game testing, Sam, Jo", R.names(listOf("Alex", "Card game testing", "Sam", "Jo")))
        assertEquals("A, B, C, D + 2 more", R.names(listOf("A", "B", "C", "D", "E", "F")))
        assertEquals("No reply needed · Alex, Sam, Jo + 1 more", R.fyiPeek(listOf("Alex", "Sam", "Jo", "Kim")))
        assertEquals("1 approval · 2 requests · 1 from your lists", R.decidePeek(1, 2, 0, 1))
        assertEquals("Streamly · nothing added until you tap Add", R.renewalsPeek(listOf("Streamly")))
        val tiles = listOf(NeedsYouMailTile("41", "tidied", false), NeedsYouMailTile("5", "junk & scams", true))
        assertEquals("41 tidied · 5 junk & scams", R.tidiedPeek(tiles))
    }

    @Test fun aHeadPeeksWhileClosedAndShowsItsPageWhileOpen() {
        val closed = R.head(NeedsYouGroup.FYI, 6, "No reply needed · Alex", open = false, page = R.page(6, 0))
        assertEquals("No reply needed · Alex", closed.peek)
        assertEquals("6", closed.count)
        assertEquals("FYI, 6. Closed. No reply needed · Alex.", closed.spoken)
        val open = R.head(NeedsYouGroup.FYI, 6, "No reply needed · Alex", open = true, page = R.page(6, 0))
        assertEquals("1–5 of 6", open.peek)
        assertEquals("", R.head(NeedsYouGroup.MESSAGES, 0, "", open = true).count)
    }

    @Test fun theSubtitleCountsMail() {
        assertEquals("1 to decide · 4 to reply · 5 FYI · 14 mail", R.subtitle(1, 4, 5, 14))
        assertEquals("3 mail", R.subtitle(0, 0, 0, 3))
        assertEquals(NeedsYouLayoutRules.ALL_CLEAR, R.subtitle(0, 0, 0, 0))
    }
}
