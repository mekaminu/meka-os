package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Galaxy Watch: approve message drafts from the wrist, slice 1 — the newest drafted WhatsApp or text reply on the
 * watch, Send only while the Fold holds the message, through the Fold's own Reply (Send (via your phone)).
 */
class WatchMessageDraftTest {
    private val world = SyncWorld()
    private val a = world.device("android")
    private val w = world.device("watch")
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 18)
    private fun at(minute: Int) = cal.toEpochMs(day, minute)
    private val fold = TriageCards(a.replica, { world.clock.nowMs }, cal)
    private val watch = TriageCards(w.replica, { world.clock.nowMs }, cal)
    private val R = WatchMessageDraftRules

    init { world.clock.nowMs = at(14 * 60 + 5) }

    private fun msg(id: String, who: String, minute: Int) = CapturedItem(
        id, CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, "coming Saturday?", null, at(minute), chatNumber = "+447700900123",
    )
    private fun reply(draft: String? = "Yes, see you there") =
        MessageTriage(TriageLane.NEEDS_REPLY, draft = draft, summary = "Asks if you're coming Saturday")

    private fun syncAll() { a.sync(); w.sync(); a.sync() }

    @Test
    fun theNewestDraftShowsWithSendWhileThePhoneHoldsTheMessage() {
        fold.save(msg("wa-1", "Alex", 14 * 60), reply())
        fold.save(msg("wa-2", "Sam", 14 * 60 + 2), reply("Running ten minutes late"))
        fold.save(msg("wa-3", "Jo", 14 * 60 + 3), reply(draft = null))
        fold.markPhoneLive(setOf("wa-1", "wa-2"))
        syncAll()
        val v = assertNotNull(R.view(watch.open()))
        assertEquals("wa-2", v.messageId)
        assertEquals("MESSAGE REPLY · 1 OF 2", v.label)
        assertTrue(v.from.startsWith("Sam"))
        assertTrue(v.from.endsWith("· WhatsApp"))
        assertEquals("Asks if you're coming Saturday", v.wants)
        assertEquals("Running ten minutes late", v.draft)
        assertTrue(v.canSend)
        assertEquals(R.NOTE, v.note)
        assertFalse(v.warn)
        assertEquals("+1 more on your phone", v.more)
        assertTrue(v.spoken.contains("only when you tap Send"))
    }

    @Test
    fun withoutThePhonesNotificationTheDraftIsKeptAndNeedsThePhone() {
        fold.save(msg("wa-1", "Alex", 14 * 60), reply())
        syncAll()
        val v = assertNotNull(R.view(watch.open()))
        assertFalse(v.canSend)
        assertEquals(R.NEEDS_PHONE, v.note)
        assertNull(R.sendable(watch.open(), "wa-1"))
        assertNull(R.view(emptyList()))
    }

    @Test
    fun aLongDraftIsCutAndNeverSentFromTheWrist() {
        fold.save(msg("wa-1", "Alex", 14 * 60), reply("word ".repeat(120).trim()))
        fold.markPhoneLive(setOf("wa-1"))
        syncAll()
        val v = assertNotNull(R.view(watch.open()))
        assertFalse(v.canSend)
        assertEquals(R.TOO_LONG, v.note)
        assertTrue(v.draft.endsWith("…"))
        assertTrue(v.draft.length <= R.MAX_CHARS)
        assertNull(R.sendable(watch.open(), "wa-1"))
    }

    @Test
    fun sendAsksThePhoneWhichSendsTheWordsAsShownAndTheCardLeavesTheWrist() {
        fold.save(msg("wa-1", "Alex", 14 * 60), reply())
        fold.markPhoneLive(setOf("wa-1"))
        syncAll()
        val words = assertNotNull(R.sendable(watch.open(), "wa-1"))
        assertTrue(watch.requestPhoneSend("wa-1", words))
        val waiting = assertNotNull(R.view(watch.open()))
        assertFalse(waiting.canSend, "one request at a time")
        assertEquals(R.SENDING, waiting.note)
        assertNull(R.sendable(watch.open(), "wa-1"))
        syncAll()
        val due = fold.phoneSendsDue().single()
        assertEquals("Yes, see you there", due.text)
        assertTrue(fold.phoneSendDone(due.messageId, due.atMs, PhoneSendOutcome.SENT))
        syncAll()
        assertNull(R.view(watch.open()))
    }

    @Test
    fun aMessageOpenedOnThePhoneOrAnUnansweredAskSaysSoAndSendsNothing() {
        fold.save(msg("wa-1", "Alex", 14 * 60), reply())
        fold.markPhoneLive(setOf("wa-1"))
        syncAll()
        assertTrue(watch.requestPhoneSend("wa-1", R.sendable(watch.open(), "wa-1")!!))
        syncAll()
        val due = fold.phoneSendsDue().single()
        assertTrue(fold.phoneSendDone(due.messageId, due.atMs, PhoneSendOutcome.GONE))
        syncAll()
        val gone = assertNotNull(R.view(watch.open()))
        assertEquals(R.GONE, gone.note)
        assertTrue(gone.warn)
        assertFalse(gone.canSend)

        // A second message: the phone never picks the ask up in five minutes.
        fold.save(msg("wa-2", "Sam", 14 * 60 + 4), reply("On my way"))
        fold.markPhoneLive(setOf("wa-2"))
        syncAll()
        assertTrue(watch.requestPhoneSend("wa-2", R.sendable(watch.open(), "wa-2")!!))
        world.clock.nowMs += TriageCards.PHONE_SEND_TTL_MS + 1
        val late = assertNotNull(R.view(watch.open()))
        assertEquals("wa-2", late.messageId)
        assertEquals(R.LATE, late.note)
        assertFalse(late.canSend)
        assertNull(R.sendable(watch.open(), "wa-2"))
        a.sync()
        assertTrue(fold.phoneSendsDue().isEmpty(), "never sent late")
    }
}
