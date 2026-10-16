package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailRefundRules
import os.meka.core.domain.MailReturnRules
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Op
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Returns and warranties, completeness: Sent it back waits for the refund, Keeping it just clears; heads-ups on both. */
class MailReturnFacadeTest {
    private var now = 1_792_225_800_000L // Wed 14 Oct 2026, 09:30 London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun sentItBackPutsTheRefundOnWaitingForOnBothDevices() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val today = fold.todayEpochDay()
        val id = fold.addRenewal("Last day to return · Amazon order", ObligationKind.OTHER, today + 2, RenewalRepeat.NONE, "40", null)
        val keep = fold.addRenewal("Last day to return · Next order", ObligationKind.OTHER, today + 2, RenewalRepeat.NONE, null, null)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        // The heads-up two days before, one per order, on both devices.
        assertEquals(2, mac.noticesNow().count { it.source == NoticeSource.RETURNS })
        assertTrue(mac.noticesNow().none { it.source == NoticeSource.RENEWAL && it.title.startsWith("Last day") })
        val item = mac.listsView.value.renewals.all.single { it.id == id }
        assertEquals("Sent it back", item.doneLabel)
        assertEquals("Keeping it", item.stopLabel)

        mac.renewalDone(id)
        mac.stopRenewal(keep)
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertTrue(fold.listsView.value.renewals.all.none { it.id == id || it.id == keep })
        val wait = fold.listsView.value.waiting.single()
        assertEquals(MailReturnRules.refundWaitId(id), wait.id)
        assertEquals("Refund · Amazon order · £40", wait.title)
        assertEquals("Amazon", wait.who)
        assertTrue(fold.noticesNow().none { it.source == NoticeSource.RETURNS })
    }

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvrf${k++}", "hh", EntityTypes.MAIL_FIND, id, f, v, clock.now(), emptyList(), "server"))
    }

    @Test
    fun theShopsRefundEmailClosesTheRefundRowOnBothDevicesWithUndo() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val today = fold.todayEpochDay()
        val id = fold.addRenewal("Last day to return · Amazon order", ObligationKind.OTHER, today + 2, RenewalRepeat.NONE, "40", null)
        fold.renewalDone(id)
        val r = MailRefundRules.read("Amazon.co.uk", "no-reply@amazon.co.uk", "Your refund of £40.00 has been processed", null)!!
        val fid = MailRefundRules.findId("acc1", "m1")
        serverWrite(fid, MailRefundRules.serverFields(r, "Personal", "Your refund of £40.00 has been processed", today))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        val row = mac.mailFinds.value!!.rows.single()
        assertEquals("Close it", row.addLabel)
        assertEquals("Refunded £40 today · closes “Refund · Amazon order · £40” on Waiting for", row.line)
        assertEquals("Closed Refund · Amazon order · £40 · Amazon refunded it today", mac.addMailFind(fid))
        assertTrue(mac.listsView.value.waiting.isEmpty())
        assertNull(mac.mailFinds.value)
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertTrue(fold.listsView.value.waiting.isEmpty())
        assertNull(fold.mailFinds.value)

        // Undo: the row is back on Waiting for, the card back in Needs you.
        mac.undoMailFind()
        assertEquals(listOf(MailReturnRules.refundWaitId(id)), mac.listsView.value.waiting.map { it.id })
        assertEquals(listOf(fid), mac.mailFinds.value!!.rows.map { it.id })
        // Not this one: the row stays.
        assertEquals(MailRefundRules.NOT_THIS_LINE, mac.notNeededMailFind(fid))
        assertNull(mac.mailFinds.value)
        assertEquals(1, mac.listsView.value.waiting.size)
    }
}
