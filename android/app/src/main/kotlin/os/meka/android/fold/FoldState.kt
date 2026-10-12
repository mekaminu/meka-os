package os.meka.android.fold

import android.content.Context
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import os.meka.core.domain.FoldHinge
import os.meka.core.domain.FoldPosture
import os.meka.core.domain.FoldPostureLog
import os.meka.core.domain.FoldPostureRules
import os.meka.core.domain.FoldReading

/**
 * How the Fold is held right now, where its hinge is (window coordinates, px) when it is half open, and the reading it
 * was worked out from ([FoldPostureRules]: WindowManager, the landscape window, the hinge angle sensor).
 */
data class FoldState(val posture: FoldPosture, val hinge: Rect?, val reading: FoldReading? = null) {
    companion object {
        val FLAT = FoldState(FoldPosture.FLAT, null)

        /** WindowManager's word, read into a [FoldReading] with the window's size and the hinge sensor's angle. */
        fun read(info: WindowLayoutInfo?, widthPx: Int, heightPx: Int, angle: Float?, steady: Boolean): Pair<FoldReading, Rect?> {
            val f = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
            val hinge = when {
                f == null -> FoldHinge.NONE
                f.state == FoldingFeature.State.HALF_OPENED -> FoldHinge.HALF_OPENED
                else -> FoldHinge.FLAT
            }
            val reading = FoldReading(
                hinge = hinge,
                across = f?.let { it.orientation == FoldingFeature.Orientation.HORIZONTAL },
                separating = f?.isSeparating,
                occlusion = f?.occlusionType?.toString(),
                windowWidthPx = widthPx, windowHeightPx = heightPx,
                hingeAngle = angle, hingeSteady = steady,
            )
            return reading to f?.bounds?.takeIf { hinge == FoldHinge.HALF_OPENED }
        }
    }
}

/**
 * What the Fold reported last and its last [FoldPostureLog.KEEP] changes (kept in the phone's preferences), for the
 * diagnostics line in Appearance and Health. Process-wide so the panes can read it without the shell.
 */
object FoldDiagnostics {
    private const val PREFS = "meka_fold"
    private const val KEY_LOG = "posture_log"

    private val _reading = MutableStateFlow<FoldReading?>(null)
    val reading: StateFlow<FoldReading?> = _reading
    private val _log = MutableStateFlow<List<FoldPostureLog.Change>?>(null)

    fun log(context: Context): List<FoldPostureLog.Change> = _log.value
        ?: FoldPostureLog.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, null)).also { _log.value = it }

    fun record(context: Context, r: FoldReading) {
        _reading.value = r
        val before = log(context)
        val after = FoldPostureLog.add(before, r, System.currentTimeMillis())
        if (after !== before) {
            _log.value = after
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LOG, FoldPostureLog.encode(after)).apply()
        }
    }

    /** The line for Appearance and Health; null away from MEKA's window (nothing read yet). */
    fun line(): String? = _reading.value?.let { FoldPostureRules.line(it) }
}

/** The hinge angle sensor's readings (`Sensor.TYPE_HINGE_ANGLE`, no permission); null on a phone without one. */
@Composable
private fun rememberHingeAngle(): Pair<Float?, Boolean> {
    val context = LocalContext.current
    var angle by remember { mutableStateOf<Float?>(null) }
    var steady by remember { mutableStateOf(false) }
    val samples = remember { ArrayList<Pair<Long, Float>>() }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                val a = event.values.firstOrNull() ?: return
                synchronized(samples) {
                    samples += now to a
                    val kept = FoldPostureRules.trim(samples.toList(), now)
                    samples.clear(); samples.addAll(kept)
                    steady = FoldPostureRules.steady(kept, now)
                }
                angle = a
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm?.unregisterListener(listener) }
    }
    // The hinge sensor only reports changes: once it stops moving, look again so a held angle becomes steady.
    LaunchedEffect(angle) {
        if (angle == null) return@LaunchedEffect
        delay(FoldPostureRules.STEADY_MS + 100)
        val now = System.currentTimeMillis()
        synchronized(samples) { steady = FoldPostureRules.steady(samples.toList(), now) }
    }
    return angle to steady
}

/**
 * The Fold's posture from Jetpack WindowManager (official API; no special permission), the window's shape and the
 * hinge angle sensor ([FoldPostureRules.posture]). Flat outside an activity. Every change is logged for diagnostics.
 */
@Composable
fun rememberFoldState(): FoldState {
    val activity = LocalActivity.current ?: return FoldState.FLAT
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(initial = null)
    val (angle, steady) = rememberHingeAngle()
    // The window's size: read again whenever WindowManager's layout or the configuration changes.
    val bounds = remember(info, androidx.compose.ui.platform.LocalConfiguration.current) {
        WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
    }
    val state = remember(info, bounds, angle?.toInt(), steady) {
        val (reading, hinge) = FoldState.read(info, bounds.width(), bounds.height(), angle, steady)
        FoldState(FoldPostureRules.posture(reading).posture, hinge, reading)
    }
    LaunchedEffect(state.reading) { state.reading?.let { FoldDiagnostics.record(activity.applicationContext, it) } }
    return state
}
