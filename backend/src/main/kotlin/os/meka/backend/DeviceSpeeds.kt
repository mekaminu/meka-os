package os.meka.backend

import os.meka.core.wire.DeviceSpeedCodec
import javax.sql.DataSource

/**
 * Obi's response times from each device (Needs Meka #43 "Yes", 2026-11-11): each device sends its last day's summary
 * once a day; GitHub's release publisher (or any keyed device) reads the newest one per device for the daily build
 * report. Numbers only: counts, averages and the slowest time to Obi's voice, the device's label and when it was sent.
 */
interface DeviceSpeedStore {
    fun put(householdId: String, deviceId: String, report: DeviceSpeedCodec.Report)
    /** The newest summary per device, newest first, at most [DeviceSpeedCodec.DEVICES_MAX]. */
    fun latest(householdId: String): List<DeviceSpeedCodec.Report>
}

class DeviceSpeeds(private val store: DeviceSpeedStore) {
    fun report(who: DeviceIdentity, report: DeviceSpeedCodec.Report) {
        // The publisher reads; it never reports.
        if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
        store.put(who.householdId, who.deviceId, report)
    }

    fun latest(who: DeviceIdentity): List<DeviceSpeedCodec.Report> = store.latest(who.householdId)
}

class InMemoryDeviceSpeedStore : DeviceSpeedStore {
    private val rows = LinkedHashMap<Pair<String, String>, DeviceSpeedCodec.Report>()
    @Synchronized override fun put(householdId: String, deviceId: String, report: DeviceSpeedCodec.Report) {
        rows.remove(householdId to deviceId)
        rows[householdId to deviceId] = report
    }
    @Synchronized override fun latest(householdId: String) =
        rows.filterKeys { it.first == householdId }.values.reversed().take(DeviceSpeedCodec.DEVICES_MAX)
}

class PostgresDeviceSpeedStore(private val ds: DataSource) : DeviceSpeedStore {
    override fun put(householdId: String, deviceId: String, report: DeviceSpeedCodec.Report) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO device_speed_report(household_id, device_id, report) VALUES (?,?,?)
               ON CONFLICT (household_id, device_id) DO UPDATE SET report = EXCLUDED.report, received_at = now()""",
        ).use {
            it.setString(1, householdId); it.setString(2, deviceId); it.setString(3, DeviceSpeedCodec.encodeReport(report))
            it.executeUpdate()
        }
        Unit
    }

    override fun latest(householdId: String): List<DeviceSpeedCodec.Report> = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT report FROM device_speed_report WHERE household_id = ? ORDER BY received_at DESC LIMIT ${DeviceSpeedCodec.DEVICES_MAX}",
        ).use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs ->
                val out = ArrayList<DeviceSpeedCodec.Report>()
                while (rs.next()) runCatching { DeviceSpeedCodec.decodeReport(rs.getString(1)) }.getOrNull()?.let { out += it }
                out
            }
        }
    }
}
