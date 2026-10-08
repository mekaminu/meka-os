# ADR-002: Local database and encryption at rest

**Status:** Accepted. Spike S6 (the Apple SQLCipher linkage) passed on 2026-10-07; the Mac database is encrypted since 2026-10-08 (addendum below).

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

## Addendum (2026-10-08, calendar editing slice 2h): device-local values
- `ReplicaStore.localValue/setLocalValue` keep small values that belong to one device and must never sync (first use: a calendar follow-up waiting on the device where Meka made the change). The SQL store keeps them in the existing `replica_meta` table under a `local:` prefix, apart from the pull cursor and clock; no schema change, no migration (one new `metaDelete` query). They never enter the op log and are never pushed.
- They are conveniences only: a rebuilt database starts without them, and anything unreadable decodes to nothing. `ReplicaStoreContract` covers round trip, replace, remove, rollback and separation from the cursor.

## Addendum (2026-10-08, Mac database encryption slice 2b): the Mac database is encrypted
- Spike S6 passed (nightly run 37686245413): the Mac app links Zetetic's `SQLCipher.swift` 4.19.0 and MekaKit's `sqlite3_*` calls bind to it. The CryptoKit column fallback is not needed.
- **Key:** a 256-bit random key, kept as a Secure Enclave-sealed file (`database_key.sealed`, `SealedKeyFiles`), not a Keychain item (unsigned rebuilds made every Keychain read prompt). It is written and read back before anything is encrypted with it. A Mac without sealed files (no Secure Enclave) stays unencrypted and Your data says FileVault is the protection.
- **Migration, once:** before the core opens `meka.db`, the app checks SQLCipher on throwaway files, then `DatabaseProtectionRules.plan` (core, pure) decides. A plain file is copied with `sqlcipher_export` into `meka.db.encrypting` (`user_version` carried over), checked (encrypted header, the same rows in every table, opens through the core's own driver with the key), swapped in (the plain file waits as `meka.db.plain-old` with its WAL and SHM), opened again, and only then is the plain file removed. A failure at any step leaves the plain file in place and the next launch tries again; a swap interrupted by a quit is put back on the next launch.
- A sealed file this launch can't open (its key is gone) is renamed aside (`meka-set-aside-<UTC stamp>.db`), never deleted, and the Mac starts afresh and re-syncs from the server (ADR-003); a one-time line says so.
