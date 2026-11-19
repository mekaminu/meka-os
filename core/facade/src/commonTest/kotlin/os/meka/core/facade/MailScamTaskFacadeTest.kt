package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActionableFields
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailItemFields
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailScamTaskRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A possible scam never becomes a task (Meka 2026-11-19): Task is refused, and a task made before the flag is removed. */
class MailScamTaskFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))
    private val scam = "⚠ Possible scam · sent from mail-x.example, not invest.example"

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmi${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, subject: String, warning: String = ""): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields("Personal", "Invest Co", null, subject, "Our details are changing", now - hour, 1,
            warning = warning))
        return id
    }

    private fun task(id: String) = serverOps.after("hh", 0, 100_000).map { it.op }
        .filter { it.entityType == EntityTypes.TASK && it.entityId == id }.associate { it.field to it.value }

    private fun activityLines(c: MekaCore) = c.activityView.value.days.flatMap { it.rows }.map { it.summary }

    @Test
    fun taskAndWaitingForAreRefusedOnAPossibleScamAndNothingIsMade() = runTest {
        val a = email("m1", "Our bank account details are changing", warning = scam)
        val fold = core("android")
        assertTrue(fold.syncNow())
        assertTrue(fold.mailNeeds.value!!.rows.single().scam)
        assertEquals(MailScamTaskRules.BLOCKED_LINE, fold.taskFromMailItem(a))
        assertEquals(MailScamTaskRules.BLOCKED_LINE, fold.mailToDay(a, fold.boardFeedDays().first().day))
        assertEquals(MailScamTaskRules.BLOCKED_LINE, fold.waitingFromMailItem(a))
        assertTrue(fold.syncNow())
        assertTrue(task(MailNeedsRules.taskId(a)).isEmpty())
        // Still on the card: nothing was archived by the refused tap.
        assertEquals(listOf(a), fold.mailNeeds.value?.rows?.map { it.id })
    }

    @Test
    fun aTaskMadeBeforeTheEmailWasFlaggedIsRemovedOnceWithALineInActivity() = runTest {
        val a = email("m1", "Our bank account details are changing")
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(MailNeedsRules.TASK_LINE, fold.taskFromMailItem(a))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(FieldValue.Bool(false), task(MailNeedsRules.taskId(a))[ActionableFields.DELETED])

        // The server's body check flags it afterwards.
        now += 60_000
        serverWrite(a, mapOf(MailItemFields.WARNING to FieldValue.Text(scam)))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(FieldValue.Bool(true), task(MailNeedsRules.taskId(a))[ActionableFields.DELETED])
        val line = "Removed “Our bank account details are changing” from your list · it came from a possible scam email"
        assertEquals(1, activityLines(fold).count { it == line })
        assertEquals(1, activityLines(mac).count { it == line })
        // One entry in the log however many devices saw it.
        assertEquals(1, serverOps.after("hh", 0, 100_000).map { it.op }
            .filter { it.entityType == EntityTypes.AGENT_ACTION && it.field == "summary" && it.value == FieldValue.Text(line) }
            .map { it.entityId }.distinct().size)
    }

    @Test
    fun aFinishedTaskAndAnEmailHeSaidIsRealAreLeftAlone() = runTest {
        val done = email("m1", "Statement ready")
        val real = email("m2", "Your new account details")
        val fold = core("android")
        assertTrue(fold.syncNow())
        fold.taskFromMailItem(done)
        fold.taskFromMailItem(real)
        fold.complete(MailNeedsRules.taskId(done))
        assertTrue(fold.syncNow())
        // Not a scam on the second one, from either app, before the server flags both.
        now += 60_000
        serverWrite(done, mapOf(MailItemFields.WARNING to FieldValue.Text(scam)))
        serverWrite(real, mapOf(MailItemFields.WARNING to FieldValue.Text(scam), MailItemFields.NOT_SCAM_AT to FieldValue.Int64(now - 1_000)))
        assertTrue(fold.syncNow()); assertTrue(fold.syncNow())
        assertEquals(FieldValue.Bool(false), task(MailNeedsRules.taskId(done))[ActionableFields.DELETED])
        assertEquals(FieldValue.Bool(false), task(MailNeedsRules.taskId(real))[ActionableFields.DELETED])
        assertTrue(activityLines(fold).none { it.startsWith("Removed") })
    }
}
