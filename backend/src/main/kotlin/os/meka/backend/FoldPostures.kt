package os.meka.backend

import os.meka.core.wire.FoldPostureCodec
import javax.sql.DataSource

/**
 * The Fold's posture changes (bedside clock, slice 2). The Fold sends its last few; GitHub's release publisher (and any
 * keyed device) reads the household's newest report, so the publish job can print it for a build run. Nothing personal
 * is stored: the phone's model name, the times and the readings, and which device sent them.
 */
interface FoldPostureStore {
    fun put(householdId: String, deviceId: String, report: FoldPostureCodec.Report)
    fun latest(householdId: String): FoldPostureCodec.Report?
}

class FoldPostures(private val store: FoldPostureStore) {
    fun report(who: DeviceIdentity, report: FoldPostureCodec.Report) {
        // The publisher reads; it never reports.
        if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
        store.put(who.householdId, who.deviceId, report)
    }

    fun latest(who: DeviceIdentity): FoldPostureCodec.Report? = store.latest(who.householdId)
}

class InMemoryFoldPostureStore : FoldPostureStore {
    private val rows = HashMap<String, FoldPostureCodec.Report>()
    @Synchronized override fun put(householdId: String, deviceId: String, report: FoldPostureCodec.Report) { rows[householdId] = report }
    @Synchronized override fun latest(householdId: String) = rows[householdId]
}

class PostgresFoldPostureStore(private val ds: DataSource) : FoldPostureStore {
    override fun put(householdId: String, deviceId: String, report: FoldPostureCodec.Report) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO fold_posture_report(household_id, device_id, report) VALUES (?,?,?)
               ON CONFLICT (household_id) DO UPDATE SET device_id = EXCLUDED.device_id, report = EXCLUDED.report, received_at = now()""",
        ).use {
            it.setString(1, householdId); it.setString(2, deviceId); it.setString(3, FoldPostureCodec.encodeReport(report))
            it.executeUpdate()
        }
        Unit
    }

    override fun latest(householdId: String): FoldPostureCodec.Report? = ds.connection.use { c ->
        c.prepareStatement("SELECT report FROM fold_posture_report WHERE household_id = ?").use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs -> if (rs.next()) runCatching { FoldPostureCodec.decodeReport(rs.getString(1)) }.getOrNull() else null }
        }
    }
}
