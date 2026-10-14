package os.meka.android.ask

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import os.meka.core.domain.LocationFix
import os.meka.core.domain.ParkSaved
import os.meka.core.domain.ParkedSpot
import os.meka.core.domain.ParkingRules

/**
 * Car and parking, slice 1 (the Fold only): where each car is parked, kept in this phone's own storage. Never synced,
 * never sent: the core only says what to save and what to show ([ParkingRules]).
 *
 * "I've parked here" listens to GPS for a few seconds ([ParkingRules.SETTLE_MS], sooner once a fix is within 5 m) and
 * saves the best fix; Find my car listens to GPS and the phone's compass (the rotation vector sensor, corrected to true
 * north) only while its view is on screen, and stops the moment it goes. Precise location is asked for only when he
 * first taps "I've parked here"; nothing runs in the background.
 */
object ParkingStore {
    private const val PREFS = "meka.parking"
    private const val KEY_SPOTS = "spots"

    /** A fix this good ends the listening early. */
    private const val GOOD_ENOUGH_M = 5f

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun precise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    fun spots(context: Context): Map<String, ParkedSpot> = ParkingRules.decode(prefs(context).getString(KEY_SPOTS, null))

    fun store(context: Context, spots: Map<String, ParkedSpot>) {
        prefs(context).edit().putString(KEY_SPOTS, ParkingRules.encode(spots.values)).apply()
    }

    /** "I've parked here" for [carId]: a few seconds of fixes, the best saved. Never throws. */
    suspend fun parkHere(context: Context, carId: String, title: String): ParkSaved {
        val before = spots(context)
        if (!precise(context)) return ParkSaved(before, ParkingRules.NO_PERMISSION, false)
        if (!locationOn(context)) return ParkSaved(before, ParkingRules.LOCATION_OFF, false)
        val fixes = try { listen(context) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val now = System.currentTimeMillis()
        val r = ParkingRules.save(before, carId, title, ParkingRules.bestFix(fixes, now), now)
        if (r.saved) store(context, r.spots)
        return r
    }

    private fun Location.toFix(): LocationFix =
        LocationFix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null, System.currentTimeMillis() - ageMs(this))

    private fun ageMs(l: Location): Long = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0)

    /** The fixes of the next few seconds (and a very fresh one the phone already has). */
    @SuppressLint("MissingPermission") // precise() checked by the caller; a late revoke throws, caught there
    private suspend fun listen(context: Context): List<LocationFix> {
        val lm = context.getSystemService(LocationManager::class.java) ?: return emptyList()
        val providers = lm.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == FUSED || it == LocationManager.NETWORK_PROVIDER }
        if (providers.isEmpty()) return emptyList()
        val got = java.util.Collections.synchronizedList(mutableListOf<LocationFix>())
        providers.forEach { p -> lm.getLastKnownLocation(p)?.takeIf { ageMs(it) <= 10_000 }?.let { got += it.toFix() } }
        val listener = LocationListener { got += it.toFix() }
        providers.forEach { p -> lm.requestLocationUpdates(p, 1_000L, 0f, context.mainExecutor, listener) }
        try {
            // Until the settle time, or sooner with a very good fix; and if nothing at all came, a while longer.
            val start = SystemClock.elapsedRealtime()
            while (true) {
                delay(250)
                val waited = SystemClock.elapsedRealtime() - start
                val best = got.toList().mapNotNull { it.accuracyM }.minOrNull()
                if (best != null && best <= GOOD_ENOUGH_M && waited >= 1_500) break
                if (waited >= ParkingRules.SETTLE_MS && got.isNotEmpty()) break
                if (waited >= ParkingRules.FIX_TIMEOUT_MS) break
            }
        } finally {
            lm.removeUpdates(listener)
        }
        return got.toList()
    }

    private const val FUSED = "fused" // LocationManager.FUSED_PROVIDER (API 31)

    /** Where he is, while collected: GPS once a second. Stops when the collector goes (Find my car leaves the screen). */
    @SuppressLint("MissingPermission")
    fun whereIAm(context: Context): Flow<LocationFix> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !precise(context)) { close(); return@callbackFlow }
        val providers = lm.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == FUSED }
            .ifEmpty { lm.getProviders(true).filter { it == LocationManager.NETWORK_PROVIDER } }
        val listener = LocationListener { trySend(it.toFix()) }
        try {
            providers.forEach { p -> lm.getLastKnownLocation(p)?.takeIf { ageMs(it) <= 30_000 }?.let { trySend(it.toFix()) } }
            providers.forEach { p -> lm.requestLocationUpdates(p, 1_000L, 0f, context.mainExecutor, listener) }
        } catch (e: SecurityException) {
            close()
        }
        awaitClose { runCatching { lm.removeUpdates(listener) } }
    }

    /**
     * The phone's heading in degrees from true north, while collected: the rotation vector sensor (no permission),
     * turned for the screen's rotation and corrected by the magnetic declination at [near]. Nothing when the phone has
     * no such sensor.
     */
    fun heading(context: Context, near: () -> LocationFix?): Flow<Double> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) { close(); return@callbackFlow }
        val r = FloatArray(9)
        val remapped = FloatArray(9)
        val o = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                val (x, y) = when (runCatching { context.display?.rotation }.getOrNull() ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(r, x, y, remapped)
                SensorManager.getOrientation(remapped, o)
                val magnetic = Math.toDegrees(o[0].toDouble())
                val declination = near()?.let {
                    GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()
                } ?: 0.0
                trySend(ParkingRules.norm360(magnetic + declination))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }

    /** One buzz on arriving within 15 m of the car. */
    fun buzz(context: Context) {
        runCatching {
            val v = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        }
    }
}
