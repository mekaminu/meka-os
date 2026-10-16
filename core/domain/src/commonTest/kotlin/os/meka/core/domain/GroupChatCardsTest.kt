package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Group chats in "While you were at work": one card per group (Meka, 2026-10-15 20:28). Invented names (rule 13). */
class GroupChatCardsTest {
    private fun msg(who: String, text: String?, at: Long, group: String? = null, app: CaptureApp = CaptureApp.WHATSAPP) =
        CapturedItem(Capture.itemId(app, CaptureKind.MESSAGE, who, group, at, text), app, CaptureKind.MESSAGE, who, text, group, at)

    private val group = "Oakfield testing"

    private fun threeSenders() = listOf(
        msg("Alex", "First look is good", 10, group),
        msg("Sam", "Agree", 20, group),
        msg("Jordan Lee", "One crash on login", 30, group),
        msg("Alex", "Some more feedback to follow", 40, group),
    )

    @Test
    fun threeSendersInOneGroupAreOneCard() {
        val s = AfterWorkSummaries.build(threeSenders(), PeopleLists())
        assertEquals(1, s.people.size)
        val card = s.people.single()
        assertTrue(card.isGroup)
        assertEquals(group, card.personName)
        assertEquals("Alex, Jordan Lee and Sam · 4 messages", card.line)
        assertEquals("Alex: Some more feedback to follow", card.latestText)
        assertEquals(listOf("Alex", "Sam", "Jordan Lee", "Alex"), card.shownItems.map { card.itemLabel(it) })
        assertEquals("1 chat · 4 messages", s.headline)
        assertNull(card.blockNumber)
    }

    @Test
    fun oneToOneChatsStayPerPersonAndKeyApartFromTheirGroup() {
        val s = AfterWorkSummaries.build(threeSenders() + msg("Alex", "Are you free later?", 50), PeopleLists())
        assertEquals(listOf("Alex", group), s.people.map { it.personName })
        val alex = s.people[0]
        assertFalse(alex.isGroup)
        assertEquals("1 message", alex.line)
        assertEquals("Are you free later?", alex.latestText)
        assertNull(alex.itemLabel(alex.items.single()))
        assertNotEquals(alex.cardKey, s.people[1].cardKey)
        assertEquals("2 chats · 5 messages", s.headline)
        assertEquals("2 people · 2 messages", AfterWorkSummaries.build(listOf(msg("Alex", "a", 1), msg("Sam", "b", 2)), PeopleLists()).headline)
    }

    @Test
    fun aGroupsBundledTitleIsTheSameGroup() {
        val s = AfterWorkSummaries.build(listOf(msg("Alex", "hi", 1, group), msg("Sam", "yo", 2, "$group (2 messages)")), PeopleLists())
        assertEquals(1, s.people.size)
        assertEquals(group, s.people.single().personName)
    }

    @Test
    fun mentionedYouFoldsIntoItsGroupAndRanksItFirst() {
        val other = listOf(msg("Riley", "Match tonight?", 100, "Five-a-side"))
        // WhatsApp's own "Mentioned you" notice: once with the group, once naming nobody but close in time.
        val withGroup = msg(GroupChatRules.MENTIONED, "@Meka can you look?", 35, group)
        val s = AfterWorkSummaries.build(threeSenders() + other + withGroup, PeopleLists())
        assertEquals(listOf(group, "Five-a-side"), s.people.map { it.personName })
        val card = s.people[0]
        assertTrue(card.mentioned)
        assertEquals("Alex, Jordan Lee and Sam · 4 messages", card.line) // the notice isn't a sender or a message
        assertEquals(4, card.shownItems.size)
        assertEquals(5, card.ids.size) // Done clears the notice too
        assertEquals(5, s.itemCount)

        val loose = msg("Navilife", GroupChatRules.MENTIONED, 42)
        val s2 = AfterWorkSummaries.build(threeSenders() + other + loose, PeopleLists())
        assertEquals(2, s2.people.size)
        assertTrue(s2.people[0].mentioned)
        assertEquals(group, s2.people[0].personName)
        // A notice nowhere near any group's messages has nothing to fold into: no card of its own.
        assertEquals(listOf("Five-a-side"), AfterWorkSummaries.build(other + msg("Navilife", "Mentioned you", 100_000_000), PeopleLists()).people.map { it.personName })
    }

    @Test
    fun mentionNoticesAreRecognised() {
        assertTrue(GroupChatRules.isMentionText("Mentioned you"))
        assertTrue(GroupChatRules.isMentionText("  you were mentioned "))
        assertTrue(GroupChatRules.isMentionText("Mentioned you (2)"))
        assertFalse(GroupChatRules.isMentionText("@Meka mentioned you in the plan"))
        assertFalse(GroupChatRules.isMentionText(null))
    }

    @Test
    fun doneOnSomeMessagesRecountsTheGroup() {
        val s = AfterWorkSummaries.build(threeSenders() + msg(GroupChatRules.MENTIONED, null, 35, group), PeopleLists())
        val card = s.people.single()
        val after = s.without(card.items.filter { it.personName == "Alex" }.map { it.id }.toSet())
        val left = after.people.single()
        assertEquals("Jordan Lee and Sam · 2 messages", left.line)
        assertEquals("Jordan Lee: One crash on login", left.latestText)
        assertTrue(left.mentioned)
        // Only the notice left: the card goes.
        assertTrue(s.without(card.shownItems.map { it.id }.toSet()).isEmpty)
    }

    @Test
    fun aGroupIsNeverShownTwice() {
        val s = AfterWorkSummaries.build(threeSenders() + msg("Alex", "1:1", 5), PeopleLists())
        assertEquals(listOf(People.key(group)), s.groupKeys)
        assertEquals(GroupDigestRules.groupKey(threeSenders().first()), s.groupKeys.single())
    }

    @Test
    fun aFamilyGroupRanksWithFamilyAndHeldPreviewNamesTheSender() {
        val lists = PeopleLists(family = setOf("Alex", "Sam"))
        val fam = listOf(msg("Alex", "Dinner at 7", 10, "Home"), msg("Sam", "OK", 20, "Home"))
        val s = AfterWorkSummaries.build(fam + msg("Riley", "hi", 30), lists)
        assertEquals(listOf("Home", "Riley"), s.people.map { it.personName })
        assertTrue(s.people[0].isFamily)
        val preview = HeldPreviewRules.build(s, "At work", 40, LocalCalendar.UTC)
        assertTrue(preview.rows.any { it.who == "Sam · Home" })
    }
}
