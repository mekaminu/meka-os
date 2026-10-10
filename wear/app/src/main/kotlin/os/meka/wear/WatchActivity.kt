package os.meka.wear

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

/**
 * The watch's one activity (Galaxy Watch, slice 2): the link screen until the watch belongs to a household, then the
 * day (Up next with Done and Tomorrow, the fast with Start or End). Sync runs while it is on screen.
 */
class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WatchApplication
        setContent {
            val core by app.core.collectAsState()
            val reduced = reducedMotion()
            WatchRoot(app, core, reduced)
        }
    }

    /** The watch's "Remove animations" (accessibility) sets the animator scale to 0: cross-fades only. */
    private fun reducedMotion(): Boolean =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
}
