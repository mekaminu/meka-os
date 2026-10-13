package os.meka.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

/**
 * Opens the replica database on macOS. [keyHex] is the 64-hex-char key from the Mac's sealed key files.
 *
 * ADR-002 (spike S6 passed, slice 2b): the key is applied with `PRAGMA key` ([keyStatement]) when [encrypted]. The app checks that
 * SQLCipher is linked (the S6 probe) before passing a key, and encrypts an older plain database first. Never pass a
 * key to a non-SQLCipher build: it would be silently ignored.
 */
object MacDatabase {
    /** [directory] must be app-specific (Application Support/os.meka.mac); tests pass a temporary directory. */
    fun open(keyHex: String?, encrypted: Boolean, directory: String, name: String = "meka.db"): SqlDriver {
        val schema = MekaDatabase.Schema
        val key = if (encrypted && keyHex != null) keyStatement(keyHex) else null
        return NativeSqliteDriver(
            schema = schema,
            name = name,
            onConfiguration = { config ->
                val lifecycle = config.lifecycleConfig
                config.copy(
                    extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true, basePath = directory),
                    // The key goes on every connection as its very first statement, written exactly as the Mac app
                    // writes it (DatabaseEncryption.swift), not through SQLiter's Encryption config: SQLiter's own
                    // keying sealed files the app's `PRAGMA key` couldn't read (nightly ci run 483: "hmac check
                    // failed for pgno=1"), so one statement, one key derivation, on both sides.
                    lifecycleConfig = if (key == null) {
                        lifecycle
                    } else {
                        DatabaseConfiguration.Lifecycle(
                            onCreateConnection = { conn ->
                                conn.rawExecSql(key)
                                lifecycle.onCreateConnection(conn)
                            },
                            onCloseConnection = lifecycle.onCloseConnection,
                        )
                    },
                )
            },
        )
    }

    /**
     * The one keying statement both sides use: SQLCipher's raw key (`x'…'`, 64 hex chars = 32 bytes, no passphrase
     * derivation). The Swift side's `PRAGMA key` and `ATTACH … KEY` use the same literal.
     */
    fun keyStatement(keyHex: String): String {
        require(keyHex.length == 64 && keyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "database key must be 64 hex characters"
        }
        return "PRAGMA key = \"x'$keyHex'\";"
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
