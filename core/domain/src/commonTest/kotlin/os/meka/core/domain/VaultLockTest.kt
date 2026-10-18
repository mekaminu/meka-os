package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Vault, slice 4: copies behind the fingerprint — how long an unlock holds, the note, and a locked open. */
class VaultLockTest {
    private val t = 1_791_622_800_000L

    @Test
    fun anUnlockHoldsUnderFiveMinutesAndNeverBackwards() {
        assertFalse(VaultLockRules.unlocked(0L, t))
        assertTrue(VaultLockRules.unlocked(t, t))
        assertTrue(VaultLockRules.unlocked(t, t + VaultLockRules.WINDOW_MS - 1))
        assertFalse(VaultLockRules.unlocked(t, t + VaultLockRules.WINDOW_MS))
        // A clock that went backwards doesn't keep it open.
        assertFalse(VaultLockRules.unlocked(t, t - 1))
    }

    @Test
    fun theNoteShowsOnceACopyIsKept() {
        val bare = DocumentRow("d1", "Passport", "Passport · expires Sat 3 Mar 2029", "Expires in 2 years", false, "Passport")
        assertNull(VaultLockRules.note(listOf(bare)))
        assertNull(VaultLockRules.note(emptyList()))
        val kept = bare.copy(copyLine = "Photo · 412 KB · encrypted · added on this Fold, Sun 18 Oct")
        assertEquals(VaultLockRules.NOTE, VaultLockRules.note(listOf(bare, kept)))
    }

    @Test
    fun aLockedOpenCarriesNoBytes() {
        val o = VaultFileOpened.locked("Passport")
        assertTrue(o.locked)
        assertNull(o.base64)
        assertEquals(VaultLockRules.LOCKED, o.problem)
        assertFalse(VaultFileOpened.problem("Passport", VaultFileRules.OFFLINE).locked)
    }
}
