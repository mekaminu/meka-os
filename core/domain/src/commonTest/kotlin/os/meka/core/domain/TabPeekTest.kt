package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabPeekTest {
    // Money's five tabs at the cover screen's size, roughly: Spending, Bills, Plan, Trading, Accounts.
    private val labels = listOf(62f, 30f, 30f, 52f, 62f)

    @Test
    fun aRowThatFitsKeepsItsPadding() {
        assertEquals(16f, TabPeekRules.padding(labels, start = 16f, gap = 8f, viewport = 900f, base = 16f))
    }

    @Test
    fun aPillEndingAtTheEdgeIsNudgedIntoAPeek() {
        // At 16 dp, Trading's pill ends at 16 + 94 + 8 + 62 + 8 + 62 + 8 + 84 = 342: right at a 344 dp screen's edge.
        assertFalse(TabPeekRules.peeks(labels, 16f, 8f, 344f, 16f))
        val p = TabPeekRules.padding(labels, start = 16f, gap = 8f, viewport = 344f, base = 16f)
        assertTrue(p in TabPeekRules.MIN_PAD..TabPeekRules.MAX_PAD)
        assertTrue(TabPeekRules.peeks(labels, 16f, 8f, 344f, p), "padding $p should leave a peek")
    }

    @Test
    fun theNearestPaddingToTheBaseWins() {
        val p = TabPeekRules.padding(labels, start = 16f, gap = 8f, viewport = 344f, base = 16f)
        val others = generateSequence(TabPeekRules.MIN_PAD) { it + 0.5f }.takeWhile { it <= TabPeekRules.MAX_PAD }
            .filter { TabPeekRules.peeks(labels, 16f, 8f, 344f, it) }
        assertTrue(others.all { kotlin.math.abs(it - 16f) >= kotlin.math.abs(p - 16f) })
    }

    @Test
    fun theEdgeInAGapOrASliverIsNotAPeek() {
        assertFalse(TabPeekRules.peeks(listOf(50f, 50f), 0f, 20f, 60f, 0f))
        assertFalse(TabPeekRules.peeks(listOf(50f, 50f), 0f, 20f, 75f, 0f)) // a sliver
        assertTrue(TabPeekRules.peeks(listOf(50f, 50f), 0f, 20f, 95f, 0f))
    }

    @Test
    fun theChosenPillIsScrolledWhollyIntoView() {
        // A25: Accounts (the last pill) on a 344 dp screen at 16 dp padding: the content is 16 + 94+8+62+8+62+8+84+8+94 + 16
        // = 460 wide, so the row scrolls to its end (460 − 344 = 116) and Accounts shows whole with 16 dp clear.
        assertEquals(116f, TabPeekRules.scrollTo(labels, 16f, 8f, 344f, 16f, chosen = 4))
        // Spending, the first, needs no scroll; one already in view keeps the row where it is.
        assertEquals(0f, TabPeekRules.scrollTo(labels, 16f, 8f, 344f, 16f, chosen = 0))
        assertEquals(40f, TabPeekRules.scrollTo(labels, 16f, 8f, 344f, 16f, chosen = 2, current = 40f))
        // Back to Spending from the end scrolls back to the start.
        assertEquals(0f, TabPeekRules.scrollTo(labels, 16f, 8f, 344f, 16f, chosen = 0, current = 116f))
        // Trading (ends at 342) needs 342 + 16 − 344 = 14 to clear the edge.
        assertEquals(14f, TabPeekRules.scrollTo(labels, 16f, 8f, 344f, 16f, chosen = 3))
    }

    @Test
    fun aRowThatFitsNeverScrolls() {
        assertEquals(0f, TabPeekRules.scrollTo(labels, 16f, 8f, 900f, 16f, chosen = 4))
        assertEquals(0f, TabPeekRules.scrollTo(labels, 16f, 8f, 344f, 16f, chosen = 9))
    }
}
