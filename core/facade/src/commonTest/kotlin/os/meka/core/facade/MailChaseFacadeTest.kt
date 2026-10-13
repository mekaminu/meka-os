package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DueState
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailItemFields
import os.meka.core.domain.MailNeedsRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6b-3: "Time to chase" through the facade — the chase drafted when it's due, sent only on Send. */
class MailChaseFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmc${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, from: String, subject: String): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields("Personal", from, null, subject, "Can you send the quote?", now - hour, 1))
        return id
    }

    private fun latest(id: String, field: String): FieldValue? =
        serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityId == id && it.field == field }.lastOrNull()?.value

    @Test
    fun aDueChaseIsDraftedSentOnlyOnSendAndUndoStopsIt() = runTest {
        val a = email("m1", "Sam Taylor", "Re: The quote for the kitchen")
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow())
        fold.waitingFromMailItem(a)
        assertNull(fold.mailChases.value)
        // Three days on, at the chase day: the card with Navilife's chase in it, on both devices.
        now += 3 * day
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        fold.tick(); mac.tick()
        val card = assertNotNull(mac.mailChases.value)
        assertEquals("1 to chase by email", card.headline)
        val row = card.rows.single()
        assertEquals("The quote for the kitchen", row.title)
        assertEquals("Personal · waiting since Sat 17 Oct · chase today", row.line)
        assertEquals("Hi Sam,\n\nJust checking in on this — is there any news?\n\nThanks", row.draft)
        assertNull(latest(a, MailItemFields.CHASE_TEXT))

        // Empty words: nothing written.
        assertEquals(MailNeedsRules.REPLY_EMPTY, mac.chaseMailItem(a, "  "))
        // Send: his words for the server, the chase recorded, the card gone.
        assertEquals(MailChaseRules.sendingLine("Sam Taylor", "Personal"), mac.chaseMailItem(a, "Hi Sam, any news on the quote?"))
        assertNull(mac.mailChases.value)
        mac.tick()
        val w = mac.listsView.value.waiting.single()
        assertEquals(DueState.LATER, w.state)
        assertTrue(mac.syncNow())
        assertEquals(FieldValue.Text("Hi Sam, any news on the quote?"), latest(a, MailItemFields.CHASE_TEXT))
        // Undo before the server sent it: never sent, and the chase is due again.
        mac.undoMailChase()
        assertNotNull(mac.mailChases.value)
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertNotNull(fold.mailChases.value)
        fold.tick()
        assertEquals(DueState.DUE, fold.listsView.value.waiting.single().state)
    }

    @Test
    fun aFailedChaseComesBackWithHisWordsAndNotYetAndGotItAnswerIt() = runTest {
        val a = email("m1", "Sam Taylor", "Quote")
        val fold = core("android")
        assertTrue(fold.syncNow())
        fold.waitingFromMailItem(a)
        now += 3 * day
        fold.tick()
        fold.chaseMailItem(a, "Any news?")
        assertTrue(fold.syncNow())
        // The server couldn't send it.
        now += hour
        serverWrite(a, MailChaseRules.failedFields(now, MailNeedsRules.whyRefused("Gmail")))
        assertTrue(fold.syncNow())
        val row = assertNotNull(fold.mailChases.value).rows.single()
        assertEquals("Chase didn't send · Gmail didn't take it · your words are kept", row.notice)
        assertEquals("Any news?", row.draft)

        // Not yet: put aside, chase again in 3 days.
        assertEquals(MailChaseRules.NOT_YET_LINE, fold.notYetMailChase(a))
        assertNull(fold.mailChases.value)
        fold.undoMailChase()
        assertNull(fold.mailChases.value) // the failure stays put aside; the chase date (already moved by Send) is back as it was
        now += 3 * day
        fold.tick()
        assertNotNull(fold.mailChases.value)
        // Got it: off Waiting for; Undo puts it back.
        assertEquals(MailChaseRules.GOT_LINE, fold.gotMailChase(a))
        assertNull(fold.mailChases.value)
        fold.tick()
        assertTrue(fold.listsView.value.waiting.isEmpty())
        fold.undoMailChase()
        fold.tick()
        assertEquals(1, fold.listsView.value.waiting.size)
        assertNotNull(fold.mailChases.value)
        assertEquals(MailChaseRules.UNKNOWN, fold.gotMailChase("nothing"))
    }
}
