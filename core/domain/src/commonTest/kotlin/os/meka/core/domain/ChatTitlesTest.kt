package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fold review 2026-10-12, bug 1: one group, one card, however WhatsApp titles its notification. */
class ChatTitlesTest {
    private val day = 1_000_000_000L
    private fun at(h: Int, m: Int) = day + (h * 60 + m) * 60_000L
    private fun msg(who: String, text: String, group: String, atMs: Long) =
        CapturedItem("$who-$text-$group-$atMs", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, atMs)

    @Test
    fun theAppsCountComesOffTheName() {
        assertEquals("WTFC U7s", ChatTitleRules.clean("WTFC U7s (2 messages)"))
        assertEquals("WTFC U7s", ChatTitleRules.clean("  WTFC   U7s (1 message) "))
        assertEquals("WTFC U7s", ChatTitleRules.clean("WTFC U7s (3 new messages)"))
        assertEquals("WTFC U7s", ChatTitleRules.clean("WTFC U7s: 12 New Messages"))
        assertEquals("WTFC U7s", ChatTitleRules.clean("WTFC U7s · 2 new messages"))
        assertEquals("WTFC U7s", ChatTitleRules.clean("WTFC U7s - 4 messages"))
        // Names that only look a little like a count stay as they are.
        assertEquals("Class of 2015 (Year 6)", ChatTitleRules.clean("Class of 2015 (Year 6)"))
        assertEquals("Top 5 messages", ChatTitleRules.clean("Top 5 messages"))
        assertEquals("(2 messages)", ChatTitleRules.clean("(2 messages)"))
        assertEquals("Barça lads", ChatTitleRules.clean("Barça lads"))
        assertNull(ChatTitleRules.cleanOrNull("   "))
        assertNull(ChatTitleRules.cleanOrNull(null))
    }

    @Test
    fun theDigestShowsOneCardForTheGroupWithEachMessageOnce() {
        // The first message arrives alone, then WhatsApp re-posts it bundled with the second under "(2 messages)".
        val first = at(9, 0)
        val items = listOf(
            msg("Coach Dan", "Training moved to 5", "WTFC U7s", first),
            msg("Coach Dan", "Training moved to 5", "WTFC U7s (2 messages)", first),
            msg("Amy", "Is there no match this Saturday?", "WTFC U7s (2 messages)", at(9, 5)),
        )
        val view = GroupDigestRules.view(items, TriageSettings(), emptyMap(), day, 12 * 60 + 30)!!
        val card = view.cards.single()
        assertEquals("WTFC U7s", card.title)
        assertEquals(2, card.count)
        assertEquals("1 group · 2 messages", view.line)
    }

    @Test
    fun aChatsRepeatedCardIsHiddenButClearedWithIt() {
        fun card(id: String, who: String, at: Long, group: String, lane: TriageLane = TriageLane.FYI) =
            TriageCard(id, lane, People.key(who), "$who in $group · 14:00", "Asks about Saturday", null, CaptureApp.WHATSAPP, true, at, "spoken", group)
        val chats = TriageChatRules.chats(
            listOf(
                card("a", "Amy", 10, "WTFC U7s"),
                card("b", "Amy", 10, "WTFC U7s (2 messages)", TriageLane.NEEDS_REPLY),
                card("c", "Dan", 12, "WTFC U7s (2 messages)"),
            ),
        )
        val chat = chats.single()
        assertEquals("WTFC U7s", chat.title)
        assertEquals(listOf("b", "c"), chat.cards.map { it.id })
        assertEquals(listOf("a"), chat.repeatIds)
        assertEquals(listOf("b", "c", "a"), chat.ids)
        assertEquals("2 new · 2 people · 1 needs a reply", chat.countLine)
    }
}
