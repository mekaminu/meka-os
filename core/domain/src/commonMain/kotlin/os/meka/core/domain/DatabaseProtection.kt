package os.meka.core.domain

/** What the replica's database file on disk is, read from its first bytes (ADR-002). */
enum class DatabaseFile {
    /** No file yet (a new install). */
    MISSING,

    /** A plain SQLite file ("SQLite format 3" header): written before the Mac encrypted its database. */
    PLAIN,

    /** Not a plain SQLite header: a SQLCipher file. */
    SEALED,
}

/** The database key, as the app found it on launch. */
enum class DatabaseKeyState {
    /** Kept from an earlier launch. */
    KEPT,

    /** Made on this launch (no key existed yet), already written and read back. */
    MADE,

    /** None to use: no Secure Enclave on this Mac, the sealed key file wouldn't open, or SQLCipher isn't linked. */
    UNAVAILABLE,
}

/**
 * What to do before opening the database. [setAside]: move the existing file to one side (renamed, never deleted)
 * because nothing on this launch can open it. [encryptFirst]: copy the plain file into an encrypted one, check the
 * copy, then swap. [keyed]: open with the key (SQLCipher); otherwise open plain.
 */
data class DatabasePlan(val setAside: Boolean, val encryptFirst: Boolean, val keyed: Boolean)

/** Why the Mac's database is or isn't encrypted, for the Your data line. */
enum class DatabaseProtection {
    /** SQLCipher with a key only this Mac's Secure Enclave can unseal. */
    ENCRYPTED,

    /** No Secure Enclave key (an Intel Mac without a T2 chip, or the enclave refused): FileVault only. */
    NO_ENCLAVE,

    /** Encrypting the existing database didn't finish this time; the plain file was kept and the next launch tries again. */
    NOT_YET,
}

/**
 * Mac database encryption (build plan M1, slice 2b; ADR-002 spike S6). Non-AI, pure: the app reads the file and the
 * key, asks [plan] what to do, and does it. The rules never lose data: a plain database is only replaced once its
 * encrypted copy has been checked, and a file that can't be opened is renamed aside, never deleted.
 */
object DatabaseProtectionRules {
    /** The first 16 bytes of every plain SQLite 3 file. */
    val PLAIN_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

    /** What the file is from its first bytes (null or empty: no file). A file shorter than the header is plain (SQLite writes nothing until the first page). */
    fun fileOf(head: ByteArray?): DatabaseFile = when {
        head == null -> DatabaseFile.MISSING
        head.isEmpty() -> DatabaseFile.PLAIN
        head.size < PLAIN_HEADER.size -> DatabaseFile.PLAIN
        head.copyOfRange(0, PLAIN_HEADER.size).contentEquals(PLAIN_HEADER) -> DatabaseFile.PLAIN
        else -> DatabaseFile.SEALED
    }

    fun plan(file: DatabaseFile, key: DatabaseKeyState): DatabasePlan = when (key) {
        // Without a key a sealed file can't be opened: set it aside and start plain (the server holds what was synced).
        DatabaseKeyState.UNAVAILABLE -> DatabasePlan(setAside = file == DatabaseFile.SEALED, encryptFirst = false, keyed = false)
        DatabaseKeyState.KEPT -> DatabasePlan(setAside = false, encryptFirst = file == DatabaseFile.PLAIN, keyed = true)
        // A sealed file next to a key that didn't exist until now was sealed with a key that is gone.
        DatabaseKeyState.MADE -> DatabasePlan(setAside = file == DatabaseFile.SEALED, encryptFirst = file == DatabaseFile.PLAIN, keyed = true)
    }

    /** The name a file set aside gets: "meka.db" → "meka-set-aside-20261008-1606.db" (UTC [stamp], "yyyyMMdd-HHmm"). */
    fun setAsideName(name: String, stamp: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}-set-aside-$stamp${name.substring(dot)}" else "$name-set-aside-$stamp"
    }

    /** Your data's line about the database on this Mac. */
    fun macLine(protection: DatabaseProtection): String = when (protection) {
        DatabaseProtection.ENCRYPTED ->
            "MEKA's database on this Mac is encrypted (SQLCipher, AES-256) with a key only this Mac's Secure Enclave can unseal."
        DatabaseProtection.NO_ENCLAVE ->
            "MEKA's database on this Mac isn't encrypted by MEKA: this Mac has no Secure Enclave key for it. FileVault protects it while the Mac is off."
        DatabaseProtection.NOT_YET ->
            "MEKA's database on this Mac isn't encrypted yet: it didn't finish this time and MEKA tries again next launch. FileVault protects it meanwhile."
    }

    /** Your data's line about the database on the phone (always encrypted, ADR-002). */
    const val PHONE_LINE: String =
        "MEKA's database on this phone is encrypted (SQLCipher, AES-256) with a key Android's Keystore holds."

    /** The one-time note after a file was set aside. */
    fun setAsideLine(asideName: String): String =
        "MEKA couldn't open its old database on this Mac, so it kept it aside as $asideName and is syncing afresh."
}
