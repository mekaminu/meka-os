package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Taps feel instant, slice 20: the mail cards' answers take the row away at once, and a refusal brings it back. */
class InstantMailTest {
    private fun need(id: String) = MailNeedsRow(id, "Alex", "Personal · 09:12", "Lunch?", null, false, "spoken $id")
    private fun chase(id: String) = MailChaseRow(id, "w-$id", "Sam", "The quote", "Asked 5 days ago", false, "Hi Sam", "", null, "spoken")
    private fun find(id: String) = MailFindRow(id, "Streaming", "Renews Tue 3 Nov", "Streaming Co", false, "spoken")
    private fun line(id: String) = ArchivedForYouLine(id, "12:40", "Personal", "3 newsletters", true, null)

    private val needs = MailNeedsCard(MailNeedsRules.TITLE, "3 emails need you", listOf(need("a"), need("b"), need("c")), null, null)
    private val chases = MailChaseCard(MailChaseRules.TITLE, "2 to chase by email", listOf(chase("x"), chase("y")), null)
    private val finds = MailFindCard("Renewals from your mail", "1 to add", listOf(find("f")), null)
    private val archived = ArchivedForYouCard(ArchivedForYouRules.TITLE, "Lunchtime digest", "Tidied 3", "note", listOf(line("t1"), line("t2")), null, "spoken")

    @Test
    fun anAnsweredRowLeavesAtOnceAndTheCardGoesWithItsLastRow() {
        var hidden = InstantTapRules.hide(emptySet(), listOf("b"))
        assertEquals(listOf("a", "c"), InstantMailRules.needs(needs, hidden)?.rows?.map { it.id })
        hidden = InstantTapRules.hide(hidden, listOf("a", "c"))
        assertNull(InstantMailRules.needs(needs, hidden))
        // Nothing decided: the core's card is drawn as it is (the same object, no copy each frame).
        assertTrue(needs === InstantMailRules.needs(needs, emptySet()))
        assertTrue(chases === InstantMailRules.chases(chases, setOf("b")))
        assertEquals(listOf("y"), InstantMailRules.chases(chases, setOf("x"))?.rows?.map { it.id })
        assertNull(InstantMailRules.finds(finds, setOf("f")))
        assertNull(InstantMailRules.needs(null, setOf("a")))
    }

    @Test
    fun gotItOnArchivedHidesThatDigestButANewerOneShows() {
        val key = InstantMailRules.archivedKey(archived)
        assertNull(InstantMailRules.archived(archived, setOf(key)))
        // A new tidy arrived before the write landed: the card holds more, so it shows.
        val newer = archived.copy(lines = listOf(line("t3")) + archived.lines)
        assertNotNull(InstantMailRules.archived(newer, setOf(key)))
        // The evening digest is a different card too.
        assertNotNull(InstantMailRules.archived(archived.copy(slot = "Evening digest"), setOf(key)))
    }

    @Test
    fun theSetKeepsOnlyWhatTheCardsStillHold() {
        val key = InstantMailRules.archivedKey(archived)
        val hidden = InstantTapRules.hide(emptySet(), listOf("a", "x", "f", key))
        val present = InstantMailRules.present(needs, chases, finds, archived)
        assertEquals(hidden, InstantTapRules.settle(hidden, present))
        // The core's cards caught up: From your mail and the archived card no longer hold them; the rest still wait.
        val after = InstantMailRules.present(needs.copy(rows = needs.rows.drop(1)), chases, finds, null)
        assertEquals(setOf("x", "f"), InstantTapRules.settle(hidden, after))
        assertEquals(emptyList(), InstantMailRules.present(null, null, null, null))
    }

    @Test
    fun aRefusalIsAFailure() {
        assertTrue(InstantMailRules.refused(MailNeedsRules.UNKNOWN))
        assertTrue(InstantMailRules.refused(MailChaseRules.UNKNOWN))
        assertTrue(InstantMailRules.refused(MailRenewalRules.UNKNOWN))
        assertTrue(InstantMailRules.refused(null))
        assertFalse(InstantMailRules.refused(MailChaseRules.GOT_LINE))
        assertFalse(InstantMailRules.refused(ArchivedForYouRules.GOT_IT_LINE))
    }
}
