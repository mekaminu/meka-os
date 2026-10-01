package os.meka.core.data

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the encrypted replica database (ADR-002). [key] is the 32-byte database key, unwrapped by the app from an
 * Android Keystore AES-GCM key. The key bytes are not retained by this function.
 */
object AndroidDatabase {
    init {
        System.loadLibrary("sqlcipher")
    }

    fun open(context: Context, key: ByteArray, name: String = "meka.db"): SqlDriver {
        require(key.size == 32) { "database key must be 32 bytes" }
        // SupportOpenHelperFactory keeps a reference to the array and the DB opens lazily (and may reopen), so it
        // gets its own copy that lives as long as the driver. SQLCipher holds the key in memory while open anyway.
        val factory = SupportOpenHelperFactory(key.copyOf())
        val driver = AndroidSqliteDriver(
            schema = MekaDatabase.Schema,
            context = context,
            name = name,
            factory = factory,
        )
        // Open now: a wrong key must fail at startup, not on the first user action.
        driver.executeQuery(null, "PRAGMA user_version", { app.cash.sqldelight.db.QueryResult.Value(Unit) }, 0)
        return driver
    }
}
