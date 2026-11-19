package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailTidiedAction
import os.meka.core.domain.MailTidiedEmail
import os.meka.core.domain.MailTidiedRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.TidiedKind
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

/** Tidied mail, slice 4: Trash all n promos (and a picked few) from one device reaches the other; Undo takes it back. */
class MailTidiedBulkFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 3 else 4), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvbk${k++}", "hh", EntityTypes.MAIL_TIDY, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun promo(id: String, from: String, minsAgo: Int) = MailTidiedEmail(
        MailTidiedRules.key("acc", id), MailTidy.ARCHIVE, MailSort.PROMO, now - minsAgo * 60_000L, from, "${from.lowercase()}.example", "Sale $id", null,
    )

    @Test
    fun trashAllPromosAsksEachOnceReachesTheMacAndUndoTakesItOff() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val a = listOf(promo("p1", "Deliveroo", 50), promo("p2", "Amazon", 40))
        val b = listOf(promo("p3", "Boots", 20))
        serverWrite("mt1", MailAutopilotRules.batchFields(now - 45 * 60_000L, "Personal", mapOf(MailTidy.ARCHIVE to 2)) + MailTidiedRules.fields(a))
        serverWrite("mt2", MailAutopilotRules.batchFields(now - 10 * 60_000L, "Personal", mapOf(MailTidy.ARCHIVE to 1)) + MailTidiedRules.fields(b))
        fold.syncNow(); mac.syncNow()

        val all = assertNotNull(fold.archivedAll(null, TidiedKind.PROMOS.id).trashAll)
        assertEquals("Trash all 3 promos", all.label)
        assertEquals("Moving 3 promos to Trash at Navilife's next check",
            fold.askArchivedEmails(all.ids, MailTidiedAction.TRASH.name, TidiedKind.PROMOS.id))
        // Asked once: nothing left to trash, and asking again does nothing.
        assertNull(fold.archivedAll(null, TidiedKind.PROMOS.id).trashAll)
        assertNull(fold.askArchivedEmails(all.ids, MailTidiedAction.TRASH.name))
        fold.syncNow(); mac.syncNow()
        val rows = mac.archivedAll(null, TidiedKind.PROMOS.id).days.flatMap { it.rows }
        assertEquals(listOf(MailTidiedAction.TRASH.pending), rows.map { it.row.state }.distinct())

        mac.undoArchivedEmails(all.ids, MailTidiedAction.TRASH.name)
        mac.syncNow(); fold.syncNow()
        assertEquals(3, assertNotNull(fold.archivedAll(null, TidiedKind.PROMOS.id).trashAll).ids.size)

        // A picked one put back; an unknown action does nothing.
        assertEquals("Putting 1 email back at Navilife's next check", fold.askArchivedEmails(listOf(all.ids.first()), MailTidiedAction.PUT_BACK.name))
        assertNull(fold.askArchivedEmails(all.ids, "NOPE"))
        assertEquals(2, assertNotNull(fold.archivedAll(null, TidiedKind.PROMOS.id).trashAll).ids.size)
    }
}
