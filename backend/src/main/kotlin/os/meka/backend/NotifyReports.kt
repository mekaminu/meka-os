package os.meka.backend

import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.AssistantCallEntry
import os.meka.core.domain.AssistantCallReportRules
import os.meka.core.domain.MailCheckRules
import os.meka.core.sync.FieldValue
import os.meka.core.wire.NotifyDiagnosticsCodec
import javax.sql.DataSource

/**
 * The phone's notification trace (notifications never seen, slice 2). The phone sends its report; GitHub's release
 * publisher (or any keyed device) reads the household's newest one, with the server's last push to the device that
 * sent it, so the publish job can print it for a build run. Nothing personal: channel names, counts, reasons and error
 * class names, the phone's model name, and which device sent it.
 */
interface NotifyReportStore {
    fun put(householdId: String, deviceId: String, report: NotifyDiagnosticsCodec.Report)
    /** The newest report and the device that sent it. */
    fun latest(householdId: String): Pair<String, NotifyDiagnosticsCodec.Report>?
}

/**
 * [profile] (About you, slice 2) reads the household profile's fields, so the publish job can say whether the Fold
 * and the Mac both hold it (release 2's gate); only presence and the held-at times leave, never a value.
 */
class NotifyReports(
    private val store: NotifyReportStore, private val push: Push?,
    private val profile: ((householdId: String) -> Map<String, FieldValue>?)? = null,
    /**
     * Call notifications, slice 1: the call assistant's last messages ([AssistantCallReportRules]) for the publish
     * job's "Call assistant" notices; never a number or a word.
     */
    private val assistantCalls: ((householdId: String) -> List<AssistantCallEntry>)? = null,
    /** Inbox fixes, slice 6: each mail account's last check ([MailCheckRules.Report]), for the "Mail autopilot" notices. */
    private val mailChecks: ((householdId: String) -> List<MailCheckRules.Report>)? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun report(who: DeviceIdentity, report: NotifyDiagnosticsCodec.Report) {
        // The publisher reads; it never reports.
        if (who.deviceId == ReleasePublisher.ID) throw Forbidden()
        store.put(who.householdId, who.deviceId, report)
    }

    fun latest(who: DeviceIdentity): NotifyDiagnosticsCodec.Latest {
        val held = profile?.let { read -> AboutYouRules.held(runCatching { read(who.householdId) }.getOrNull()) }
        val calls = assistantCalls?.let { read -> runCatching { read(who.householdId) }.getOrNull() }?.map { e ->
            NotifyDiagnosticsCodec.Entry(e.atMs, if (e.automated) "automated" else "voice", AssistantCallReportRules.line(e))
        }
        val nowMs = now()
        val mail = mailChecks?.let { read -> runCatching { read(who.householdId) }.getOrNull() }?.map { r ->
            NotifyDiagnosticsCodec.Entry(r.lastCheckAtMs ?: nowMs, r.label, MailCheckRules.reportLine(r, nowMs) { utc(it) })
        }
        val about = NotifyDiagnosticsCodec.Latest(
            null, aboutFilled = held?.filled, aboutFoldAtMs = held?.foldAtMs, aboutMacAtMs = held?.macAtMs,
            aboutClubs = held?.clubs, aboutCouncil = held?.council, aboutRoute = held?.route, assistant = calls, mail = mail,
        )
        val (deviceId, report) = store.latest(who.householdId) ?: return about
        val sender = DeviceIdentity(who.householdId, deviceId)
        val last = push?.lastResult(sender)
        return about.copy(
            report = report,
            lastPushAtMs = last?.first,
            lastPushResult = last?.second?.let { word(it) },
            hasAddress = push?.hasAddress(sender),
        )
    }

    companion object {
        /** "2026-10-16 06:50 UTC". */
        fun utc(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString().take(16).replace('T', ' ') + " UTC"

        /** The wire's word for a send's result. */
        fun word(r: SendResult): String = when (r) {
            SendResult.SENT -> NotifyDiagnosticsCodec.SENT
            SendResult.GONE -> NotifyDiagnosticsCodec.GONE
            SendResult.FAILED -> NotifyDiagnosticsCodec.FAILED
            SendResult.OFF -> NotifyDiagnosticsCodec.OFF
        }
    }
}

class InMemoryNotifyReportStore : NotifyReportStore {
    private val rows = HashMap<String, Pair<String, NotifyDiagnosticsCodec.Report>>()
    @Synchronized override fun put(householdId: String, deviceId: String, report: NotifyDiagnosticsCodec.Report) { rows[householdId] = deviceId to report }
    @Synchronized override fun latest(householdId: String) = rows[householdId]
}

class PostgresNotifyReportStore(private val ds: DataSource) : NotifyReportStore {
    override fun put(householdId: String, deviceId: String, report: NotifyDiagnosticsCodec.Report) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO notify_report(household_id, device_id, report) VALUES (?,?,?)
               ON CONFLICT (household_id) DO UPDATE SET device_id = EXCLUDED.device_id, report = EXCLUDED.report, received_at = now()""",
        ).use {
            it.setString(1, householdId); it.setString(2, deviceId); it.setString(3, NotifyDiagnosticsCodec.encodeReport(report))
            it.executeUpdate()
        }
        Unit
    }

    override fun latest(householdId: String): Pair<String, NotifyDiagnosticsCodec.Report>? = ds.connection.use { c ->
        c.prepareStatement("SELECT device_id, report FROM notify_report WHERE household_id = ?").use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else {
                    val device = rs.getString(1)
                    runCatching { NotifyDiagnosticsCodec.decodeReport(rs.getString(2)) }.getOrNull()?.let { device to it }
                }
            }
        }
    }
}
