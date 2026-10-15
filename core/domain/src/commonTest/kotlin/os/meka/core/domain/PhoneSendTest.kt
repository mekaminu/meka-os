package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WhatsApp replies that always have a way to send, slice 2: Send (via your phone) on the Mac asks the Fold, through the
 * synced card, to send the reply with the notification's own Reply; the Fold answers Sent or why not.
 */
class PhoneSendTest {
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 15)
    private fun at(minute: Int) = cal.toEpochMs(day, minute)
    private val fold = TriageCards(a.replica, { world.clock.nowMs }, cal)
    private val mac = TriageCards(m.replica, { world.clock.nowMs }, cal)
    private val rules = TriageReplyRules

    init { world.clock.nowMs = at(14 * 60 + 3) }

    private val alex = CapturedItem(
        "wa-1", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Alex", "coming Saturday?", null, at(14 * 60 + 2),
        chatNumber = "+447700900123",
    )
    private val reply = MessageTriage(TriageLane.NEEDS_REPLY, draft = "Yes, see you there", summary = "Asks if you're coming Saturday")

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    private fun liveOnPhone() {
        fold.save(alex, reply)
        assertEquals(1, fold.markPhoneLive(setOf("wa-1")))
        assertEquals(0, fold.markPhoneLive(setOf("wa-1")), "only a change is written")
        syncBoth()
    }

    @Test
    fun theMacOffersSendViaYourPhoneOnlyWhileThePhoneHoldsTheNotification() {
        fold.save(alex, reply)
        syncBoth()
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(mac.open().single(), live = false, canSend = false))
        fold.markPhoneLive(setOf("wa-1"))
        syncBoth()
        val card = mac.open().single()
        assertTrue(card.phoneLive)
        assertEquals(TriageCardPrimary.SEND_VIA_PHONE, rules.primary(card, live = false, canSend = false))
        assertEquals("Send (via your phone)", rules.label(TriageCardPrimary.SEND_VIA_PHONE))
        assertEquals(rules.ROUTE_VIA_PHONE, rules.routeLine(card, TriageCardPrimary.SEND_VIA_PHONE))
        // The Fold itself still sends with its own Send.
        assertEquals(TriageCardPrimary.SEND, rules.primary(fold.open().single(), live = true))
        // The notification goes: the Mac falls back to Reply in WhatsApp.
        fold.markPhoneLive(emptySet())
        syncBoth()
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(mac.open().single(), live = false, canSend = false))
    }

    @Test
    fun theFoldSendsTheMacsRequestOnceAndTheCardLeavesBothDevices() {
        liveOnPhone()
        assertTrue(mac.requestPhoneSend("wa-1", "  Yes, see you at 10  "))
        assertFalse(mac.requestPhoneSend("wa-1", "again"), "one request at a time")
        val waiting = mac.open().single()
        assertEquals(PhoneSendState.WAITING, waiting.phoneSend)
        assertTrue(rules.waitingOnPhone(waiting))
        assertEquals(rules.PHONE_WAITING_LINE, rules.phoneSendLine(waiting))
        assertNull(rules.routeLine(waiting, TriageCardPrimary.SEND_VIA_PHONE))
        syncBoth()
        val due = fold.phoneSendsDue().single()
        assertEquals("wa-1", due.messageId)
        assertEquals("Yes, see you at 10", due.text)
        assertTrue(fold.phoneSendDone(due.messageId, due.atMs, PhoneSendOutcome.SENT))
        assertFalse(fold.phoneSendDone(due.messageId, due.atMs, PhoneSendOutcome.SENT), "answered once")
        assertTrue(fold.phoneSendsDue().isEmpty())
        syncBoth()
        assertTrue(mac.open().isEmpty())
        val e = m.replica.entities(EntityTypes.TRIAGE_CARD).single()
        assertEquals(TriageResolution.SENT.wire, e[TriageCardFields.RESOLUTION].textOrNull)
        assertNull(e[TriageCardFields.PHONE_SEND_TEXT].textOrNull, "the words are blanked")
    }

    @Test
    fun aGoneNotificationSaysSoAndTheMacFallsBackToReplyInWhatsApp() {
        liveOnPhone()
        assertTrue(mac.requestPhoneSend("wa-1", "Yes"))
        syncBoth()
        val due = fold.phoneSendsDue().single()
        assertTrue(fold.phoneSendDone(due.messageId, due.atMs, PhoneSendOutcome.GONE))
        syncBoth()
        val card = mac.open().single()
        assertEquals(PhoneSendState.GONE, card.phoneSend)
        assertFalse(card.phoneLive)
        assertEquals(rules.PHONE_GONE_LINE, rules.phoneSendLine(card))
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(card, live = false, canSend = false))
        assertFalse(mac.requestPhoneSend("wa-1", "Yes"), "the phone no longer holds it")
        // A fresh notification for it: offered again, the old failure no longer shown.
        fold.markPhoneLive(setOf("wa-1"))
        syncBoth()
        val again = mac.open().single()
        assertNull(rules.phoneSendLine(again))
        assertEquals(TriageCardPrimary.SEND_VIA_PHONE, rules.primary(again, live = false, canSend = false))
    }

    @Test
    fun aRequestThePhoneGetsLateIsNeverSent() {
        liveOnPhone()
        assertTrue(mac.requestPhoneSend("wa-1", "Yes"))
        world.clock.nowMs += TriageCards.PHONE_SEND_TTL_MS + 1
        syncBoth()
        assertTrue(fold.phoneSendsDue().isEmpty())
        val card = mac.open().single()
        assertEquals(PhoneSendState.LATE, card.phoneSend)
        assertEquals(rules.PHONE_LATE_LINE, rules.phoneSendLine(card))
        assertEquals(TriageCardPrimary.REPLY_IN_APP, rules.primary(card, live = false, canSend = false))
    }

    @Test
    fun anAnsweredCardOrABlankReplyIsNeverAsked() {
        liveOnPhone()
        assertFalse(mac.requestPhoneSend("wa-1", "   "))
        assertFalse(mac.requestPhoneSend("nope", "Yes"))
        assertTrue(mac.resolve("wa-1", TriageResolution.DISMISSED))
        assertFalse(mac.requestPhoneSend("wa-1", "Yes"))
        syncBoth()
        assertTrue(fold.phoneSendsDue().isEmpty())
    }

    @Test
    fun aRequestReplacedByANewerOneIsNotAnsweredForIt() {
        liveOnPhone()
        assertTrue(mac.requestPhoneSend("wa-1", "Yes"))
        syncBoth()
        val first = fold.phoneSendsDue().single()
        world.clock.nowMs += TriageCards.PHONE_SEND_TTL_MS + 1
        assertTrue(mac.requestPhoneSend("wa-1", "Yes, at 10"), "a late one can be asked again once the phone holds it")
        syncBoth()
        assertFalse(fold.phoneSendDone(first.messageId, first.atMs, PhoneSendOutcome.SENT))
        assertEquals("Yes, at 10", fold.phoneSendsDue().single().text)
    }
}
