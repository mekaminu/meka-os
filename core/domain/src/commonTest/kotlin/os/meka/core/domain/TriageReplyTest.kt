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
    ) = TriageCard(id, lane, People.key(from.substringBefore(" ")), from, "Asks if you're coming Saturday", draft,
        CaptureApp.WHATSAPP, mentioned, at, "spoken")

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
}
