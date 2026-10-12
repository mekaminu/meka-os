package os.meka.android.fold

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.core.domain.FoldPostureReportRules

/**
 * Sends the Fold's last posture changes to MEKA's server (bedside clock, slice 2) once the posture has settled
 * ([FoldPostureReportRules]), so a build run can read what the Fold 8 reports from the publish job. What goes: the
 * phone's model name and the changes Health lists (times and readings); nothing personal. A failed send (offline, no
 * key yet, an older server) tries again later; what was sent last is kept in the phone's preferences for Health.
 */
object FoldPostureReporter {
    private const val PREFS = "meka_fold"
    private const val KEY_THROUGH = "posture_sent_through"
    private const val KEY_SENT_AT = "posture_sent_at"

    /** (newest change sent, when it was sent or null), for Health's line. */
    data class Sent(val throughMs: Long, val atMs: Long?)

    private val _sent = MutableStateFlow<Sent?>(null)
    val sent: StateFlow<Sent?> = _sent
    private var job: Job? = null
    private var failedAtMs: Long? = null

    fun sent(context: Context): Sent = _sent.value ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
        Sent(p.getLong(KEY_THROUGH, 0L), p.getLong(KEY_SENT_AT, 0L).takeIf { it > 0 })
    }.also { _sent.value = it }

    /** Makes sure a sender is waiting when there are changes not sent yet; cheap to call on every change. */
    @Synchronized
    fun poke(context: Context) {
        val app = context.applicationContext as? MekaApplication ?: return
        if (job?.isActive == true) return
        job = app.appScope.launch {
            while (true) {
                val log = FoldDiagnostics.log(app)
                val through = sent(app).throughMs
                val wait = FoldPostureReportRules.waitMs(log, through, failedAtMs, System.currentTimeMillis()) ?: break
                if (wait > 0) delay(wait)
                val now = System.currentTimeMillis()
                // A newer change while waiting moves the settle time on; look again.
                if (!FoldPostureReportRules.due(FoldDiagnostics.log(app), through, failedAtMs, now)) continue
                val current = FoldDiagnostics.log(app)
                val ok = runCatching { app.core.reportFoldPostures(Build.MODEL, current) }.getOrDefault(false)
                if (ok) {
                    failedAtMs = null
                    val s = Sent(current.first().atMs, now)
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_THROUGH, s.throughMs).putLong(KEY_SENT_AT, now).apply()
                    _sent.value = s
                } else {
                    failedAtMs = now
                }
            }
        }
    }
}
