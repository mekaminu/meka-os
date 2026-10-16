package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailSentActivityRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drafted replies, slice 3: a reply he sends shows in Activity at once, its Undo stops it, and the server's Sent follows. */
class MailSentActivityFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String = "android") = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvsa${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields("Personal", "Sam Taylor", "sam@example.com", "Saturday", "Can you drive on Saturday?", now - hour, 1))
        return id
    }

    private fun MekaCore.replyRow(id: String) =
        activityView.value.days.flatMap { it.rows }.firstOrNull { it.id == MailSentActivityRules.id(id) }

    @Test
    fun aSentReplyShowsInActivityAndItsUndoStopsIt() = runTest {
        val a = email("m1")
        val fold = core()
        assertTrue(fold.syncNow())
        fold.replyToMailItem(a, "Yes, I can drive.")
        val held = fold.replyRow(a)!!
        assertEquals("Sending reply to Sam Taylor · Personal", held.summary)
        assertTrue(held.canUndo)
        assertTrue(fold.mailNeeds.value?.rows.orEmpty().none { it.id == a })

        assertEquals(MailSentActivityRules.STOPPED, fold.undoActivity(MailSentActivityRules.id(a)))
        val stopped = fold.replyRow(a)!!
        assertFalse(stopped.canUndo)
        assertTrue(stopped.undoneLine!!.startsWith("Undone at"))
        assertTrue(fold.mailNeeds.value!!.rows.any { it.id == a })

        // Sent again; the Mac sees it held, then the server's Sent reaches both: no Undo, and too late to stop.
        now += 60_000
        fold.replyToMailItem(a, "Yes, I can drive.")
        assertTrue(fold.syncNow())
        val mac = core("mac")
        assertTrue(mac.syncNow())
        assertTrue(mac.replyRow(a)!!.canUndo)
        now += 60_000
        serverWrite(a, MailNeedsRules.replySentFields(now))
        assertTrue(fold.syncNow())
        assertTrue(mac.syncNow())
        for (c in listOf(fold, mac)) {
            val row = c.replyRow(a)!!
            assertEquals("Replied to Sam Taylor · Personal", row.summary)
            assertEquals("Re: Saturday · “Yes, I can drive.” · Sent", row.detail)
            assertFalse(row.canUndo)
        }
        assertEquals(MailSentActivityRules.TOO_LATE, mac.undoActivity(MailSentActivityRules.id(a)))
    }
}
