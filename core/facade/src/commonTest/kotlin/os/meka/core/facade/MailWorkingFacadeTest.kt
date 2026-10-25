package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.FieldValue
import os.meka.core.domain.MailBacklogFields
import os.meka.core.domain.MailBacklogKind
import os.meka.core.domain.MailBacklogRules
import os.meka.core.domain.MailMarkReadRules
import os.meka.core.domain.MailWorkingRules
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Approve must land, last slice: after Mark read on Needs you, "Working on it" counts up as the server reports. */
class MailWorkingFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()
    private var seq = 0
    private val id = MailBacklogRules.entityId("gmail:alex")

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun serverWrites(fields: Map<String, FieldValue>) {
        for ((f, v) in fields) {
            seq++
            ops.append(Op("s$seq", "hh", EntityTypes.MAIL_BACKLOG, id, f, v, Hlc(now, seq, "server"), emptyList(), "server"))
        }
    }

    @Test
    fun markReadOnNeedsYouLeavesALineThatCountsUp() = runTest {
        val c = core()
        serverWrites(
            MailBacklogRules.fields("Personal", now - 3_600_000L, 3_000, mapOf(MailBacklogKind.KEEP to 3_000), null, now - 1_800_000L) + mapOf(
                MailBacklogFields.ACCOUNT_KEY to FieldValue.Text("gmail:alex"),
                MailBacklogFields.ACCOUNT_NAME to FieldValue.Text("Personal Gmail"),
                MailBacklogFields.TIDY_UNREAD to FieldValue.Int64(600),
                MailBacklogFields.UNREAD_COUNTED_AT to FieldValue.Int64(now),
            ),
        )
        c.syncNow()
        val card = c.needsYouStack.value.cards.single { MailMarkReadRules.isCard(it.id) }
        assertTrue(c.needsYouStack.value.working.isEmpty())

        c.approveMailBacklog(card.id)
        assertTrue(c.needsYouStack.value.cards.none { it.id == card.id })
        val sent = c.needsYouStack.value.working.single()
        assertEquals("Marking read · Personal Gmail", sent.title)
        assertEquals(MailWorkingRules.SENDING, sent.meta)

        // The server's next check: it echoes the approval and marks the first 500.
        c.syncNow()
        now += 5 * 60_000L
        val approvedAt = now - 5 * 60_000L
        serverWrites(mapOf(
            MailBacklogFields.MARK_READ_SEEN to FieldValue.Int64(approvedAt),
            MailBacklogFields.MARKED_READ to FieldValue.Int64(500),
        ))
        c.syncNow()
        assertEquals("500 so far · ${MailMarkReadRules.KEEPS_UNREAD}", c.needsYouStack.value.working.single().meta)

        // Finished: "Marked read" for six hours, then the section goes.
        serverWrites(mapOf(
            MailBacklogFields.MARKED_READ to FieldValue.Int64(580),
            MailBacklogFields.MARK_READ_FINISHED_AT to FieldValue.Int64(now),
        ))
        c.syncNow()
        assertEquals("Marked read · Personal Gmail", c.needsYouStack.value.working.single().title)
        now += MailWorkingRules.SHOW_DONE_MS + 60_000L
        c.syncNow()
        assertTrue(c.needsYouStack.value.working.isEmpty())
    }
}
