package os.meka.wear

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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import os.meka.core.domain.LocationFix
import os.meka.core.domain.ParkingRules

/**
 * Find my car on the watch (Needs Meka #35): where the watch is and which way its face points, only while the Find my
 * car screen is showing (it stops the moment that screen goes). Nothing is kept: the fixes and the heading live in the
 * screen's memory. The spot itself comes from the synced copy the Fold shares ([os.meka.core.facade.MekaCore.parkedSpotsView]).
 */
object WatchFindCar {
    private const val FUSED = "fused"

    fun precise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    fun hasCompass(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    private fun ageMs(l: Location): Long = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0)

    private fun Location.toFix(): LocationFix =
        LocationFix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null, System.currentTimeMillis() - ageMs(this))

    /** Where the watch is, once a second while collected (its own GPS, or the phone's through the system). */
    @SuppressLint("MissingPermission") // precise() checked; a late revoke throws, caught below
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
     * Which way the top of the watch face points, degrees from true north, while collected: the rotation vector sensor
     * (no permission), corrected by the magnetic declination at [near]. Nothing on a watch without one.
     */
    fun heading(context: Context, near: () -> LocationFix?): Flow<Double> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) { close(); return@callbackFlow }
        val r = FloatArray(9)
        val o = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                SensorManager.getOrientation(r, o)
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

    /** One double buzz on coming within 15 m of the car. */
    fun buzz(context: Context) {
        runCatching {
            val v = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        }
    }
}
