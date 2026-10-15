package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The messages assistant, slice 3: Needs you's reply cards — what leads, Send all, edited replies, the undo lines. */
class TriageReplyTest {
    private fun card(
        id: String, from: String = "Tunde · 14:02", lane: TriageLane = TriageLane.NEEDS_REPLY,
        draft: String? = "Yes, I'll be there — what time?", mentioned: Boolean = false, at: Long = 1_000L,
        number: String? = null, app: CaptureApp = CaptureApp.WHATSAPP,
    ) = TriageCard(id, lane, People.key(from.substringBefore(" ")), from, "Asks if you're coming Saturday", draft,
        app, mentioned, at, "spoken", group = if (mentioned) "Barça lads" else null, number = number)

    private val rules = TriageReplyRules

    @Test
    fun sendLeadsOnlyWhileTheReplyActionIsLiveAndThereIsADraft() {
        assertEquals(TriageCardPrimary.SEND, rules.primary(card("a"), live = true))
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(card("a"), live = false))
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(card("a", draft = null), live = true))
        assertEquals(TriageCardPrimary.SEEN, rules.primary(card("a", lane = TriageLane.FYI, draft = null), live = true))
        // The Mac never sends: it copies the draft, or marks the card seen when there is none.
        assertEquals(TriageCardPrimary.COPY, rules.primary(card("a"), live = true, canSend = false))
        assertEquals(TriageCardPrimary.SEEN, rules.primary(card("a", draft = null), live = false, canSend = false))
        assertEquals("Send", rules.label(TriageCardPrimary.SEND))
        assertEquals("Not now", rules.secondary(card("a")))
        assertNull(rules.secondary(card("a", lane = TriageLane.FYI)))
    }

    @Test
    fun editIsOfferedOnlyWhereTheReplyCanGoOut() {
        assertTrue(rules.editable(card("a"), live = true))
        assertFalse(rules.editable(card("a"), live = false))
        assertFalse(rules.editable(card("a", draft = null), live = true))
        assertFalse(rules.editable(card("a", lane = TriageLane.FYI), live = true))
    }

    @Test
    fun anEditedReplyIsMekasOwnWordsTrimmedNeverBlank() {
        assertNull(rules.cleanReply("   "))
        assertNull(rules.cleanReply(null))
        assertEquals("Call me on 07700 900123\nafter 6", rules.cleanReply("  Call me on 07700 900123\r\nafter 6 \n"))
        assertEquals(TriageReplyRules.MAX_REPLY, rules.cleanReply("x".repeat(5_000))!!.length)
    }

    @Test
    fun sendAllTakesShortOneToOneRepliesThatAreLiveOldestFirst() {
        val tunde = card("t", "Tunde · 14:02", at = 3_000L)
        val femi = card("f", "Femi · 13:10", draft = "Thanks, see you then", at = 1_000L)
        val obi = card("o", "Obi · 13:40", draft = "Will do", at = 2_000L)
        val group = card("g", "Femi in Barça lads · 13:00", draft = "I'm in", mentioned = true)
        val long = card("l", "Ada · 12:00", draft = "x".repeat(TriageReplyRules.MAX_EASY + 1))
        val twoLines = card("p", "Pete · 12:00", draft = "Yes\nSee you")
        val noDraft = card("n", "Kemi · 12:00", draft = null)
        val fyi = card("i", "Bank · 12:00", lane = TriageLane.FYI, draft = "ok")
        val all = listOf(tunde, femi, obi, group, long, twoLines, noDraft, fyi)
        val live = all.map { it.id }.toSet() - "o"

        val sendAll = assertNotNull(rules.sendAll(all, live))
        assertEquals(listOf("f", "t"), sendAll.ids)
        assertEquals("Send all 2", sendAll.label)
        assertEquals("Send the 2 short replies to Femi and Tunde", sendAll.spoken)

        val three = assertNotNull(rules.sendAll(all, live + "o"))
        assertEquals(listOf("f", "o", "t"), three.ids)
        assertEquals("Send the 3 short replies to Femi, Obi and Tunde", three.spoken)

        // One easy reply has its own Send: no "Send all 1".
        assertNull(rules.sendAll(all, setOf("t")))
        assertNull(rules.sendAll(emptyList(), emptySet()))
    }

    @Test
    fun theUndoBarSaysWhoItWasWithoutTheMessage() {
        val c = card("a")
        val g = card("g", "Femi in Barça lads · 13:00", mentioned = true)
        assertEquals("Sent to Tunde", rules.sentLine(c))
        assertEquals("Sent to Femi in Barça lads", rules.sentLine(g))
        assertEquals("Sent 3 replies", rules.sentAllLine(3))
        assertEquals("Sent 1 reply", rules.sentAllLine(1))
        assertEquals("Not now · Tunde", rules.notNowLine(c))
        assertEquals("Seen · Tunde", rules.seenLine(c))
        assertEquals("Reply copied · paste it in WhatsApp", rules.openChatLine(c, rules.appName(CaptureApp.WHATSAPP)))
        assertEquals("Opening Messages", rules.openChatLine(card("n", draft = null), rules.appName(CaptureApp.SMS)))
        assertEquals("Reply to Tunde copied", rules.copiedLine(c))
    }

    // --- WhatsApp replies that always have a way to send (slice 1) ---

    @Test
    fun replyInWhatsAppLeadsWhenSendCantGoAndTheChatsNumberIsKnown() {
        val numbered = card("a", number = "+447700900123")
        // The live notification still wins on the Fold.
        assertEquals(TriageCardPrimary.SEND, rules.primary(numbered, live = true))
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(numbered, live = false))
        // The Mac never sends, but it can open WhatsApp with the words typed.
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(numbered, live = true, canSend = false))
        assertEquals("Reply in WhatsApp", rules.label(TriageCardPrimary.REPLY_IN_APP))
        // No number, a group, a text message or no draft: as before.
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(card("b"), live = false))
        assertEquals(TriageCardPrimary.COPY, rules.primary(card("b"), live = false, canSend = false))
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(card("g", mentioned = true, number = "+447700900123"), live = false))
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(card("s", number = "+447700900123", app = CaptureApp.SMS), live = false))
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(card("n", draft = null, number = "+447700900123"), live = false))
        // Edit shows where the words go somewhere: sent, or typed into WhatsApp.
        assertTrue(rules.editable(numbered, live = false))
        assertFalse(rules.editable(card("b"), live = false))
    }

    @Test
    fun theLinkIsWhatsAppsOwnClickToChatWithTheEditedWords() {
        val numbered = card("a", number = "+447700900123")
        assertEquals("https://wa.me/447700900123?text=See%20you%20at%206%20%F0%9F%91%8D", rules.replyLink(numbered, " See you at 6 👍 "))
        assertEquals("https://wa.me/447700900123?text=Line%20one%0Aline%20two", rules.replyLink(numbered, "Line one\r\nline two"))
        // A UK number written 07… is made international.
        assertEquals("https://wa.me/447700900123?text=Yes", rules.replyLink(card("u", number = "07700900123"), "Yes"))
        assertNull(rules.replyLink(numbered, "   "))
        assertNull(rules.replyLink(card("b"), "Yes"))
        assertNull(rules.replyLink(card("g", mentioned = true, number = "+447700900123"), "Yes"))
        assertEquals("Opened WhatsApp with your reply to Tunde · tap send there", rules.replyInAppLine(numbered))
    }

    @Test
    fun eachCardSaysWhichWayItsReplyGoes() {
        val numbered = card("a", number = "+447700900123")
        assertEquals("Sends from your phone", rules.routeLine(numbered, rules.primary(numbered, live = true)))
        assertEquals("Opens WhatsApp with your reply typed · you tap send", rules.routeLine(numbered, rules.primary(numbered, live = false)))
        assertEquals("Opens WhatsApp with your reply copied · paste and send", rules.routeLine(card("b"), rules.primary(card("b"), live = false)))
        assertEquals("Copies your reply · paste it in the chat", rules.routeLine(card("b"), rules.primary(card("b"), live = false, canSend = false)))
        assertNull(rules.routeLine(card("i", lane = TriageLane.FYI, draft = null), TriageCardPrimary.SEEN))
        assertNull(rules.routeLine(card("n", draft = null), TriageCardPrimary.OPEN_CHAT))
    }

    @Test
    fun theChatsNumberComesFromWhatTheNotificationCarriedNeverAHiddenIdOrAGroup() {
        assertEquals("+447700900123", rules.chatNumber("Tunde", null, "447700900123@s.whatsapp.net"))
        assertEquals("+447700900123", rules.chatNumber("Tunde", "tel:+44 7700 900123", null))
        assertEquals("07700900123", rules.chatNumber("07700 900123", null, null))
        assertEquals("+447700900123", rules.chatNumber("+44 7700 900123", null, "120363000000000000@g.us"))
        // A group's id, WhatsApp's hidden "@lid" ids, a name, a short code: none is a number.
        assertNull(rules.chatNumber("Tunde", null, "120363000000000000@g.us"))
        assertNull(rules.chatNumber("Tunde", null, "123456789012345@lid"))
        assertNull(rules.chatNumber("Tunde", "mailto:t@example.com", null))
        assertNull(rules.chatNumber("Tunde", null, null))
        assertNull(rules.chatNumber("88088", null, null))
        assertNull(rules.cleanNumber("12"))
        assertEquals("+447700900123", rules.cleanNumber(" +44 (0)7700-900123 ".replace("(0)", "")))
    }
}
