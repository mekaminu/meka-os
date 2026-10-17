package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailItemFields
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailCardUnsubRules
import os.meka.core.domain.MailSenderRuleRules
import os.meka.core.domain.UnsubscribeState
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

/** Inbox autopilot, slice 6a: "From your mail" in Needs you through the facade, Done and Snooze on both devices. */
class MailNeedsFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmi${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(
        message: String, from: String, subject: String, agoMs: Long, account: String = "Personal",
        warning: String = "", canUnsubscribe: Boolean = false,
    ): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields(account, from, null, subject, "Hi, can you look at this", now - agoMs, 1,
            warning = warning, canUnsubscribe = canUnsubscribe))
        return id
    }

    @Test
    fun doneFoldsTheCardAwayOnBothDevicesAndUndoBringsItBack() = runTest {
        val a = email("m1", "Sam Taylor", "Football on Saturday", hour)
        val b = email("m2", "Alex Reed", "Invoice question", 2 * hour, "Business")
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val card = assertNotNull(fold.mailNeeds.value)
        assertEquals("2 emails need you", card.headline)
        assertEquals(listOf(a, b), card.rows.map { it.id })
        assertTrue(card.rows[1].business)
        assertEquals("Personal · 08:30", card.rows[0].line)

        assertEquals(MailNeedsRules.DONE_LINE, fold.doneMailItem(a))
        assertEquals(listOf(b), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(b), mac.mailNeeds.value?.rows?.map { it.id })
        // The server sees the Done.
        assertTrue(serverOps.after("hh", 0, 100_000).map { it.op }.any { it.entityId == a && it.field == MailItemFields.DONE_ASKED_AT })

        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a, b), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(a, b), mac.mailNeeds.value?.rows?.map { it.id })
        // A second Undo does nothing.
        fold.undoMailItem()
        assertEquals(2, fold.mailNeeds.value?.rows?.size)
    }

    @Test
    fun alwaysTidyIsADoneThatAsksForTheSendersRuleAndUndoTakesItBack() = runTest {
        // Slice 10b: Always tidy on a card.
        val a = email("m1", "Tool Reports", "Weekly usage", hour)
        val b = email("m2", "Alex Reed", "Invoice question", 2 * hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MailSenderRuleRules.cardLine("Tool Reports"), fold.alwaysTidyMailItem(a))
        assertEquals(listOf(b), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(b), mac.mailNeeds.value?.rows?.map { it.id })
        val fields = serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityId == a }.map { it.field }
        assertTrue(MailItemFields.DONE_ASKED_AT in fields && MailItemFields.TIDY_ASKED_AT in fields)
        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a, b), fold.mailNeeds.value?.rows?.map { it.id })
        assertEquals(MailNeedsRules.UNKNOWN, fold.alwaysTidyMailItem("nothing"))
    }

    @Test
    fun blockAndUnsubscribeAreDonesThatAskTheServerAndUndoTakesThemBack() = runTest {
        // Junk fix slice 2: Block sender (Bin & block on a possible scam) and Unsubscribe on a card.
        val a = email("m1", "Prize Team", "4 HOURS LEFT", hour, canUnsubscribe = true)
        val b = email("m2", "Notification via PayPal", "Your account will be closed", 2 * hour,
            warning = "⚠ Possible scam · sent from mail-x.example, not paypal.com")
        val c = email("m3", "Alex Reed", "Invoice question", 3 * hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val rows = assertNotNull(fold.mailNeeds.value).rows.associateBy { it.id }
        assertTrue(rows.getValue(a).canUnsubscribe && rows.getValue(a).canBlock)
        assertTrue(rows.getValue(b).scam && rows.getValue(b).canBlock && !rows.getValue(b).canUnsubscribe)
        assertEquals(MailSenderRuleRules.binLine("Notification via PayPal"), fold.blockMailItem(b))
        assertEquals(MailCardUnsubRules.line("Prize Team"), fold.unsubscribeMailItem(a))
        assertEquals(listOf(c), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(c), mac.mailNeeds.value?.rows?.map { it.id })
        val ops = serverOps.after("hh", 0, 100_000).map { it.op }
        assertTrue(ops.any { it.entityId == b && it.field == MailItemFields.RULE_ASKED })
        assertTrue(ops.any { it.entityId == a && it.field == MailItemFields.UNSUB_ASKED_AT })
        // Undo takes back the last one (the unsubscribe): the card is back and it's called off.
        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a, c), fold.mailNeeds.value?.rows?.map { it.id })
        // Unsubscribe on an email without a link says it can't.
        assertEquals(UnsubscribeState.FAILED.note, fold.unsubscribeMailItem(c))
        assertEquals(MailNeedsRules.UNKNOWN, fold.blockMailItem("nothing"))
    }

    @Test
    fun linkedEmailsGoAndComeBackTogether() = runTest {
        // Slice 6c: two emails from Sam about the same thing share a row; Done takes both, Undo brings both back.
        val a = email("m1", "Sam Taylor", "Re: Football on Saturday", hour)
        val b = email("m2", "Sam Taylor", "Football on Saturday", 2 * hour)
        val c = email("m3", "Alex Reed", "Invoice question", 3 * hour)
        val fold = core("android")
        assertTrue(fold.syncNow())
        val card = assertNotNull(fold.mailNeeds.value)
        assertEquals(listOf(a, c), card.rows.map { it.id })
        assertEquals(listOf(b), card.rows[0].linked.map { it.id })
        assertEquals("Done · 2 emails archived at Navilife's next check", fold.doneMailItem(a))
        assertEquals(listOf(c), fold.mailNeeds.value?.rows?.map { it.id })
        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a, c), fold.mailNeeds.value?.rows?.map { it.id })
        // Snooze and Task take the group too.
        assertEquals("2 emails snoozed till tomorrow 08:00", fold.snoozeMailItem(a))
        assertEquals(listOf(c), fold.mailNeeds.value?.rows?.map { it.id })
        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a, c), fold.mailNeeds.value?.rows?.map { it.id })
        assertEquals("Task added · 2 emails archived at Navilife's next check", fold.taskFromMailItem(a))
        assertEquals(listOf(c), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow())
        val asked = serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.field == MailItemFields.DONE_ASKED_AT }.map { it.entityId }.toSet()
        assertTrue(a in asked && b in asked)
        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a, c), fold.mailNeeds.value?.rows?.map { it.id })
    }

    @Test
    fun snoozeHidesItUntilTomorrowMorning() = runTest {
        val a = email("m1", "Sam Taylor", "Football on Saturday", hour)
        val mac = core("mac")
        assertTrue(mac.syncNow())
        assertEquals(MailNeedsRules.SNOOZED_LINE, mac.snoozeMailItem(a))
        assertNull(mac.mailNeeds.value)
        mac.undoMailItem()
        assertNotNull(mac.mailNeeds.value)
        mac.snoozeMailItem(a)
        assertNull(mac.mailNeeds.value)
        // Tomorrow 08:00 in London it's back.
        now += 22 * hour + 30 * 60_000L
        mac.tick()
        assertEquals(listOf(a), mac.mailNeeds.value?.rows?.map { it.id })
        assertEquals(MailNeedsRules.UNKNOWN, mac.doneMailItem("nothing"))
        assertEquals(MailNeedsRules.UNKNOWN, mac.snoozeMailItem("nothing"))
    }

    // ---- Slice 6b-1 ----

    private fun task(id: String) = serverOps.after("hh", 0, 100_000).map { it.op }
        .filter { it.entityType == EntityTypes.TASK && it.entityId == id }.associate { it.field to it.value }

    @Test
    fun taskMakesOneTaskOnBothDevicesArchivesTheEmailAndUndoTakesItBack() = runTest {
        val a = email("m1", "Sam Taylor", "Re: Kit list for Saturday", hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MailNeedsRules.TASK_LINE, fold.taskFromMailItem(a))
        assertEquals(MailNeedsRules.TASK_LINE, mac.taskFromMailItem(a))
        assertNull(fold.mailNeeds.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        val t = task(MailNeedsRules.taskId(a))
        assertEquals(FieldValue.Text("Kit list for Saturday"), t[os.meka.core.domain.ActionableFields.TITLE])
        assertEquals(FieldValue.Text("Email from Sam Taylor · Personal · Sat 17 Oct"), t[os.meka.core.domain.ActionableFields.NOTES])
        // Only the one task, whichever device made it.
        assertEquals(1, serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityType == EntityTypes.TASK }.map { it.entityId }.distinct().size)
        // The server sees a Done, so it archives the email.
        assertTrue(serverOps.after("hh", 0, 100_000).map { it.op }.any { it.entityId == a && it.field == MailItemFields.DONE_ASKED_AT })

        now += 1_000
        mac.undoMailItem()
        assertEquals(listOf(a), mac.mailNeeds.value?.rows?.map { it.id })
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(listOf(a), fold.mailNeeds.value?.rows?.map { it.id })
        assertEquals(FieldValue.Bool(true), task(MailNeedsRules.taskId(a))[os.meka.core.domain.ActionableFields.DELETED])
        // Task again brings the same task back.
        now += 1_000
        fold.taskFromMailItem(a)
        assertTrue(fold.syncNow())
        assertEquals(FieldValue.Bool(false), task(MailNeedsRules.taskId(a))[os.meka.core.domain.ActionableFields.DELETED])
        assertEquals(MailNeedsRules.UNKNOWN, fold.taskFromMailItem("nothing"))
    }

    @Test
    fun waitingForPutsItInWaitingForFromTheSenderAndUndoRemovesIt() = runTest {
        val a = email("m1", "Alex Reed", "Fwd: Quote for the fence", hour)
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow())
        assertEquals(MailNeedsRules.WAITING_LINE, fold.waitingFromMailItem(a))
        assertNull(fold.mailNeeds.value)
        val w = fold.listsView.value.waiting.single()
        assertEquals("Quote for the fence", w.title)
        assertEquals("Alex Reed", w.who)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf("Quote for the fence"), mac.listsView.value.waiting.map { it.title })
        assertNull(mac.mailNeeds.value)

        now += 1_000
        fold.undoMailItem()
        assertTrue(fold.listsView.value.waiting.isEmpty())
        assertEquals(listOf(a), fold.mailNeeds.value?.rows?.map { it.id })
        // Made again after the Undo: the same item comes back, once.
        now += 1_000
        fold.waitingFromMailItem(a)
        assertEquals(1, fold.listsView.value.waiting.size)
        assertEquals(MailNeedsRules.UNKNOWN, fold.waitingFromMailItem("nothing"))
    }

    // ---- Slice 6b-2a ----

    @Test
    fun sendWritesHisWordsForTheServerAndUndoStopsIt() = runTest {
        val a = email("m1", "Sam Taylor", "Football on Saturday", hour, "Business")
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        // Nothing to send: nothing written, the card stays.
        assertEquals(MailNeedsRules.REPLY_EMPTY, fold.replyToMailItem(a, "  "))
        assertNotNull(fold.mailNeeds.value)
        assertEquals("Reply sending from Business at Navilife's next check · Undo stops it", fold.replyToMailItem(a, "Yes, I can take them."))
        assertNull(fold.mailNeeds.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailNeeds.value)
        val ops = serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityId == a }.associate { it.field to it.value }
        assertEquals(FieldValue.Text("Yes, I can take them."), ops[MailItemFields.REPLY_TEXT])
        assertEquals(FieldValue.Int64(now), ops[MailItemFields.REPLY_ASKED_AT])

        now += 1_000
        fold.undoMailItem()
        assertEquals(listOf(a), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val item = MailNeedsRules.read(a, serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityId == a }.associate { it.field to it.value })!!
        assertTrue(!MailNeedsRules.replyDue(item, now + 60_000))
        assertEquals(listOf(a), mac.mailNeeds.value?.rows?.map { it.id })
        assertEquals(MailNeedsRules.UNKNOWN, fold.replyToMailItem("nothing", "Hi"))
    }
}
