package os.meka.core.data

import app.cash.sqldelight.db.SqlDriver
import os.meka.core.sync.EntityRef
import os.meka.core.sync.FieldKey
import os.meka.core.sync.FieldState
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.ReplicaStore

/**
 * SQLDelight-backed [ReplicaStore]. Must pass `ReplicaStoreContract` (core/testing) exactly like the in-memory store.
 * The driver is created by the platform with SQLCipher and a Keystore/Keychain-held key (ADR-002).
 */
class SqlReplicaStore(driver: SqlDriver) : ReplicaStore {
    private val db = MekaDatabase(driver)
    private val q = db.replicaQueries

    override fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    override fun op(opId: String): Op? = q.opById(opId, ::rowToOp).executeAsOneOrNull()

    override fun appendOp(op: Op, local: Boolean) {
        val (type, text, int) = op.value.columns()
        q.insertOp(
            op.opId, op.householdId, op.entityType, op.entityId, op.field, type, text, int,
            op.hlc.encode(), op.baseOpIds.joinToString(","), op.deviceId, op.schemaVersion.toLong(), if (local) 1L else 0L,
        )
    }

    override fun fieldState(key: FieldKey): FieldState {
        val heads = q.headsFor(key.entityType, key.entityId, key.field, ::rowToOp).executeAsList()
        val superseded = q.supersededFor(key.entityType, key.entityId, key.field).executeAsList().toSet()
        return FieldState(heads, superseded)
    }

    override fun setFieldState(key: FieldKey, state: FieldState) {
        q.deleteHeads(key.entityType, key.entityId, key.field)
        state.heads.forEach { q.insertHead(key.entityType, key.entityId, key.field, it.opId) }
        // Superseded only ever grows (FieldState.add), so inserting is sufficient.
        state.superseded.forEach { q.insertSuperseded(key.entityType, key.entityId, key.field, it) }
    }

    override fun fieldHeads(ref: EntityRef): Map<String, List<Op>> =
        q.headsForEntity(ref.entityType, ref.entityId, ::rowToOp).executeAsList().groupBy { it.field }

    override fun entityIds(entityType: String): List<String> = q.entityIds(entityType).executeAsList()

    override fun pendingPush(limit: Int): List<Op> = q.pendingPush(limit.toLong(), ::rowToOp).executeAsList()
    override fun markPushed(opIds: Collection<String>) { opIds.chunked(500).forEach { q.markPushed(it) } }
    override fun pendingCount(): Int = q.pendingCount().executeAsOne().toInt()

    override fun pullCursor(): Long = q.metaGet(META_CURSOR).executeAsOneOrNull()?.toLong() ?: 0L
    override fun setPullCursor(seq: Long) { q.metaPut(META_CURSOR, seq.toString()) }

    override fun clockHighWater(): Hlc = q.metaGet(META_CLOCK).executeAsOneOrNull()?.let(Hlc::decode) ?: Hlc.ZERO
    override fun setClockHighWater(hlc: Hlc) { q.metaPut(META_CLOCK, hlc.encode()) }

    override fun localValue(key: String): String? = q.metaGet(LOCAL_PREFIX + key).executeAsOneOrNull()
    override fun setLocalValue(key: String, value: String?) {
        if (value == null) q.metaDelete(LOCAL_PREFIX + key) else q.metaPut(LOCAL_PREFIX + key, value)
    }

    /** Column order of `op_log` (all op queries select exactly `op_log.*`), via SQLDelight's mapper overloads. */
    @Suppress("UNUSED_PARAMETER")
    private fun rowToOp(
        op_id: String, household_id: String, entity_type: String, entity_id: String, field_name: String,
        value_type: String, value_text: String?, value_int: Long?, hlc: String, base_op_ids: String,
        device_id: String, schema_version: Long, local: Long, pushed: Long,
    ) = Op(
        opId = op_id, householdId = household_id, entityType = entity_type, entityId = entity_id, field = field_name,
        value = valueOf(value_type, value_text, value_int), hlc = Hlc.decode(hlc), baseOpIds = splitBase(base_op_ids),
        deviceId = device_id, schemaVersion = schema_version.toInt(),
    )

    private companion object {
        const val META_CURSOR = "pull_cursor"
        const val META_CLOCK = "clock_high_water"
        /** Device-local values live in `replica_meta` under this prefix, apart from the cursor and clock. */
        const val LOCAL_PREFIX = "local:"

        fun splitBase(s: String) = if (s.isEmpty()) emptyList() else s.split(',')

        fun FieldValue.columns(): Triple<String, String?, Long?> = when (this) {
            is FieldValue.Text -> Triple("s", value, null)
            is FieldValue.Int64 -> Triple("i", null, value)
            is FieldValue.Bool -> Triple("b", null, if (value) 1L else 0L)
            FieldValue.Null -> Triple("n", null, null)
        }

        fun valueOf(type: String, text: String?, int: Long?): FieldValue = when (type) {
            "s" -> FieldValue.Text(text ?: "")
            "i" -> FieldValue.Int64(int ?: 0L)
            "b" -> FieldValue.Bool(int == 1L)
            else -> FieldValue.Null
        }
    }
}
