package os.meka.android.today

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.core.facade.MekaCore
import kotlin.coroutines.resume

/**
 * "Where I am now" on the Fold (build plan "Places…", item 3, the app slice). The switch is kept on this phone only
 * (off by default); the decision to locate is the core's ([MekaCore.hereShouldLocate]: switch on, permission given,
 * Today opening or a question about "here", at most once per 30 minutes). One approximate fix is taken on demand,
 * while MEKA is in front, with `ACCESS_COARSE_LOCATION` only and balanced power; never a background or repeating
 * request. The core rounds the point to about 1 km before anything is sent ([MekaCore.hereWeather]); turning the
 * switch off, or losing the permission, forgets the last answer ([MekaCore.forgetHere]).
 */
object HereLocation {
    private const val PREFS = "meka.here"
    private const val KEY_ON = "on"

    /** How long one fix may take before MEKA carries on without it (Ask doesn't wait longer than this). */
    const val FIX_TIMEOUT_MS = 6_000L

    /** A fix the phone already has is good enough when it is this fresh (no radio woken at all). */
    const val LAST_KNOWN_FRESH_MS = 10 * 60_000L

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Before Today shows ([question] null) or before Ask/Talk sends [question]: takes one approximate fix when the core
     * says so and hands it to the core. Never throws; false when nothing was located. With the switch off or no
     * permission, whatever was known is forgotten.
     */
    suspend fun refresh(context: Context, core: MekaCore, question: String?): Boolean {
        val on = isOn(context)
        val allowed = permitted(context)
        return try {
            if (!on || !allowed) {
                core.forgetHere()
                return false
            }
            if (!core.hereShouldLocate(on, allowed, question)) return false
            val fix = locate(context) ?: return false
            core.hereWeather(fix.latitude, fix.longitude)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The provider to ask, from the enabled ones: the fused provider (Android 12+) first, then the network's, then
     * GPS (with coarse permission Android blurs it anyway). Null when location is off on the phone. Pure.
     */
    fun pickProvider(enabled: Collection<String>): String? =
        listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in enabled }

    /** Whether a fix the phone already holds is fresh enough to use, from its age. Pure. */
    fun freshEnough(ageMs: Long): Boolean = ageMs in 0..LAST_KNOWN_FRESH_MS

    private const val FUSED = "fused" // LocationManager.FUSED_PROVIDER (API 31)

    @SuppressLint("MissingPermission") // checked by the caller (permitted) and again here
    private suspend fun locate(context: Context): Location? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        val provider = pickProvider(lm.getProviders(true)) ?: return null
        lm.getLastKnownLocation(provider)?.let { last ->
            val age = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
            if (freshEnough(age)) return last
        }
        val request = LocationRequest.Builder(0L).setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).build()
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(provider, request, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
