package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailFindFields
import os.meka.core.domain.MailFollowUpRules
import os.meka.core.domain.MailPromiseRules
import os.meka.core.domain.MailSentFacts
import os.meka.core.domain.MailRenewalRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Promises, receipts and renewals from email, slice 1: a renewal the server read reaches Needs you on both devices;
 * Add puts it on Renewals (once, on both) and Undo takes it off; Not needed clears it on both.
 */
class MailFindFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmf${k++}", "hh", EntityTypes.MAIL_FIND, id, f, v, clock.now(), emptyList(), "server"))
    }

    private val received = CivilDate.toEpochDay(2026, 10, 16)

    private fun renewal(subject: String, preview: String, name: String) =
        MailRenewalRules.read(name, null, subject, preview, received)!!

    @Test
    fun addPutsItOnRenewalsOnBothAppsAndNotNeededClearsIt() = runTest {
        val a = renewal("Your membership renews soon", "It renews on 3 Nov for £10.99 a month", "Streamly")
        val b = renewal("Your car insurance is due for renewal", "Renewal date: 28/11/2026", "Safe Drive")
        val ida = MailRenewalRules.findId("acc1", "m1", a)
        val idb = MailRenewalRules.findId("acc1", "m2", b)
        serverWrite(ida, MailRenewalRules.serverFields(a, "Personal", "Your membership renews soon", received))
        serverWrite(idb, MailRenewalRules.serverFields(b, "Personal", null, received))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(ida, idb), fold.mailFinds.value!!.rows.map { it.id })
        assertEquals(2, mac.mailFinds.value!!.rows.size)

        assertEquals("On Renewals: Streamly · renews Tue 3 Nov", fold.addMailFind(ida))
        assertEquals(listOf(idb), fold.mailFinds.value!!.rows.map { it.id })
        val onRadar = fold.listsView.value.renewals.let { it.attention + it.upcoming + it.later }
        assertEquals(1, onRadar.count { it.title == "Streamly" })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(idb), mac.mailFinds.value!!.rows.map { it.id })

        // Undo: off Renewals, back in the card.
        fold.undoMailFind()
        assertEquals(listOf(ida, idb), fold.mailFinds.value!!.rows.map { it.id })
        assertEquals(0, fold.listsView.value.renewals.let { it.attention + it.upcoming + it.later }.count { it.title == "Streamly" })

        assertEquals(MailRenewalRules.NOT_NEEDED_LINE, mac.notNeededMailFind(idb))
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(listOf(ida), fold.mailFinds.value!!.rows.map { it.id })
        assertEquals(MailRenewalRules.UNKNOWN, fold.addMailFind("nope"))

        // Added on the Fold: gone everywhere once synced.
        fold.addMailFind(ida)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailFinds.value)
    }

    @Test
    fun aPromiseAddsToWaitingForOnBothAppsAndUndoTakesItOff() = runTest {
        val p = MailPromiseRules.read("Sam Taylor", "sam@example.com", "Re: contract", "I'll send you the signed contract by Tuesday", received)!!
        val id = MailPromiseRules.findId("acc1", "m9")
        serverWrite(id, MailPromiseRules.serverFields(p, "Personal", "Re: contract", received))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals("Promises from your mail", fold.mailFinds.value!!.title)
        assertEquals("Waiting for: Signed contract · by Tue 20 Oct", fold.mailFinds.value!!.rows.single().line)

        assertEquals("On Waiting for: Signed contract from Sam Taylor · chase Wed 21 Oct", fold.addMailFind(id))
        assertNull(fold.mailFinds.value)
        val w = fold.listsView.value.waiting.single()
        assertEquals("Signed contract", w.title)
        assertEquals("Sam Taylor", w.who)
        assertTrue(w.notes!!.startsWith("From an email in your Personal mail"))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailFinds.value)
        assertEquals(1, mac.listsView.value.waiting.size)

        // Undo: off Waiting for, back in the card.
        fold.undoMailFind()
        assertEquals(0, fold.listsView.value.waiting.size)
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
        // Added again on the Mac once synced: one item, the same id.
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(id), mac.mailFinds.value!!.rows.map { it.id })
        mac.addMailFind(id)
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(listOf(MailPromiseRules.waitingId(id)), fold.listsView.value.waiting.map { it.id })
    }

    @Test
    fun aFollowUpGoesOnWaitingForToChaseTodayAndAnAnswerClearsTheOther() = runTest {
        val sentDay = CivilDate.toEpochDay(2026, 10, 12)
        val own = setOf("alex@example.com")
        val fu = MailFollowUpRules.read(MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote", "Could you send the quote?", now - 5 * 86_400_000L), own, false)!!
        val other = MailFollowUpRules.read(MailSentFacts("Ann Lee", "ann@example.com", 1, "Dates", "Does Friday work?", now - 5 * 86_400_000L), own, false)!!
        val id = MailFollowUpRules.findId("acc1", "s1")
        val id2 = MailFollowUpRules.findId("acc1", "s2")
        serverWrite(id, MailFollowUpRules.serverFields(fu, "Personal", "Quote", sentDay))
        serverWrite(id2, MailFollowUpRules.serverFields(other, "Personal", "Dates", sentDay))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals("No reply yet", fold.mailFinds.value!!.title)
        assertEquals(2, mac.mailFinds.value!!.rows.size)

        assertEquals("On Waiting for: Reply about Quote from Sam Taylor · chase today", fold.addMailFind(id))
        val w = fold.listsView.value.waiting.single()
        assertEquals("Reply about Quote", w.title)
        assertEquals("Sam Taylor", w.who)
        assertEquals(CivilDate.toEpochDay(2026, 10, 17), w.chaseDay)
        assertEquals(listOf(id2), fold.mailFinds.value!!.rows.map { it.id })

        // Ann answers: the server marks it and it leaves both apps.
        serverWrite(id2, MailFollowUpRules.answeredFields(now, MailFollowUpRules.ANSWERED_REPLIED))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(fold.mailFinds.value)
        assertNull(mac.mailFinds.value)
        assertEquals(1, mac.listsView.value.waiting.size)

        // Undo: off Waiting for, back in the card.
        fold.undoMailFind()
        assertEquals(0, fold.listsView.value.waiting.size)
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
    }

    @Test
    fun aPersonsOwnWaitHoldsTheRowOnBothAppsAndUndoPutsTheDefaultBack() = runTest {
        // Sent Wed 14 Oct, today Sat 17 Oct: three days, so it's offered with the default wait.
        val sentDay = CivilDate.toEpochDay(2026, 10, 14)
        val own = setOf("alex@example.com")
        val fu = MailFollowUpRules.read(MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote", "Could you send the quote?", now - 3 * 86_400_000L), own, false)!!
        val id = MailFollowUpRules.findId("acc1", "w1")
        serverWrite(id, MailFollowUpRules.serverFields(fu, "Personal", "Quote", sentDay))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val row = fold.mailFinds.value!!.rows.single()
        assertEquals("Wait 3 days ▾", row.waitPill)
        assertEquals(listOf(3), row.waits.filter { it.chosen }.map { it.days })

        // A week for Sam: the row waits until Wed 21 Oct, on both apps.
        assertEquals("Waiting 1 week for Sam Taylor's replies · this one comes back Wed 21 Oct", fold.setFollowUpWait(id, 7))
        assertNull(fold.mailFinds.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailFinds.value)
        now += 4 * 86_400_000L
        assertTrue(mac.syncNow())
        assertEquals("Wait 1 week ▾", mac.mailFinds.value!!.rows.single().waitPill)

        // Back to Saturday; 1 day keeps it showing; Undo puts the week back; a choice off the list does nothing.
        now -= 4 * 86_400_000L
        assertEquals("Waiting 1 day for Sam Taylor's replies from now on", mac.setFollowUpWait(id, 1))
        assertEquals("Wait 1 day ▾", mac.mailFinds.value!!.rows.single().waitPill)
        mac.undoMailFind()
        assertNull(mac.mailFinds.value)
        assertEquals(MailRenewalRules.UNKNOWN, mac.setFollowUpWait(id, 4))
    }

    @Test
    fun chaseWaitsInMailSettingsChangeAndResetAPersonsWaitOnBothAppsWithUndo() = runTest {
        val sentDay = CivilDate.toEpochDay(2026, 10, 14)
        val own = setOf("alex@example.com")
        val fu = MailFollowUpRules.read(MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote", "Could you send the quote?", now - 3 * 86_400_000L), own, false)!!
        val id = MailFollowUpRules.findId("acc1", "cw1")
        serverWrite(id, MailFollowUpRules.serverFields(fu, "Personal", "Quote", sentDay))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        // Nobody set yet: the usual, and how to set one.
        assertTrue(mac.followUpWaitsPanel().rows.isEmpty())

        // A week for Sam from Needs you: Chase waits lists him on both apps.
        fold.setFollowUpWait(id, 7)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val row = mac.followUpWaitsPanel().rows.single()
        assertEquals("Sam Taylor", row.who)
        assertEquals("Waits 1 week before a chase is offered · set Sat 17 Oct", row.line)
        assertNull(mac.mailFinds.value)

        // Back to usual on the Mac: off the list, and the row is offered again on both apps; Undo puts the week back.
        assertEquals("Sam Taylor back to the usual 3 days", mac.changeFollowUpWait(row.id, 0))
        assertTrue(mac.followUpWaitsPanel().rows.isEmpty())
        assertEquals(listOf(id), mac.mailFinds.value!!.rows.map { it.id })
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
        assertEquals("Put back as it was", mac.undoFollowUpWaitChange())
        assertNull(mac.undoFollowUpWaitChange())
        assertEquals("Waits 1 week before a chase is offered · set Sat 17 Oct", mac.followUpWaitsPanel().rows.single().line)
        assertNull(mac.mailFinds.value)

        // A chip on the Fold: 1 day; a wait off the list or an unknown person changes nothing.
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals("Waiting 1 day for Sam Taylor's replies from now on", fold.changeFollowUpWait(row.id, 1))
        assertEquals("Wait 1 day ▾", fold.mailFinds.value!!.rows.single().waitPill)
        assertEquals(MailRenewalRules.UNKNOWN, fold.changeFollowUpWait(row.id, 4))
        assertEquals(MailRenewalRules.UNKNOWN, fold.changeFollowUpWait("fwnobody", 7))
    }

    @Test
    fun aFollowUpOnWaitingForIsChasedFromTimeToChaseAndTheirReplyComesBackToGotIt() = runTest {
        val sentDay = CivilDate.toEpochDay(2026, 10, 12)
        val fu = MailFollowUpRules.read(MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote", "Could you send the quote?", now - 5 * 86_400_000L), setOf("alex@example.com"), false)!!
        val id = MailFollowUpRules.findId("acc1", "s1")
        serverWrite(id, MailFollowUpRules.serverFields(fu, "Personal", "Quote", sentDay))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow())
        fold.addMailFind(id)
        // On Waiting for to chase today: Time to chase offers it with a chase that points at his email.
        val row = fold.mailChases.value!!.rows.single()
        assertEquals(id, row.id)
        assertEquals("Sam Taylor", row.from)
        assertEquals("Reply about Quote", row.title)
        assertEquals("Personal · you wrote Mon 12 Oct · chase today", row.line)
        assertTrue(row.draft.startsWith("Hi Sam,\n\nJust following up on my email below"), row.draft)
        assertEquals("To Sam Taylor · in the same conversation as your email, from Personal, only when you tap Send", row.replyLine)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(id), mac.mailChases.value!!.rows.map { it.id })

        // Send: his words on the find for the server; off the card on both apps. Undo brings it back, nothing sent.
        assertEquals("Chase sending to Sam Taylor from Personal at Navilife's next check · Undo stops it", fold.chaseMailItem(id, "Hi Sam, any news on the quote?"))
        assertNull(fold.mailChases.value)
        fold.undoMailChase()
        assertEquals(listOf(id), fold.mailChases.value!!.rows.map { it.id })
        fold.chaseMailItem(id, "Hi Sam, any news on the quote?")
        assertTrue(fold.syncNow())
        val asked = serverOps.after("hh", 0, 10_000).map { it.op }.last { it.entityId == id && it.field == MailFindFields.CHASE_TEXT }
        assertEquals(FieldValue.Text("Hi Sam, any news on the quote?"), asked.value)
        assertTrue(mac.syncNow())
        assertNull(mac.mailChases.value)

        // The server sent it; then Sam replies: the row comes back saying so, and Got it takes it off Waiting for.
        now += 120_000
        serverWrite(id, MailChaseRules.sentFields(now))
        now += 60_000
        serverWrite(id, MailFollowUpRules.answeredFields(now, MailFollowUpRules.ANSWERED_REPLIED))
        assertTrue(fold.syncNow())
        val back = fold.mailChases.value!!.rows.single()
        assertEquals("They replied on Sat 17 Oct · Got it if that settles it", back.notice)
        assertEquals(MailChaseRules.GOT_LINE, fold.gotMailChase(id))
        assertNull(fold.mailChases.value)
        assertEquals(0, fold.listsView.value.waiting.size)
    }
}
