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
}
