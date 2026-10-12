package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DocumentRules
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Vault through the facade (slice 1): typed on the Fold, on the Mac too, and its expiry a heads-up on the plan. */
class DocumentsFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()
    private val day = 24 * 3_600_000L

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aDocumentTypedOnTheFoldShowsOnTheMacAndItsRenewalComesAsAHeadsUp() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(DocumentRules.EMPTY_LINE, fold.documentsView.value.summary)
        assertTrue(fold.addDocument("Passport 31 Feb 2029").refused)
        // A railcard running out in 15 days: its two-week window opens tomorrow.
        val added = fold.addDocument("Railcard 25 Oct 2026")
        assertFalse(added.refused)
        assertEquals("Added Railcard · expires Sun 25 Oct 2026 · MEKA reminds you from Sun 11 Oct 2026", added.line)
        assertEquals("1 document · next to run out: Railcard, Oct 2026", fold.documentsView.value.summary)

        fold.syncNow(); mac.syncNow()
        val row = mac.documentsView.value.rows.single()
        assertEquals("Railcard", row.title)
        assertEquals("Expires in 2 weeks", row.status)
        assertFalse(row.statusLit)

        // Sunday 10:00: the window is open, lit, and a heads-up opens the Vault.
        now += day
        mac.tick()
        assertEquals("Renew now · expires in 2 weeks", mac.documentsView.value.rows.single().status)
        assertEquals("1 document · 1 to renew", mac.documentsView.value.summary)
        val n = mac.noticesNow().single { it.source == NoticeSource.DOCUMENTS }
        assertEquals("Railcard expires Sun 25 Oct 2026", n.title)
        assertEquals(NoticeTarget.VAULT, n.target)

        // Removed on the Mac: gone on the Fold.
        assertTrue(mac.removeDocument(row.id))
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertTrue(fold.documentsView.value.isEmpty)
    }
}
