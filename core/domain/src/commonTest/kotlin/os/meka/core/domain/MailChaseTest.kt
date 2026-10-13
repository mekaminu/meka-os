package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6b-3: Waiting for's chase, drafted when it's due, sent only on his tap. */
class MailChaseTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 08:30 UTC
    private val day = 86_400_000L
    private val today = cal.epochDayOf(now)

    private fun item(id: String, from: String = "Sam Taylor", extra: Map<String, FieldValue> = emptyMap()) =
        MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", from, null, "Quote", "Hi", now - 5 * day, 1) +
            mapOf(MailItemFields.WAITING_ID to "w$id".let { FieldValue.Text(it) }) + extra)!!

    private fun waiting(id: String, chaseDay: Long?, sinceAgo: Long = 5 * day, lastChased: Long? = null) = WaitingItem(
        id = id, title = "The quote", who = "Sam Taylor", notes = null, sinceMs = now - sinceAgo, lastChasedMs = lastChased,
        chaseDay = chaseDay, state = ListRules.dueState(chaseDay, today), meta = "", hasConflict = false,
    )

    @Test
    fun theDraftGreetsAPersonByFirstNameAndSignsWithHisName() {
        assertEquals("Sam", MailChaseRules.greetName("Sam Taylor"))
        assertEquals("Mary-Jo", MailChaseRules.greetName("Mary-Jo O'Neil"))
        assertNull(MailChaseRules.greetName("Octopus"))
        assertNull(MailChaseRules.greetName("HMRC Self Assessment"))
        assertNull(MailChaseRules.greetName("shop.example"))
        assertNull(MailChaseRules.greetName("Order 12345"))
        assertNull(MailChaseRules.greetName("The Big Kitchen Company Ltd"))
        assertEquals("Hi Sam,\n\nJust checking in on this — is there any news?\n\nThanks,\nAlex", MailChaseRules.draft("Sam Taylor", " Alex "))
        assertEquals("Hi,\n\nJust checking in on this — is there any news?\n\nThanks", MailChaseRules.draft("shop.example", null))
    }

    @Test
    fun onlyDueOpenWaitingForItemsFromEmailShowWaitingLongestFirst() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val card = assertNotNull(MailChaseRules.card(
            listOf(a, b, c, item("x").copy(waitingId = null)),
            listOf(waiting("wa", today, 3 * day), waiting("wb", today - 2, 8 * day, lastChased = now - 4 * day), waiting("wc", today + 1)),
            null, now, cal,
        ))
        assertEquals(listOf("b", "a"), card.rows.map { it.id })
        assertEquals("Personal · waiting since Fri 9 Oct · last chased Tue 13 Oct · chase was due Thu 15 Oct", card.rows[0].line)
        assertEquals("To Sam Taylor · in the same thread, from Personal, only when you tap Send", card.rows[0].replyLine)
        assertEquals("2 to chase by email", card.headline)
        // Got it (no longer open) or no Waiting for left: nothing.
        assertNull(MailChaseRules.card(listOf(a), emptyList(), null, now, cal))
    }

    @Test
    fun sendWaitsOutTheHoldUndoStopsItAndAFailureComesBackOnce() {
        val sent = item("a", extra = MailChaseRules.sendFields(" Any news? ", now))
        assertEquals("Any news?", sent.chaseText)
        assertTrue(MailChaseRules.waiting(sent))
        assertFalse(MailChaseRules.due(sent, now + 1_000))
        assertTrue(MailChaseRules.due(sent, now + MailNeedsRules.REPLY_HOLD_MS))
        val undone = item("a", extra = MailChaseRules.sendFields("Any news?", now) + MailChaseRules.undoFields(now + 1_000))
        assertFalse(MailChaseRules.due(undone, now + MailNeedsRules.REPLY_HOLD_MS * 2))
        val done = item("a", extra = MailChaseRules.sendFields("Any news?", now) + MailChaseRules.sentFields(now + 30_000))
        assertTrue(MailChaseRules.sent(done))
        assertFalse(MailChaseRules.due(done, now + day))
        // A chase on its way doesn't show even on the due day.
        assertNull(MailChaseRules.card(listOf(sent), listOf(waiting("wa", today)), null, now, cal))
        // Failed: back with his words and why, even though the chase date has moved on; Not yet puts it aside.
        val failed = item("a", extra = MailChaseRules.sendFields("Any news?", now) + MailChaseRules.failedFields(now + 30_000, "Gmail didn't take it"))
        val row = assertNotNull(MailChaseRules.card(listOf(failed), listOf(waiting("wa", today + 3)), null, now + day, cal)).rows.single()
        assertEquals("Any news?", row.draft)
        assertEquals("Chase didn't send · Gmail didn't take it · your words are kept", row.notice)
        assertFalse(MailChaseRules.due(failed, now + day))
        val seen = failed.copy(chaseSeenAtMs = now + 60_000)
        assertNull(MailChaseRules.card(listOf(seen), listOf(waiting("wa", today + 3)), null, now + day, cal))
    }
}
