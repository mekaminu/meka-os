package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailPromiseRules
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
}
