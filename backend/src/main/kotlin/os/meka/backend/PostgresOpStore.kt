package os.meka.backend

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.SequencedOp
import os.meka.core.sync.ServerOpStore
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * Postgres [ServerOpStore]. Per-household sequence numbers are allocated under a row lock on `household`, so
 * `seq` is gap-free and strictly increasing in commit order — pullers can never skip an op committed "late".
 */
class PostgresOpStore(private val ds: DataSource) : ServerOpStore {
    private val current = ThreadLocal<Connection?>()

    override fun <T> transaction(block: () -> T): T {
        current.get()?.let { return block() }
        ds.connection.use { c ->
            c.autoCommit = false
            c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            current.set(c)
            try {
                val r = block()
                c.commit()
                return r
            } catch (t: Throwable) {
                c.rollback()
                throw t
            } finally {
                current.set(null)
            }
        }
    }

    private fun <T> conn(block: (Connection) -> T): T =
        current.get()?.let(block) ?: ds.connection.use(block)

    override fun find(householdId: String, opId: String): Op? = conn { c ->
        c.prepareStatement("SELECT * FROM op_log WHERE household_id = ? AND op_id = ?").use { st ->
            st.setString(1, householdId); st.setString(2, opId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toOp() else null }
        }
    }

    override fun append(op: Op): Long = conn { c ->
        val seq = c.prepareStatement(
            "UPDATE household SET next_seq = next_seq + 1 WHERE id = ? RETURNING next_seq - 1",
        ).use { st ->
            st.setString(1, op.householdId)
            st.executeQuery().use { rs -> check(rs.next()) { "unknown household" }; rs.getLong(1) }
        }
        c.prepareStatement(
            """INSERT INTO op_log(household_id, seq, op_id, entity_type, entity_id, field_name, value_type, value_text,
               value_int, hlc, base_op_ids, device_id, schema_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { st ->
            st.setString(1, op.householdId); st.setLong(2, seq); st.setString(3, op.opId)
            st.setString(4, op.entityType); st.setString(5, op.entityId); st.setString(6, op.field)
            when (val v = op.value) {
                is FieldValue.Text -> { st.setString(7, "s"); st.setString(8, v.value); st.setNull(9, Types.BIGINT) }
                is FieldValue.Int64 -> { st.setString(7, "i"); st.setNull(8, Types.VARCHAR); st.setLong(9, v.value) }
                is FieldValue.Bool -> { st.setString(7, "b"); st.setNull(8, Types.VARCHAR); st.setLong(9, if (v.value) 1 else 0) }
                FieldValue.Null -> { st.setString(7, "n"); st.setNull(8, Types.VARCHAR); st.setNull(9, Types.BIGINT) }
            }
            st.setString(10, op.hlc.encode()); st.setString(11, op.baseOpIds.joinToString(","))
            st.setString(12, op.deviceId); st.setInt(13, op.schemaVersion)
            st.executeUpdate()
        }
        seq
    }

    override fun after(householdId: String, afterSeq: Long, limit: Int): List<SequencedOp> = conn { c ->
        c.prepareStatement("SELECT * FROM op_log WHERE household_id = ? AND seq > ? ORDER BY seq LIMIT ?").use { st ->
            st.setString(1, householdId); st.setLong(2, afterSeq); st.setInt(3, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(SequencedOp(rs.getLong("seq"), rs.toOp())) } }
        }
    }

    override fun isDeviceAuthorised(householdId: String, deviceId: String): Boolean = conn { c ->
        c.prepareStatement("SELECT 1 FROM device WHERE household_id = ? AND id = ? AND revoked_at IS NULL").use { st ->
            st.setString(1, householdId); st.setString(2, deviceId)
            st.executeQuery().use { it.next() }
        }
    }

    private fun ResultSet.toOp(): Op {
        val type = getString("value_type")
        val value = when (type) {
            "s" -> FieldValue.Text(getString("value_text"))
            "i" -> FieldValue.Int64(getLong("value_int"))
            "b" -> FieldValue.Bool(getLong("value_int") == 1L)
            else -> FieldValue.Null
        }
        val base = getString("base_op_ids")
        return Op(
            opId = getString("op_id"), householdId = getString("household_id"), entityType = getString("entity_type"),
            entityId = getString("entity_id"), field = getString("field_name"), value = value,
            hlc = Hlc.decode(getString("hlc")), baseOpIds = if (base.isEmpty()) emptyList() else base.split(','),
            deviceId = getString("device_id"), schemaVersion = getInt("schema_version"),
        )
    }
}
