package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailTidiedEmail
import os.meka.core.domain.MailTidiedRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.VaultReceiptRules
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

/** Receipts → Vault: Keep in the Vault on a tidied receipt's preview, on both devices, and Take it out. */
class VaultReceiptFacadeTest {
    private var now = 1_792_225_800_000L // Wed 14 Oct 2026, 09:30 London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvvr${k++}", "hh", EntityTypes.MAIL_TIDY, id, f, v, clock.now(), emptyList(), "server"))
    }

    @Test
    fun aKeptReceiptIsAVaultDocumentOnBothDevicesAndTakingItOutUndoesIt() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val receipt = MailTidiedEmail(
            MailTidiedRules.key("acc", "m1"), MailTidy.RECEIPT, null, now - 86_400_000L, "Currys", "currys.co.uk",
            "Your Currys order", 49_900, preview = "Order total: £499.00",
        )
        val promo = receipt.copy(key = MailTidiedRules.key("acc", "m2"), tidy = MailTidy.ARCHIVE, subject = "Sale now on", pence = null)
        val emails = listOf(receipt, promo)
        serverWrite("mt1", MailAutopilotRules.batchFields(now - 3_600_000L, "Personal", emails.groupingBy { it.tidy }.eachCount()) + MailTidiedRules.fields(emails))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        assertNull(fold.archivedEmail("mt1", promo.key)!!.vault)
        assertEquals(VaultReceiptRules.KEEP, fold.archivedEmail("mt1", receipt.key)!!.vault!!.action)
        val kept = assertNotNull(fold.keepReceiptInVault("mt1", receipt.key))
        assertTrue(kept.line.startsWith("Kept “Currys receipt” in the Vault · warranty until"), kept.line)
        assertTrue(fold.keepReceiptInVault("mt1", receipt.key)!!.refused)
        assertNull(fold.keepReceiptInVault("mt1", promo.key))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        val row = mac.documentsView.value.rows.single()
        assertEquals("Currys receipt", row.title)
        assertEquals("Receipt · £499.00 · bought Tue 13 Oct 2026 · Personal mail", row.line)
        assertTrue(row.fromMail)
        assertTrue(mac.archivedEmail("mt1", receipt.key)!!.vault!!.kept)
        // Open the email: the account list isn't known offline here, so it says why rather than a link.
        val open = mac.vaultReceiptLink(row.id)
        assertNull(open.link)
        assertNotNull(open.problem)

        assertEquals("Took “Currys receipt” out of the Vault", mac.takeReceiptOutOfVault(receipt.key))
        assertNull(mac.takeReceiptOutOfVault(receipt.key))
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertTrue(fold.documentsView.value.rows.isEmpty())
        assertEquals(VaultReceiptRules.KEEP, fold.archivedEmail("mt1", receipt.key)!!.vault!!.action)
        assertEquals(VaultReceiptRules.GONE, fold.vaultReceiptLink("nothing").problem)
    }
}
