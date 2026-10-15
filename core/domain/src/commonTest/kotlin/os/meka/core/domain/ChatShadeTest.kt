package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Messages grouped per chat, slice 2: Mark read in WhatsApp and clearing the chat's notifications once finished. */
class ChatShadeTest {
    private fun card(id: String, who: String, at: Long, group: String? = "Barça lads", app: CaptureApp = CaptureApp.WHATSAPP) =
        TriageCard(id, TriageLane.FYI, People.key(who), "$who · 14:0$at", "Shared a photo", null, app, group != null, at, "spoken", group)

    private val chat = TriageChatRules.chats(listOf(card("a", "Tunde", 1), card("b", "Femi", 2))).single()

    @Test
    fun theButtonMarksReadWhileANotificationOffersItElseOpensTheApp() {
        assertTrue(ChatShadeRules.markable(chat, setOf("b")))
        assertFalse(ChatShadeRules.markable(chat, setOf("zz")))
        assertEquals("Mark read in WhatsApp" to ChatFinishTap.MARK_READ, ChatShadeRules.markButton(CaptureApp.WHATSAPP, true))
        assertEquals("Open WhatsApp to mark read" to ChatFinishTap.OPEN_TO_MARK, ChatShadeRules.markButton(CaptureApp.WHATSAPP, false))
        assertEquals("Mark read in Messages", ChatShadeRules.markReadLabel(CaptureApp.SMS))
    }

    @Test
    fun doneMarksReadOnlyWithTheSettingAndAlwaysClearsTheShade() {
        // Off by default: Done never marks read (no blue ticks), only clears the chat's notifications.
        assertFalse(TriageSettings().markReadOnDone)
        assertEquals(ChatFinish(markRead = false, clear = true, openChat = false), ChatShadeRules.finish(ChatFinishTap.DONE, markable = true, markReadOnDone = false))
        assertEquals(ChatFinish(markRead = true, clear = true, openChat = false), ChatShadeRules.finish(ChatFinishTap.DONE, markable = true, markReadOnDone = true))
        // The notification went: nothing to fire, still cleared (nothing left to clear is harmless).
        assertEquals(ChatFinish(markRead = false, clear = true, openChat = false), ChatShadeRules.finish(ChatFinishTap.DONE, markable = false, markReadOnDone = true))
    }

    @Test
    fun markReadFiresThenClearsOrOpensTheChatWhenItCant() {
        val marked = ChatShadeRules.finish(ChatFinishTap.MARK_READ, markable = true, markReadOnDone = false)
        assertEquals(ChatFinish(markRead = true, clear = true, openChat = false), marked)
        assertEquals("Marked read in WhatsApp · Barça lads", ChatShadeRules.line(chat, marked, marked = true))
        val gone = ChatShadeRules.finish(ChatFinishTap.MARK_READ, markable = false, markReadOnDone = false)
        assertEquals(ChatFinish(markRead = false, clear = false, openChat = true), gone)
        assertEquals("Opened WhatsApp · mark Barça lads read there", ChatShadeRules.line(chat, gone, marked = false))
        assertEquals(gone, ChatShadeRules.finish(ChatFinishTap.OPEN_TO_MARK, markable = true, markReadOnDone = true))
        val done = ChatShadeRules.finish(ChatFinishTap.DONE, markable = false, markReadOnDone = false)
        assertEquals("Done · Barça lads · 2 messages", ChatShadeRules.line(chat, done, marked = false))
    }

    private fun item(id: String, who: String, group: String? = "Barça lads", text: String = "Anyone for five-a-side?") =
        CapturedItem(id, CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, 1_000L)

    @Test
    fun aNoisyChatLeavesTheShadeOnceMekaHasEachMessageButNeverFamilyOrUrgent() {
        val lists = PeopleLists(family = setOf("Jordan"), alwaysNotify = setOf("Mum"))
        // The item's key matches the card's, so the switch on the chat's card reaches what the listener sees.
        assertEquals(chat.key, ChatShadeRules.key(item("x", "Tunde")))
        val on = TriageSettings(clearOnArrival = ChatShadeRules.setClearOnArrival(emptyMap(), chat, on = true))
        assertEquals("Barça lads", ChatShadeRules.arrivalSummary(on))
        assertEquals(null, ChatShadeRules.arrivalSummary(TriageSettings()))
        val chatter = listOf(item("1", "Tunde"), item("2", "Femi"))
        assertTrue(ChatShadeRules.clearsOnArrival(chatter, on, lists, had = setOf("1", "2")))
        // Off by default; a message MEKA hasn't got yet (AI offline) stays for WhatsApp's re-post.
        assertFalse(ChatShadeRules.clearsOnArrival(chatter, TriageSettings(), lists, had = setOf("1", "2")))
        assertFalse(ChatShadeRules.clearsOnArrival(chatter, on, lists, had = setOf("1")))
        // Jordan, Mum or an urgent message in the same notification: it stays.
        assertFalse(ChatShadeRules.clearsOnArrival(chatter + item("3", "Jordan"), on, lists, had = setOf("1", "2", "3")))
        assertFalse(ChatShadeRules.clearsOnArrival(listOf(item("4", "Mum")), on, lists, had = setOf("4")))
        assertFalse(ChatShadeRules.clearsOnArrival(listOf(item("5", "Tunde", text = "URGENT call me")), on, lists, had = setOf("5")))
        // Another chat's message isn't touched.
        assertFalse(ChatShadeRules.clearsOnArrival(listOf(item("6", "Tunde", group = "Work")), on, lists, had = setOf("6")))
        // WhatsApp replies, slice 1: a message whose reply card is waiting keeps its notification (Send's route).
        assertFalse(ChatShadeRules.clearsOnArrival(chatter, on, lists, had = setOf("1", "2"), pending = setOf("2")))
        assertTrue(ChatShadeRules.clearsOnArrival(chatter, on, lists, had = setOf("1", "2"), pending = setOf("9")))
        // Switching off forgets it.
        assertTrue(ChatShadeRules.setClearOnArrival(on.clearOnArrival, chat, on = false).isEmpty())
    }

    @Test
    fun onlyOpenRepliesWithADraftHoldTheirNotification() {
        val reply = card("r", "Tunde", 3, group = null).copy(lane = TriageLane.NEEDS_REPLY, draft = "Yes")
        val noDraft = card("n", "Femi", 4, group = null).copy(lane = TriageLane.NEEDS_REPLY)
        assertEquals(setOf("r"), ChatShadeRules.pendingReplies(listOf(reply, noDraft, card("a", "Obi", 1))))
    }

    @Test
    fun theSwitchIsNeverOfferedForJordanOrAlwaysNotifyOneToOne() {
        val lists = PeopleLists(family = setOf("Jordan"), alwaysNotify = setOf("Mum"))
        val jordan = TriageChatRules.chats(listOf(card("j1", "Jordan", 1, group = null), card("j2", "Jordan", 2, group = null))).single()
        val tunde = TriageChatRules.chats(listOf(card("t1", "Tunde", 1, group = null), card("t2", "Tunde", 2, group = null))).single()
        assertFalse(ChatShadeRules.canClearOnArrival(jordan, lists))
        assertTrue(ChatShadeRules.canClearOnArrival(tunde, lists))
        assertTrue(ChatShadeRules.canClearOnArrival(chat, lists))
    }

    @Test
    fun aOneMessageCardMarksReadAndClearsAndReadingToTheEndClearsOnlyWithoutAReplyWaiting() {
        assertEquals("Mark read in WhatsApp", ChatShadeRules.singleMarkButton(CaptureApp.WHATSAPP, true))
        assertEquals(null, ChatShadeRules.singleMarkButton(CaptureApp.WHATSAPP, false))
        assertTrue(ChatShadeRules.clearsAfter(CardFinish.SENT))
        assertTrue(ChatShadeRules.clearsAfter(CardFinish.SEEN))
        assertFalse(ChatShadeRules.clearsAfter(CardFinish.NOT_NOW))
        assertFalse(ChatShadeRules.clearsAfter(CardFinish.OPENED))
        assertTrue(ChatShadeRules.readToEndClears(chat))
        val asks = TriageChatRules.chats(listOf(card("a", "Tunde", 1), card("b", "Femi", 2).copy(lane = TriageLane.NEEDS_REPLY))).single()
        assertFalse(ChatShadeRules.readToEndClears(asks))
    }
}
