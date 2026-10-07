package os.meka.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

/**
 * Opens the replica database on macOS. [keyHex] is the 64-hex-char key from the Keychain.
 *
 * ADR-002 / spike S6: the key is applied with `PRAGMA key` only once SQLCipher is linked instead of the system
 * SQLite. Until then [encrypted] must be false and the app relies on FileVault + CryptoKit column encryption,
 * and says so in Settings → Security. Never pass a key to a non-SQLCipher build: it would be silently ignored.
 */
object MacDatabase {
    /** [directory] must be app-specific (Application Support/os.meka.mac); tests pass a temporary directory. */
    fun open(keyHex: String?, encrypted: Boolean, directory: String, name: String = "meka.db"): SqlDriver {
        val schema = MekaDatabase.Schema
        return NativeSqliteDriver(
            schema = schema,
            name = name,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true, basePath = directory),
                    encryptionConfig = if (encrypted && keyHex != null) {
                        DatabaseConfiguration.Encryption(key = "x'$keyHex'")
                    } else {
                        config.encryptionConfig
                    },
                )
            },
        )
    }

    /**
     * Spike S6: the SQLCipher version this build is linked against ("4.19.0 community"), or null on the system SQLite.
     * Opens (and creates) an unkeyed database [name] in [directory]; the caller deletes it.
     */
    fun cipherVersion(directory: String, name: String): String? {
        val driver = open(keyHex = null, encrypted = false, directory = directory, name = name)
        try {
            return driver.executeQuery(
                null,
                "PRAGMA cipher_version",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                0,
            ).value
        } finally {
            driver.close()
        }
    }

    /**
     * Spike S6: whether database [name] in [directory] opens and reads with [keyHex] (null: no key), creating it if
     * it doesn't exist. Only call with a key once [cipherVersion] is non-null: the system SQLite ignores keys.
     */
    fun opens(keyHex: String?, directory: String, name: String): Boolean {
        val driver = try {
            open(keyHex = keyHex, encrypted = keyHex != null, directory = directory, name = name)
        } catch (e: Throwable) {
            return false
        }
        return try {
            driver.executeQuery(
                null,
                "SELECT count(*) FROM sqlite_master",
                { cursor -> QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
                0,
            ).value
        } catch (e: Throwable) {
            false
        } finally {
            try {
                driver.close()
            } catch (e: Throwable) {
                // A connection that never opened has nothing to close.
            }
        }
    }
}
