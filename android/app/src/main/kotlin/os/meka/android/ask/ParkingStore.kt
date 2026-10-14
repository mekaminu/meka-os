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

    // ---- slice 4: the bay's photo (files/parking/, never backed up: data_extraction_rules.xml) ----

    private const val PHOTO_DIR = "parking"
    private const val CAPTURE = "capture.jpg"

    private fun photoDir(context: Context): java.io.File = java.io.File(context.filesDir, PHOTO_DIR).apply { mkdirs() }

    fun photoFile(context: Context, carId: String): java.io.File = java.io.File(photoDir(context), ParkingRules.photoFile(carId))

    /**
     * Where the camera app writes the picture: a content URI (the FileProvider shared with updates) over one scratch
     * file, so the camera never needs storage access and no copy lands in the gallery.
     */
    fun captureUri(context: Context): android.net.Uri {
        val f = java.io.File(photoDir(context), CAPTURE)
        runCatching { f.delete() }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", f)
    }

    /**
     * Keeps the picture just taken as [carId]'s bay photo: decoded upright (ImageDecoder follows the camera's
     * orientation), shrunk to [ParkingRules.PHOTO_MAX_PX] and written as a fresh JPEG, so the camera's location tag and
     * other metadata don't come with it. The scratch file is deleted either way. False when there's nothing to keep.
     */
    suspend fun keepPhoto(context: Context, carId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val src = java.io.File(photoDir(context), CAPTURE)
        try {
            if (!src.exists() || src.length() == 0L) return@withContext false
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(src)) { d, info, _ ->
                val (w, h) = ParkingRules.photoSize(info.size.width, info.size.height)
                d.setTargetSize(w, h)
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = photoFile(context, carId)
            val tmp = java.io.File(out.parentFile, out.name + ".part")
            tmp.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            tmp.renameTo(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { src.delete() }
        }
    }

    /** [carId]'s bay photo, shrunk to about [maxPx] on its long side; null without one. Never throws. */
    suspend fun loadPhoto(context: Context, carId: String, maxPx: Int): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = photoFile(context, carId)
            if (!f.exists()) return@withContext null
            runCatching {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f)) { d, info, _ ->
                    val (w, h) = ParkingRules.photoSize(info.size.width, info.size.height, maxPx)
                    d.setTargetSize(w, h)
                }
            }.getOrNull()
        }

    /**
     * The words on [carId]'s bay photo, read on the phone by ML Kit's bundled text recognition (the picture never leaves
     * the phone); null when nothing could be read within a few seconds. Never throws.
     */
    suspend fun readSign(context: Context, carId: String): String? {
        val bitmap = loadPhoto(context, carId, ParkingRules.PHOTO_MAX_PX) ?: return null
        val soft = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bitmap
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS,
        )
        return try {
            kotlinx.coroutines.withTimeoutOrNull(SIGN_READ_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(soft ?: bitmap, 0))
                        .addOnSuccessListener { t -> if (cont.isActive) cont.resumeWith(Result.success(t.text)) }
                        .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                        .addOnCanceledListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private const val SIGN_READ_MS = 6_000L

    /** Deletes the bay photos no parked car uses ([keep]: a Drove off still offering Undo). Never throws. */
    fun tidyPhotos(context: Context, spots: Map<String, ParkedSpot>, keep: ParkedSpot? = null) {
        runCatching {
            val dir = photoDir(context)
            val names = dir.list()?.toList().orEmpty()
            ParkingRules.photosToDelete(spots, names, keep).forEach { java.io.File(dir, it).delete() }
        }
    }

    /**
     * "I've parked here" for [carId]: a few seconds of fixes, the best saved. Never throws. [maxMs]: how long to wait
     * for a first fix (slice 3: the car's Bluetooth disconnecting saves by itself in at most 15 s).
     */
    suspend fun parkHere(context: Context, carId: String, title: String, maxMs: Long = ParkingRules.FIX_TIMEOUT_MS): ParkSaved {
        val before = spots(context)
        if (!precise(context)) return ParkSaved(before, ParkingRules.NO_PERMISSION, false)
        if (!locationOn(context)) return ParkSaved(before, ParkingRules.LOCATION_OFF, false)
        val fixes = try { listen(context, maxMs) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
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
    private suspend fun listen(context: Context, maxMs: Long): List<LocationFix> {
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
                if (waited >= maxMs) break
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
