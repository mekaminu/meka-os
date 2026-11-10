package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The small bugs from Meka's Needs you screenshots (2026-11-10), each pinned here. */
class NeedsYouBugsTest {
    private fun card(
        id: String, who: String, at: Long, gist: String = "Thanks", lane: TriageLane = TriageLane.FYI,
        group: String? = null, number: String? = null,
    ): TriageCard {
        val from = if (group != null) "$who in $group · 14:0${at % 10}" else "$who · 14:0${at % 10}"
        return TriageCard(id, lane, People.key(who), from, gist, null, CaptureApp.WHATSAPP, group != null, at, "spoken", group, number = number)
    }

    @Test
    fun hisOwnMessagesNeverShowAsAChat() {
        val chats = TriageChatRules.chats(listOf(card("me", "You", 5, "You asked whether it's the same tables")))
        assertTrue(chats.isEmpty())
        assertTrue(TriageChatRules.fromMe(card("x", "you", 1)))
        assertTrue(TriageChatRules.fromMe(card("y", "You", 1, group = "Barça lads")))
        assertFalse(TriageChatRules.fromMe(card("z", "Yousef", 1)))
    }

    @Test
    fun aGroupWhoseLastWordIsHisLeavesNeedsYou() {
        val cards = listOf(
            card("a", "Sam", 1, "Asks if the tables are the same?", TriageLane.NEEDS_REPLY, group = "Quiz night"),
            card("b", "You", 3, "Says they are", group = "Quiz night"),
        )
        assertTrue(TriageChatRules.chats(cards).isEmpty())
        assertEquals(0, TriageChatRules.replyChats(cards))
        assertEquals(0, NeedsYouLayoutRules.fyi(TriageChatRules.chats(cards)).size)
    }

    @Test
    fun aGroupThatWentOnAfterHimShowsOnlyWhatCameAfterAndDoneClearsAll() {
        val cards = listOf(
            card("a", "Sam", 1, "Asks about Saturday?", TriageLane.NEEDS_REPLY, group = "Quiz night"),
            card("b", "You", 3, "Says yes", group = "Quiz night"),
            card("c", "Alex", 5, "Thumbs up", group = "Quiz night"),
        )
        val chat = TriageChatRules.chats(cards).single()
        assertEquals(listOf("c"), chat.cards.map { it.id })
        assertEquals(0, chat.replies)
        assertTrue(NeedsYouLayoutRules.isFyi(chat))
        assertEquals(setOf("a", "b", "c"), chat.ids.toSet())
    }

    @Test
    fun aOneToOneReplyIsMatchedByTheChatsNumber() {
        val cards = listOf(
            card("a", "Sam", 1, "Asks if you're free?", TriageLane.NEEDS_REPLY, number = "+447700900123"),
            card("b", "You", 4, "Says yes", number = "+447700900123"),
            card("c", "Alex", 2, "Asks about the match?", TriageLane.NEEDS_REPLY, number = "+447700900999"),
        )
        val chats = TriageChatRules.chats(cards)
        assertEquals(listOf("Alex"), chats.map { it.title })
        assertEquals(1, TriageChatRules.replyChats(cards))
    }

    @Test
    fun namesTypedInLowerCaseGetCapitals() {
        assertEquals("Mato Kano", ChatTitleRules.personName("Mato kano"))
        assertEquals("Mary-Jane Okafor", ChatTitleRules.personName("mary-jane okafor"))
        assertEquals("McDonald", ChatTitleRules.personName("McDonald"))
        assertEquals("iPhone", ChatTitleRules.personName("iPhone"))
        assertEquals("07700 900123", ChatTitleRules.personName("07700 900123"))
        assertEquals("sam@example.com", ChatTitleRules.personName("sam@example.com"))
        val chat = TriageChatRules.chats(listOf(card("a", "Mato kano", 1, "Mato kano: Sent the photos"))).single()
        assertEquals("Mato Kano", chat.title)
        assertEquals("MK", NeedsYouLayoutRules.row(chat).initials)
        assertEquals("Sent the photos", NeedsYouLayoutRules.line(chat))
        val group = TriageChatRules.chats(listOf(card("g", "mato kano", 1, "Sent the photos", group = "Quiz night"))).single()
        assertEquals("Mato Kano: Sent the photos", group.top.single())
    }

    private val now = 1_792_225_800_000L
    private fun mail(id: String, agoMs: Long, extra: Map<String, FieldValue> = emptyMap()) =
        MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", "Sam Taylor", "sam@example.com", "Subject $id", "Hi Alex, card ending 1234", now - agoMs, 1) + extra)!!

    @Test
    fun theMailCountIsTheHeadlinesFigureNotTheRowsShown() {
        val items = (1..14).map { mail("m$it", it * 60_000L) }
        val card = assertNotNull(MailNeedsRules.card(items, now, LocalCalendar.UTC))
        assertEquals("14 emails need you", card.headline)
        assertEquals(MailNeedsRules.SHOWN, card.rows.size)
        assertEquals(14, card.count)
        // The row is sender and subject only: what a screen reader hears leaves the body out too.
        assertFalse("card ending" in card.rows.first().spoken)
        assertTrue("Sam Taylor, Subject m1" in card.rows.first().spoken)
    }
}
