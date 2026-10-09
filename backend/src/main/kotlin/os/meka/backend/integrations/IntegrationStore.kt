package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.backend.PostgresOpStore
import java.sql.Connection
import java.sql.Timestamp

data class PendingConnect(
    val householdId: String, val provider: String, val codeVerifier: String, val createdAtMs: Long,
    /** The owner tapped Allow editing (or reconnected an account that could edit): the write scope was asked for. */
    val editing: Boolean = false,
)

data class AccountRow(
    val id: String,
    val householdId: String,
    val provider: String,
    val email: String,
    val refreshTokenEnc: ByteArray,
    val status: String,
    val lastSyncAtMs: Long?,
    /** The provider granted the write scope and the owner hasn't stopped editing (calendar editing). */
    val canEdit: Boolean = false,
    /** When the owner last signed in (Reliability first, item 2); null for accounts signed in before it was recorded. */
    val grantedAtMs: Long? = null,
)

/** What the server last wrote for one mirrored event: per field, the op id and an encoded value. */
data class MirrorRow(
    val entityId: String,
    val accountId: String,
    val startMs: Long,
    val removed: Boolean,
    val fieldOps: Map<String, Pair<String, String>>,
    val endMs: Long = startMs,
    val allDay: Boolean = false,
    /** The provider's id for it (`<calendar>/<event>`), so an edit can find the real event (calendar editing). */
    val remoteId: String? = null,
)

/** Persistence for connected accounts and the event mirror. Runs inside the op store's transaction. */
interface IntegrationStore {
    fun <T> transaction(block: () -> T): T
    fun saveState(state: String, p: PendingConnect)
    /** Removes and returns a pending handshake (single use). */
    fun takeState(state: String): PendingConnect?
    /**
     * Inserts or updates by (household, provider, email); returns the account id. [canEdit] is what this sign-in
     * granted, so a read-only reconnect also turns editing off.
     */
    fun upsertAccount(
        householdId: String, provider: String, email: String, refreshTokenEnc: ByteArray, canEdit: Boolean = false,
        /** A sign-in's time (the OAuth consent); null (feeds) leaves what was recorded. */
        grantedAtMs: Long? = null,
        newId: () -> String,
    ): String
    /** Stop editing: the account goes back to read-only. Returns false when there's no such account. */
    fun stopEditing(householdId: String, provider: String, email: String): Boolean
    fun account(id: String): AccountRow?
    fun accounts(householdId: String): List<AccountRow>
    fun syncableAccounts(): List<AccountRow>
    /** Households with at least one enrolled device. */
    fun households(): List<String>
    fun updateRefreshToken(id: String, enc: ByteArray)
    fun markSynced(id: String, atMs: Long)
    fun markError(id: String, status: String, error: String)
    /** Serialises syncs of one account across processes for the rest of the current transaction. */
    fun lockAccount(id: String)
    fun mirror(householdId: String, accountId: String): Map<String, MirrorRow>
    fun putMirror(householdId: String, row: MirrorRow)
}

internal object FieldOpsJson {
    fun encode(m: Map<String, Pair<String, String>>): String =
        JsonObject(m.mapValues { (_, v) -> JsonArray(listOf(JsonPrimitive(v.first), JsonPrimitive(v.second))) }).toString()

    fun decode(s: String): Map<String, Pair<String, String>> =
        Json.parseToJsonElement(s).jsonObject.mapValues { (_, v) ->
            val a = v.jsonArray; a[0].jsonPrimitive.content to a[1].jsonPrimitive.content
        }
}

class InMemoryIntegrationStore(private val knownHouseholds: List<String> = emptyList()) : IntegrationStore {
    private val states = HashMap<String, PendingConnect>()
    private val accounts = LinkedHashMap<String, AccountRow>()
    private val mirrors = HashMap<Pair<String, String>, MirrorRow>()

    @Synchronized override fun <T> transaction(block: () -> T): T = block()
    @Synchronized override fun saveState(state: String, p: PendingConnect) { states[state] = p }
    @Synchronized override fun takeState(state: String) = states.remove(state)
    @Synchronized override fun upsertAccount(householdId: String, provider: String, email: String, refreshTokenEnc: ByteArray, canEdit: Boolean, grantedAtMs: Long?, newId: () -> String): String {
        val existing = accounts.values.firstOrNull { it.householdId == householdId && it.provider == provider && it.email == email }
        val id = existing?.id ?: newId()
        accounts[id] = AccountRow(id, householdId, provider, email, refreshTokenEnc, "ok", existing?.lastSyncAtMs, canEdit, grantedAtMs ?: existing?.grantedAtMs)
        return id
    }
    @Synchronized override fun stopEditing(householdId: String, provider: String, email: String): Boolean {
        val a = accounts.values.firstOrNull { it.householdId == householdId && it.provider == provider && it.email == email } ?: return false
        accounts[a.id] = a.copy(canEdit = false)
        return true
    }
    @Synchronized override fun account(id: String) = accounts[id]
    @Synchronized override fun accounts(householdId: String) = accounts.values.filter { it.householdId == householdId }
    @Synchronized override fun syncableAccounts() = accounts.values.filter { it.status != "needs_reconnect" }
    override fun households() = knownHouseholds
    @Synchronized override fun updateRefreshToken(id: String, enc: ByteArray) { accounts[id]?.let { accounts[id] = it.copy(refreshTokenEnc = enc) } }
    @Synchronized override fun markSynced(id: String, atMs: Long) { accounts[id]?.let { accounts[id] = it.copy(status = "ok", lastSyncAtMs = atMs) } }
    @Synchronized override fun markError(id: String, status: String, error: String) { accounts[id]?.let { accounts[id] = it.copy(status = status) } }
    override fun lockAccount(id: String) = Unit
    @Synchronized override fun mirror(householdId: String, accountId: String) =
        mirrors.filter { (k, v) -> k.first == householdId && v.accountId == accountId }.values.associateBy { it.entityId }
    @Synchronized override fun putMirror(householdId: String, row: MirrorRow) { mirrors[householdId to row.entityId] = row }
}

/** Postgres implementation sharing [PostgresOpStore]'s transaction, so mirror state and ops commit together. */
class PostgresIntegrationStore(private val ops: PostgresOpStore) : IntegrationStore {
    override fun <T> transaction(block: () -> T): T = ops.transaction(block)
    private fun <T> c(block: (Connection) -> T): T = ops.withConnection(block)

    override fun saveState(state: String, p: PendingConnect) = c { c ->
        c.prepareStatement("DELETE FROM oauth_state WHERE created_at < now() - interval '1 hour'").use { it.executeUpdate() }
        c.prepareStatement("INSERT INTO oauth_state(state, household_id, provider, code_verifier, editing) VALUES (?,?,?,?,?)").use {
            it.setString(1, state); it.setString(2, p.householdId); it.setString(3, p.provider); it.setString(4, p.codeVerifier)
            it.setBoolean(5, p.editing)
            it.executeUpdate()
        }
        Unit
    }

    override fun takeState(state: String): PendingConnect? = c { c ->
        c.prepareStatement("DELETE FROM oauth_state WHERE state = ? RETURNING household_id, provider, code_verifier, created_at, editing").use { st ->
            st.setString(1, state)
            st.executeQuery().use { rs ->
                if (rs.next()) PendingConnect(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).time, rs.getBoolean(5)) else null
            }
        }
    }

    override fun upsertAccount(householdId: String, provider: String, email: String, refreshTokenEnc: ByteArray, canEdit: Boolean, grantedAtMs: Long?, newId: () -> String): String = c { c ->
        c.prepareStatement(
            """INSERT INTO integration_account(id, household_id, provider, email, refresh_token_enc, can_edit, granted_at) VALUES (?,?,?,?,?,?,?)
               ON CONFLICT (household_id, provider, email) DO UPDATE SET refresh_token_enc = EXCLUDED.refresh_token_enc,
                 can_edit = EXCLUDED.can_edit, status = 'ok', last_error = NULL, updated_at = now(),
                 granted_at = COALESCE(EXCLUDED.granted_at, integration_account.granted_at)
               RETURNING id""",
        ).use { st ->
            st.setString(1, newId()); st.setString(2, householdId); st.setString(3, provider); st.setString(4, email); st.setBytes(5, refreshTokenEnc)
            st.setBoolean(6, canEdit)
            if (grantedAtMs != null) st.setTimestamp(7, Timestamp(grantedAtMs)) else st.setNull(7, java.sql.Types.TIMESTAMP)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    private fun query(sql: String, vararg args: String): List<AccountRow> = c { c ->
        c.prepareStatement("SELECT id, household_id, provider, email, refresh_token_enc, status, last_sync_at, can_edit, granted_at FROM integration_account $sql").use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(AccountRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getString(6),
                        rs.getTimestamp(7)?.time, rs.getBoolean(8), rs.getTimestamp(9)?.time,
                    ))
                }
            }
        }
    }

    override fun account(id: String) = query("WHERE id = ?", id).firstOrNull()
    override fun accounts(householdId: String) = query("WHERE household_id = ? ORDER BY created_at", householdId)
    override fun syncableAccounts() = query("WHERE status <> 'needs_reconnect'")

    override fun households(): List<String> = c { c ->
        c.prepareStatement("SELECT DISTINCT household_id FROM device WHERE revoked_at IS NULL").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit) = c { c ->
        c.prepareStatement(sql).use { bind(it); it.executeUpdate() }
        Unit
    }

    override fun updateRefreshToken(id: String, enc: ByteArray) =
        update("UPDATE integration_account SET refresh_token_enc = ?, updated_at = now() WHERE id = ?") { it.setBytes(1, enc); it.setString(2, id) }

    override fun stopEditing(householdId: String, provider: String, email: String): Boolean = c { c ->
        c.prepareStatement("UPDATE integration_account SET can_edit = false, updated_at = now() WHERE household_id = ? AND provider = ? AND email = ?").use {
            it.setString(1, householdId); it.setString(2, provider); it.setString(3, email)
            it.executeUpdate() > 0
        }
    }

    override fun markSynced(id: String, atMs: Long) =
        update("UPDATE integration_account SET status = 'ok', last_error = NULL, last_sync_at = ? WHERE id = ?") {
            it.setTimestamp(1, Timestamp(atMs)); it.setString(2, id)
        }

    override fun markError(id: String, status: String, error: String) =
        update("UPDATE integration_account SET status = ?, last_error = ?, updated_at = now() WHERE id = ?") {
            it.setString(1, status); it.setString(2, error.take(300)); it.setString(3, id)
        }

    override fun lockAccount(id: String) = c { c ->
        c.prepareStatement("SELECT 1 FROM integration_account WHERE id = ? FOR UPDATE").use { it.setString(1, id); it.executeQuery().close() }
    }

    override fun mirror(householdId: String, accountId: String): Map<String, MirrorRow> = c { c ->
        c.prepareStatement("SELECT entity_id, start_ms, removed, field_ops, end_ms, all_day, remote_id FROM event_mirror WHERE household_id = ? AND account_id = ?").use { st ->
            st.setString(1, householdId); st.setString(2, accountId)
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val id = rs.getString(1)
                        val start = rs.getLong(2)
                        val end = rs.getLong(5).takeIf { !rs.wasNull() } ?: start
                        put(id, MirrorRow(id, accountId, start, rs.getBoolean(3), FieldOpsJson.decode(rs.getString(4)), end, rs.getBoolean(6), rs.getString(7)))
                    }
                }
            }
        }
    }

    override fun putMirror(householdId: String, row: MirrorRow) = update(
        """INSERT INTO event_mirror(household_id, entity_id, account_id, start_ms, removed, field_ops, end_ms, all_day, remote_id) VALUES (?,?,?,?,?,?,?,?,?)
           ON CONFLICT (household_id, entity_id) DO UPDATE SET start_ms = EXCLUDED.start_ms, removed = EXCLUDED.removed,
             field_ops = EXCLUDED.field_ops, end_ms = EXCLUDED.end_ms, all_day = EXCLUDED.all_day,
             remote_id = COALESCE(EXCLUDED.remote_id, event_mirror.remote_id)""",
    ) {
        it.setString(1, householdId); it.setString(2, row.entityId); it.setString(3, row.accountId)
        it.setLong(4, row.startMs); it.setBoolean(5, row.removed); it.setString(6, FieldOpsJson.encode(row.fieldOps))
        it.setLong(7, row.endMs); it.setBoolean(8, row.allDay)
        if (row.remoteId != null) it.setString(9, row.remoteId) else it.setNull(9, java.sql.Types.VARCHAR)
    }
}
