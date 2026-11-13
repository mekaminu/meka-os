package os.meka.android.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.ask.ErrandProximity
import os.meka.android.notify.NotifyTraceStore
import os.meka.core.domain.LeavingRules
import os.meka.core.facade.MekaCore

/**
 * "When I leave work" (Needs Meka #24, "Geofence yes", 2026-11-11), the Fold only. The work spot is one precise fix
 * taken when Meka taps "I'm at work", kept in this phone's app storage only (never synced or sent). With Leaving now's
 * "When I leave work" on and precise plus "Allow all the time" location given, the spot is handed to Android's own
 * proximity alert ([LocationManager.addProximityAlert]; no Google library, as with Errands). Android wakes
 * [WorkAreaReceiver] on walking in and out; only those two moments are kept (the last of each, on this phone) and handed
 * to the core in memory, so the draft to his wife comes as he leaves. Nothing else reads location for this.
 */
object WorkArea {
    private const val PREFS = "meka.work-area"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ENTERED = "entered"
    private const val KEY_LEFT = "left"
    private const val KEY_ARMED = "armed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun spotSaved(context: Context): Boolean = prefs(context).contains(KEY_LAT)

    private fun spot(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains(KEY_LAT) || !p.contains(KEY_LON)) return null
        return java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0L)) to java.lang.Double.longBitsToDouble(p.getLong(KEY_LON, 0L))
    }

    private fun time(context: Context, key: String): Long? = prefs(context).getLong(key, -1L).takeIf { it > 0L }

    /** "I'm at work": one precise fix saved as the work spot when it is sure enough, then watched. Never throws. */
    suspend fun saveHere(context: Context, core: MekaCore): Pair<Boolean, String> {
        if (!ErrandProximity.precise(context)) return false to LeavingRules.areaLine(true, false, false, false).line
        val fix = try { ErrandProximity.locate(context) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val r = LeavingRules.areaSaved(fix?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble())
        if (r.first && fix != null) {
            prefs(context).edit()
                .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(fix.latitude))
                .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(fix.longitude))
                // Standing there now: inside from this moment.
                .putLong(KEY_ENTERED, System.currentTimeMillis()).remove(KEY_LEFT)
                .apply()
            arm(context, core)
        }
        return r
    }

    /** Forgets the work spot and stops watching it; the draft falls back to the end of work. */
    suspend fun forget(context: Context, core: MekaCore) {
        prefs(context).edit().remove(KEY_LAT).remove(KEY_LON).remove(KEY_ENTERED).remove(KEY_LEFT).apply()
        arm(context, core)
    }

    /**
     * Brings Android's watch in line with the setting, the permissions and the spot, and tells the core. Safe to call
     * often (at start, after the switch or a permission changes).
     */
    @SuppressLint("MissingPermission") // precise() is checked; a late revoke throws SecurityException, caught below
    suspend fun arm(context: Context, core: MekaCore) {
        val lm = context.getSystemService(LocationManager::class.java)
        val want = core.leavingView.value.on && core.leavingView.value.byArea &&
            ErrandProximity.precise(context) && ErrandProximity.always(context)
        val at = spot(context)
        if (lm != null) runCatching { lm.removeProximityAlert(intent(context)) }
        val armed = want && at != null && lm != null && try {
            lm.addProximityAlert(at.first, at.second, LeavingRules.AREA_RADIUS_M.toFloat(), -1L, intent(context))
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
        prefs(context).edit().putBoolean(KEY_ARMED, armed).apply()
        core.setWorkArea(armed, time(context, KEY_ENTERED), time(context, KEY_LEFT))
    }

    /** Android said he walked in ([entering]) or out: keep that moment and hand it to the core. */
    suspend fun noticed(context: Context, core: MekaCore, entering: Boolean, nowMs: Long) {
        prefs(context).edit().putLong(if (entering) KEY_ENTERED else KEY_LEFT, nowMs).apply()
        core.setWorkArea(prefs(context).getBoolean(KEY_ARMED, false), time(context, KEY_ENTERED), time(context, KEY_LEFT))
    }

    /** Mutable because Android adds whether it is entering or leaving; explicit, so nothing else can fill it in. */
    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, WorkAreaReceiver::class.java).setAction(WorkAreaReceiver.ACTION_AREA),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Android's proximity alert on the work spot: walking in or out, then the notices are looked at again. */
class WorkAreaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AREA) return
        val app = context.applicationContext as MekaApplication
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WorkArea.noticed(app, app.core, entering, System.currentTimeMillis())
                if (!entering) { app.core.tick(); app.governor.run() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NotifyTraceStore.failed(context, "work area", e)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_AREA = "os.meka.work.AREA"
    }
}
