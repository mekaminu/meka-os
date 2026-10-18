package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Vault, slice 2b: a document's encrypted copy — the rules, the one synced field, and the row's words. */
class VaultFileTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val sun = CivilDate.toEpochDay(2026, 10, 18)
    private val ref = VaultFileRef("0123456789abcdef0123456789abcdef", VaultFileRules.JPEG, 412_300, "4f2a91d0", cal.toEpochMs(sun, 600), "Fold")

    @Test
    fun onlyPhotosAndPdfsUpToTheLimit() {
        assertEquals(VaultFileRules.JPEG, VaultFileRules.typeOf("image/jpeg"))
        assertEquals(VaultFileRules.JPEG, VaultFileRules.typeOf(null, "Scan 3.JPG"))
        assertEquals(VaultFileRules.PNG, VaultFileRules.typeOf("image/png; q=1"))
        assertEquals(VaultFileRules.PDF, VaultFileRules.typeOf("application/octet-stream", "policy.pdf"))
        assertNull(VaultFileRules.typeOf("image/heic", "IMG.heic"))
        assertEquals(VaultFileRules.NOT_TYPE, VaultFileRules.refusal(null, 10))
        assertEquals(VaultFileRules.COULDNT_READ, VaultFileRules.refusal(VaultFileRules.PDF, 0))
        assertEquals(VaultFileRules.TOO_BIG, VaultFileRules.refusal(VaultFileRules.PDF, VaultFileRules.MAX_FILE_BYTES + 1))
        assertNull(VaultFileRules.refusal(VaultFileRules.PDF, VaultFileRules.MAX_FILE_BYTES))
        assertTrue(VaultFileRules.validId(ref.id))
        assertFalse(VaultFileRules.validId("short"))
        assertFalse(VaultFileRules.validId("../../" + "a".repeat(20)))
    }

    @Test
    fun theBoxNamesItsCopySoAMovedOneIsRefused() {
        val file = byteArrayOf(1, 2, 3, 4)
        val framed = VaultFileRules.frame(ref.id, file)
        assertTrue(VaultFileRules.unframe(ref.id, framed)!!.contentEquals(file))
        assertNull(VaultFileRules.unframe("ffffffffffffffffffffffffffffffff", framed))
        assertNull(VaultFileRules.unframe(ref.id, byteArrayOf(1)))
        val opened = VaultFileRules.open(ref, "4f2a91d0") { framed }
        assertTrue(opened is VaultFileOpen.Opened && opened.bytes.contentEquals(file))
        assertEquals(VaultFileOpen.Refused(VaultFileRules.NO_KEY), VaultFileRules.open(ref, null) { framed })
        assertEquals(VaultFileOpen.Refused(VaultFileRules.OTHER_KEY), VaultFileRules.open(ref, "99999999") { framed })
        assertEquals(VaultFileOpen.Refused(VaultFileRules.CHANGED), VaultFileRules.open(ref, "4f2a91d0") { null })
        assertEquals(
            VaultFileOpen.Refused(VaultFileRules.CHANGED),
            VaultFileRules.open(ref, "4f2a91d0") { VaultFileRules.frame("ffffffffffffffffffffffffffffffff", file) },
        )
    }

    @Test
    fun oneSyncedFieldAndTheRowSaysWhatIsKept() {
        assertEquals(ref, VaultFileRules.decode(VaultFileRules.encode(ref)))
        assertNull(VaultFileRules.decode(null))
        assertNull(VaultFileRules.decode("2|x"))
        assertEquals("412 KB", VaultFileRules.sizeWords(412_300))
        assertEquals("1.4 MB", VaultFileRules.sizeWords(1_390_000))
        assertEquals("1 KB", VaultFileRules.sizeWords(10))
        assertEquals("Photo · 412 KB · encrypted · added on this Fold, Sun 18 Oct", VaultFileRules.copyLine(ref, cal, "Fold"))
        assertEquals("PDF · 412 KB · encrypted · added on the Fold, Sun 18 Oct", VaultFileRules.copyLine(ref.copy(type = VaultFileRules.PDF), cal, "Mac"))
        assertEquals(
            "Copy added · photo, 412 KB, locked with your Vault key · only your devices can open it",
            VaultFileRules.addedLine(ref, uploaded = true, replaced = false),
        )
        assertEquals(
            "Copy replaced · photo, 412 KB, locked with your Vault key · it goes up when this Fold is next online",
            VaultFileRules.addedLine(ref, uploaded = false, replaced = true),
        )
        assertEquals("This copy isn't on Navilife's server yet: it goes up when the Mac is next online.", VaultFileRules.notUploaded("Mac"))
        assertEquals(listOf(ref.id), VaultFileRules.decodeIds(VaultFileRules.encodeIds(listOf(ref.id, ref.id, "bad"))))
        assertNull(VaultFileRules.encodeIds(emptyList()))
    }

    @Test
    fun theCopySyncsOnTheDocumentAndTheRowShowsIt() {
        val world = SyncWorld()
        world.clock.nowMs = cal.toEpochMs(sun, 600)
        val fold = world.device("android")
        val mac = world.device("mac")
        var n = 0
        val dFold = Documents(fold.replica, { "d${n++}" }, { world.clock.nowMs }, cal)
        val dMac = Documents(mac.replica, { "d${n++}" }, { world.clock.nowMs }, cal)
        val id = dFold.add("Passport expires 3 Mar 2029").id!!
        assertTrue(dFold.setFile(id, ref))
        assertFalse(dFold.setFile("nope", ref))
        fold.sync(); mac.sync()
        val row = dMac.view("Mac").rows.single()
        assertEquals("Photo · 412 KB · encrypted · added on the Fold, Sun 18 Oct", row.copyLine)
        assertFalse(row.copyIsPdf)
        assertTrue(row.hasCopy)
        assertTrue(row.spoken.endsWith("Copy kept: Photo · 412 KB · encrypted · added on the Fold, Sun 18 Oct"))
        // A new date for the document keeps its copy; removing the copy keeps the document.
        dMac.add("Passport expires 4 Mar 2029")
        mac.sync(); fold.sync()
        assertEquals(ref, dFold.item(id)!!.file)
        assertTrue(dFold.setFile(id, null))
        fold.sync(); mac.sync()
        assertFalse(dMac.view().rows.single().hasCopy)
        assertNull(dMac.item(id)!!.file)
    }
}
