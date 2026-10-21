package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board by hand, slice 5: the colour dot for where a card came from. */
class BoardSourceTest {
    private fun task(id: String, source: String? = "user") =
        Task(id, "Title", null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false, source = source)

    @Test
    fun eachSourceIsKnownFromWhatTheTaskCarries() {
        assertEquals(CardSource.MAIL, BoardSourceRules.of(task(MailNeedsRules.taskId("mi0123"))))
        assertEquals(CardSource.MAIL, BoardSourceRules.of(task("0f3a", source = BoardSourceRules.MAIL)))
        assertEquals(CardSource.DISCORD, BoardSourceRules.of(task("0f3a", source = "discord")))
        assertEquals(CardSource.NEEDS, BoardSourceRules.of(task("0f3a", source = BoardSourceRules.NEEDS)))
        assertEquals(CardSource.SCHOOL, BoardSourceRules.of(task(SchoolMailRules.taskId("find"))))
        assertEquals(CardSource.SCHOOL, BoardSourceRules.of(task(BoardFeedRules.dateTaskId("s", "trip", 20_400))))
    }

    @Test
    fun whatHeTypedAndGiftsHaveNoDot() {
        // Random ids are lowercase hex, so never start with a source's letters.
        assertNull(BoardSourceRules.of(task("5d2c0b9e1a7f44e0b3c2d1e0f9a8b7c6")))
        assertNull(BoardSourceRules.of(task("abc", source = null)))
        assertNull(BoardSourceRules.of(task(BoardFeedRules.dateTaskId("g", "mum", 20_400))))
    }

    @Test
    fun theDotsAreDistinctAndNeverAmberOrGold() {
        val tones = CardSource.entries.map { it.tone }
        assertEquals(tones.size, tones.toSet().size)
        // 3 is the amber of "carried"; 0 is Barça's fixture colour.
        assertTrue(tones.none { it == 3 || it == CalendarTones.FIXTURE })
    }

    @Test
    fun aScreenReaderHearsWhereItCameFrom() {
        val card = BoardCard(task(MailNeedsRules.taskId("mi1")), "Reply to the builder", null, null, done = false, carried = false)
        assertEquals(CardSource.MAIL, card.source)
        assertEquals("Reply to the builder, from your mail", card.accessibilityLabel)
        assertNull(BoardCard(task("ab12"), "Mine", null, null, false, false).source)
    }
}
