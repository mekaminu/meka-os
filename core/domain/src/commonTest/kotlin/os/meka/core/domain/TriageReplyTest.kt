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

    // WhatsApp Open chat lands in the chat (2026-10-29): a 1:1 chat's number found on the phone, never a guess.

    @Test
    fun aOneToOneChatsNumberComesFromTheFamilyListFirst() {
        val lists = PeopleLists(family = setOf("Sam"), numbers = mapOf("Sam" to setOf("07700 900123")))
        val contacts = listOf(ContactPhone("9", "Sam", "+44 7700 900999", mobile = true))
        assertEquals("+447700900123", rules.lookupNumber("sam", lists, contacts))
        // Always-notify next, when the name isn't on Family.
        val always = PeopleLists(alwaysNotify = setOf("Jordan"), numbers = mapOf("Jordan" to setOf("+447700900456")))
        assertEquals("+447700900456", rules.lookupNumber("Jordan", always, emptyList()))
    }

    @Test
    fun elseAContactOfExactlyThatName() {
        val one = listOf(ContactPhone("1", "Alex Smith", "07700 900321"))
        assertEquals("+447700900321", rules.lookupNumber("Alex Smith", PeopleLists(), one))
        // Not a near name.
        assertNull(rules.lookupNumber("Alex", PeopleLists(), one))
        // A contact with a home and a mobile: the mobile; two mobiles: no link.
        val two = listOf(ContactPhone("1", "Alex Smith", "01632 960001"), ContactPhone("1", "Alex Smith", "07700 900321", mobile = true))
        assertEquals("+447700900321", rules.lookupNumber("Alex Smith", PeopleLists(), two))
        val mobiles = listOf(ContactPhone("1", "Alex Smith", "07700 900111", true), ContactPhone("1", "Alex Smith", "07700 900222", true))
        assertNull(rules.lookupNumber("Alex Smith", PeopleLists(), mobiles))
        // The same number written twice is one number.
        val dup = listOf(ContactPhone("1", "Alex Smith", "07700 900321"), ContactPhone("1", "Alex Smith", "+447700900321"))
        assertEquals("+447700900321", rules.lookupNumber("Alex Smith", PeopleLists(), dup))
    }

    @Test
    fun twoPeopleOfTheSameNameOrNoneGiveNoLink() {
        val twins = listOf(ContactPhone("1", "Sam", "07700 900111"), ContactPhone("2", "Sam", "07700 900222"))
        assertNull(rules.lookupNumber("Sam", PeopleLists(), twins))
        assertNull(rules.lookupNumber("Sam", PeopleLists(), emptyList()))
        assertNull(rules.lookupNumber("  ", PeopleLists(), twins))
        // A Family name with two numbers falls through to the contacts, which settle it.
        val lists = PeopleLists(family = setOf("Sam"), numbers = mapOf("Sam" to setOf("07700 900111", "07700 900222")))
        assertNull(rules.lookupNumber("Sam", lists, twins))
        assertEquals("+447700900111", rules.lookupNumber("Sam", lists, twins.take(1)))
    }

    @Test
    fun aFoundNumberTurnsOpenChatIntoReplyInWhatsApp() {
        val c = card("a", from = "Sam · 03:14")
        assertTrue(rules.needsLookup(c))
        assertFalse(rules.needsLookup(card("b", mentioned = true)))
        assertFalse(rules.needsLookup(card("c", number = "+447700900123")))
        assertFalse(rules.needsLookup(card("d", app = CaptureApp.SMS)))
        assertEquals(TriageCardPrimary.OPEN_CHAT, rules.primary(c, live = false))
        val filled = rules.withNumbers(listOf(c, card("e")), mapOf("a" to "+447700900123"))
        assertEquals("+447700900123", filled[0].number)
        assertNull(filled[1].number)
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(filled[0], live = false))
        assertTrue(rules.replyLink(filled[0], filled[0].draft)!!.startsWith("https://wa.me/447700900123?text=Yes"))
        assertEquals("https://wa.me/447700900123", rules.chatLink(filled[0]))
        assertNull(rules.chatLink(c))
        // A number the card already had is never replaced.
        val had = card("f", number = "+447700900999")
        assertEquals("+447700900999", rules.withNumbers(listOf(had), mapOf("f" to "+447700900123"))[0].number)
    }

    @Test
    fun aGroupWhoseNotificationWentSaysOpenWhatsAppAndNamesTheGroup() {
        val g = card("g", mentioned = true)
        val p = rules.primary(g, live = false)
        assertEquals(TriageCardPrimary.OPEN_CHAT, p)
        assertEquals("Open WhatsApp", rules.openLabel(g, p, live = false))
        assertEquals("Find Barça lads in WhatsApp · your reply is copied", rules.openRoute(g, p, live = false))
        assertEquals("Find Barça lads in WhatsApp", rules.openRoute(g.copy(draft = null), rules.primary(g.copy(draft = null), false), live = false))
        // While it shows, its tap target opens the group: the usual Open chat.
        assertEquals("Open chat", rules.openLabel(g.copy(draft = null), rules.primary(g.copy(draft = null), live = true), live = true))
        assertEquals(rules.routeLine(card("a"), TriageCardPrimary.OPEN_CHAT), rules.openRoute(card("a"), TriageCardPrimary.OPEN_CHAT, live = false))
    }
}
