package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Messages grouped per chat, slice 1: one Needs you card per conversation, replies and questions on top. */
class TriageChatsTest {
    private fun card(
        id: String, who: String, at: Long, gist: String = "Shared a photo of the pitch",
        lane: TriageLane = TriageLane.FYI, group: String? = null, app: CaptureApp = CaptureApp.WHATSAPP,
        draft: String? = null,
    ): TriageCard {
        val time = "14:" + (at.toInt() % 60).toString().padStart(2, '0')
        val from = if (group != null) "$who in $group · $time" else "$who · $time"
        return TriageCard(id, lane, People.key(who), from, gist, draft, app, group != null, at, "spoken", group)
    }

    @Test
    fun aBusyGroupBecomesOneCardWithItsCountPeopleAndWhatMattersFirst() {
        val group = (1..20).map { i ->
            card("g$i", listOf("Tunde", "Femi", "Obi", "Ada")[i % 4], at = i.toLong(), group = "Barça lads")
        } + card("g21", "Femi", at = 3, group = "Barça lads", gist = "Asks if you're coming Saturday?", lane = TriageLane.NEEDS_REPLY, draft = "Yes")
        val chats = TriageChatRules.chats(group)
        val chat = chats.single()
        assertEquals("Barça lads", chat.title)
        assertTrue(chat.isGroup)
        assertEquals("21 new · 4 people · 1 needs a reply", chat.countLine)
        assertEquals("Femi: Asks if you're coming Saturday?", chat.top.first())
        // Identical gists collapse, so the top lines say different things.
        assertEquals(listOf("Femi: Asks if you're coming Saturday?", "Tunde: Shared a photo of the pitch", "Ada: Shared a photo of the pitch"), chat.top)
        assertEquals(group.sortedBy { it.atMs }.map { it.id }, chat.ids)
        assertEquals("WhatsApp · 14:20", chat.meta)
        assertEquals("Show all 21", TriageChatRules.expandLabel(chat))
        assertEquals("Done · Barça lads · 21 messages", TriageChatRules.doneLine(chat))
        assertFalse(chat.single)
    }

    @Test
    fun chatsAreKeptApartByPersonGroupAndAppAndRepliesLeadTheList() {
        val cards = listOf(
            card("a", "Tunde", at = 10),
            card("b", "Tunde", at = 12, gist = "Running late"),
            card("c", "Tunde", at = 30, group = "Barça lads"),
            card("d", "Tunde", at = 40, app = CaptureApp.SMS),
            card("e", "Ada", at = 5, lane = TriageLane.NEEDS_REPLY, gist = "Asks about dinner", draft = "Yes"),
        )
        val chats = TriageChatRules.chats(cards)
        assertEquals(listOf("Ada", "Tunde", "Barça lads", "Tunde"), chats.map { it.title })
        assertEquals(listOf(1, 1, 1, 2), chats.map { it.cards.size })
        val tunde = chats.single { it.cards.size == 2 }
        assertEquals("2 new", tunde.countLine)
        assertEquals(listOf("Running late", "Shared a photo of the pitch"), tunde.top)
        assertTrue(chats.first().single)
        assertEquals("Messages · 14:40", chats.single { it.app == CaptureApp.SMS }.meta)
        // The badge counts chats with a reply waiting, not messages.
        assertEquals(1, TriageChatRules.replyChats(cards + card("f", "Ada", at = 6, lane = TriageLane.NEEDS_REPLY)))
    }
}
