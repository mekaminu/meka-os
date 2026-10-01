package os.meka.core.data

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
}
