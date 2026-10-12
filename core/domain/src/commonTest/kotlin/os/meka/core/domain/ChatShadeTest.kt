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
}
