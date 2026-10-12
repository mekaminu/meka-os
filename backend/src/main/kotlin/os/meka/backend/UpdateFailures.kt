package os.meka.backend

import os.meka.core.wire.UpdateFailureCodec
import javax.sql.DataSource

/**
 * Phone update failures (build plan: phone updates sometimes fail to install). The Fold reports why an update didn't
 * go in; GitHub's release publisher (and any keyed device) reads the household's newest few, so the publish job can
 * print them for a build run. Nothing personal is stored: the report's fields and which device sent it.
 */
interface UpdateFailureStore {
    fun add(householdId: String, deviceId: String, report: UpdateFailureCodec.Report)
    /** Newest first. */
    fun recent(householdId: String, limit: Int): List<UpdateFailureCodec.Report>
}

class UpdateFailures(private val store: UpdateFailureStore) {
    fun report(who: DeviceIdentity, report: UpdateFailureCodec.Report) {
        // The publisher reads; it never reports.
        if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
        store.add(who.householdId, who.deviceId, report)
    }

    fun recent(who: DeviceIdentity): List<UpdateFailureCodec.Report> = store.recent(who.householdId, UpdateFailureCodec.LIST_MAX)

    companion object {
        /** The most kept per household. */
        const val KEEP = 20
    }
}

class InMemoryUpdateFailureStore : UpdateFailureStore {
    private val rows = HashMap<String, ArrayDeque<UpdateFailureCodec.Report>>()

    @Synchronized override fun add(householdId: String, deviceId: String, report: UpdateFailureCodec.Report) {
        val q = rows.getOrPut(householdId) { ArrayDeque() }
        q.addFirst(report)
        while (q.size > UpdateFailures.KEEP) q.removeLast()
    }

    @Synchronized override fun recent(householdId: String, limit: Int) = rows[householdId]?.take(limit).orEmpty()
}

class PostgresUpdateFailureStore(private val ds: DataSource) : UpdateFailureStore {
    override fun add(householdId: String, deviceId: String, report: UpdateFailureCodec.Report) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO app_update_failure(household_id, device_id, stage, status, message, from_code, to_code, failed_at_ms, storage, network, attempts)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        ).use {
            it.setString(1, householdId); it.setString(2, deviceId); it.setString(3, report.stage)
            val status = report.status
            if (status != null) it.setInt(4, status) else it.setNull(4, java.sql.Types.INTEGER)
            it.setString(5, report.message); it.setLong(6, report.fromCode); it.setLong(7, report.toCode); it.setLong(8, report.atMs)
            it.setString(9, report.storage); it.setString(10, report.network); it.setInt(11, report.attempts)
            it.executeUpdate()
        }
        c.prepareStatement(
            """DELETE FROM app_update_failure WHERE household_id = ? AND id NOT IN
                 (SELECT id FROM app_update_failure WHERE household_id = ? ORDER BY id DESC LIMIT ?)""",
        ).use {
            it.setString(1, householdId); it.setString(2, householdId); it.setInt(3, UpdateFailures.KEEP); it.executeUpdate()
        }
        Unit
    }

    override fun recent(householdId: String, limit: Int): List<UpdateFailureCodec.Report> = ds.connection.use { c ->
        c.prepareStatement(
            """SELECT stage, status, message, from_code, to_code, failed_at_ms, storage, network, attempts FROM app_update_failure
               WHERE household_id = ? ORDER BY id DESC LIMIT ?""",
        ).use { st ->
            st.setString(1, householdId); st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            UpdateFailureCodec.Report(
                                stage = rs.getString(1), status = rs.getInt(2).takeIf { !rs.wasNull() }, message = rs.getString(3),
                                fromCode = rs.getLong(4), toCode = rs.getLong(5), atMs = rs.getLong(6),
                                storage = rs.getString(7), network = rs.getString(8), attempts = rs.getInt(9),
                            ),
                        )
                    }
                }
            }
        }
    }
}
