package os.meka.android.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/*
 * MEKA's own motion clock (motion pass 2). Compose normally scales every animation by the phone's animator duration
 * scale, so "Remove animations" (scale 0) snaps everything to its end and Meka saw no motion at all. MEKA follows its
 * own Appearance → Motion setting instead (MekaTheme; Off = cross-fades only), so the activity's compositions run on
 * a recomposer whose MotionDurationScale is always 1. Like the window's own recomposer, its frame clock pauses while
 * the activity is stopped, so nothing animates off screen.
 */

private object FullSpeed : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** Pass to `setContent(parent = …)`. Lives until the activity is destroyed. */
fun ComponentActivity.mekaRecomposer(): Recomposer {
    val ui = AndroidUiDispatcher.CurrentThread
    val clock = PausableMonotonicFrameClock(ui[MonotonicFrameClock]!!)
    val context = ui + clock + FullSpeed
    val recomposer = Recomposer(context)
    val scope = CoroutineScope(context)
    scope.launch { recomposer.runRecomposeAndApplyChanges() }
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> clock.resume()
            Lifecycle.Event.ON_STOP -> clock.pause()
            Lifecycle.Event.ON_DESTROY -> {
                recomposer.cancel()
                scope.cancel()
            }
            else -> Unit
        }
    })
    return recomposer
}
