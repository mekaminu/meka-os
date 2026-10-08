package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseProtectionTest {
    private val plainHead = "SQLite format 3\u0000".encodeToByteArray() + ByteArray(84)

    @Test
    fun theFileIsReadFromItsHeader() {
        assertEquals(DatabaseFile.MISSING, DatabaseProtectionRules.fileOf(null))
        assertEquals(DatabaseFile.PLAIN, DatabaseProtectionRules.fileOf(ByteArray(0)))
        assertEquals(DatabaseFile.PLAIN, DatabaseProtectionRules.fileOf(plainHead))
        assertEquals(DatabaseFile.PLAIN, DatabaseProtectionRules.fileOf("SQLite".encodeToByteArray()))
        assertEquals(DatabaseFile.SEALED, DatabaseProtectionRules.fileOf(ByteArray(100) { (it * 37 + 11).toByte() }))
    }

    @Test
    fun aPlainDatabaseIsEncryptedOnceAndThenOpenedWithItsKey() {
        assertEquals(DatabasePlan(setAside = false, encryptFirst = true, keyed = true), DatabaseProtectionRules.plan(DatabaseFile.PLAIN, DatabaseKeyState.MADE))
        assertEquals(DatabasePlan(setAside = false, encryptFirst = true, keyed = true), DatabaseProtectionRules.plan(DatabaseFile.PLAIN, DatabaseKeyState.KEPT))
        assertEquals(DatabasePlan(setAside = false, encryptFirst = false, keyed = true), DatabaseProtectionRules.plan(DatabaseFile.SEALED, DatabaseKeyState.KEPT))
    }

    @Test
    fun aNewInstallStartsEncrypted() {
        assertEquals(DatabasePlan(setAside = false, encryptFirst = false, keyed = true), DatabaseProtectionRules.plan(DatabaseFile.MISSING, DatabaseKeyState.MADE))
        assertEquals(DatabasePlan(setAside = false, encryptFirst = false, keyed = true), DatabaseProtectionRules.plan(DatabaseFile.MISSING, DatabaseKeyState.KEPT))
    }

    @Test
    fun withoutAKeyNothingIsEncryptedAndASealedFileIsSetAsideNeverDeleted() {
        assertEquals(DatabasePlan(setAside = false, encryptFirst = false, keyed = false), DatabaseProtectionRules.plan(DatabaseFile.PLAIN, DatabaseKeyState.UNAVAILABLE))
        assertEquals(DatabasePlan(setAside = false, encryptFirst = false, keyed = false), DatabaseProtectionRules.plan(DatabaseFile.MISSING, DatabaseKeyState.UNAVAILABLE))
        assertEquals(DatabasePlan(setAside = true, encryptFirst = false, keyed = false), DatabaseProtectionRules.plan(DatabaseFile.SEALED, DatabaseKeyState.UNAVAILABLE))
        // A sealed file beside a key made just now was sealed with a key that's gone.
        assertEquals(DatabasePlan(setAside = true, encryptFirst = false, keyed = true), DatabaseProtectionRules.plan(DatabaseFile.SEALED, DatabaseKeyState.MADE))
    }

    @Test
    fun setAsideNamesKeepTheExtension() {
        assertEquals("meka-set-aside-20261008-1606.db", DatabaseProtectionRules.setAsideName("meka.db", "20261008-1606"))
        assertEquals("meka-set-aside-20261008-1606", DatabaseProtectionRules.setAsideName("meka", "20261008-1606"))
        assertTrue(DatabaseProtectionRules.setAsideLine("meka-set-aside-20261008-1606.db").contains("kept it aside as meka-set-aside-20261008-1606.db"))
    }

    @Test
    fun yourDataSaysHowTheDatabaseIsProtected() {
        assertTrue(DatabaseProtectionRules.macLine(DatabaseProtection.ENCRYPTED).contains("encrypted (SQLCipher, AES-256)"))
        assertTrue(DatabaseProtectionRules.macLine(DatabaseProtection.NO_ENCLAVE).contains("FileVault"))
        assertTrue(DatabaseProtectionRules.macLine(DatabaseProtection.NOT_YET).contains("tries again next launch"))
        assertTrue(DatabaseProtectionRules.PHONE_LINE.contains("Keystore"))
    }
}
