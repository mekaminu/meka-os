# ADR-002: Local database and encryption at rest

**Status:** Accepted. The Apple SQLCipher linkage is tracked as spike S6.

## Context
The device database is the source of truth for the user's immediate experience (ADR-003). It will hold the family schedule, messages-derived commitments and eventually documents, so it must be encrypted at rest with keys in Keystore/Keychain. It must be queryable from shared Kotlin code on Android and macOS.

## Options
- **SQLDelight + SQLCipher.** Typed SQL, generated in shared code. Mature on Android.
- **Room KMP.** Viable now, but its multiplatform SQLCipher story is no better and it is more annotation-heavy.
- **Realm / Couchbase Lite.** They bring their own sync model, which conflicts with ADR-003. Realm's device sync is deprecated.
- **Plain SQLite + field-level encryption.** Leaks structure and needs custom crypto in every column.

## Decision
- **SQLDelight 2.4.0** is the query layer, and `.sq` schemas live in `core/data`.
- **Android:** `net.zetetic:sqlcipher-android` 4.19.x with `androidx.sqlite` 2.7.0 via SQLDelight's AndroidSqliteDriver and a `SupportOpenHelperFactory`.
  - The database key is a 256-bit random key. It is wrapped by an Android Keystore AES-GCM key, which requires user authentication within a configurable validity window.
- **macOS:** SQLDelight's NativeSqliteDriver (touchlab SQLiter 1.4.0) linked against SQLCipher instead of the system `libsqlite3`.
  - The key is stored in the Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` and is gated by LocalAuthentication for the app lock.
- **Verified risk:** there is no officially packaged path for SQLCipher with Kotlin/Native on Apple. SQLiter links `-lsqlite3`, and the system SQLite must not win at link time.
  - Spike S6 proves the linkage. It must show `PRAGMA cipher_version` returning a value inside the Mac app, and an unreadable database file without the key.
  - **Fallback if S6 fails:** the Mac database uses the system SQLite with CryptoKit AES-GCM encryption of sensitive columns (keyed from the Keychain), and relies on FileVault for the rest. The Mac UI states this honestly.
- **Tests** use an in-memory `ReplicaStore` implementation. The SQLDelight implementation must pass the same contract test suite (`ReplicaStoreContract`).

## Consequences
- The migration strategy is SQLDelight's `.sqm` files, verified in CI by `verifyMigrations`.
- A database key is lost if the Keystore is wiped. Recovery comes from the cloud op-log (ADR-003) and the owner's recovery key (ADR-005). The local DB is always rebuildable.

## Revisit if
- SQLCipher on Kotlin/Native gets an official package, or the Room KMP story materially improves.
