package os.meka.wear

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import os.meka.core.domain.WatchCaptureRules

/**
 * The watch's one activity (Galaxy Watch, slice 2): the link screen until the watch belongs to a household, then the
 * day (Up next with Done and Tomorrow, Capture, the fast with Start or End). Sync runs while it is on screen. Opened
 * from the tile's Capture (slice 4a), it starts listening straight away.
 */
class WatchActivity : ComponentActivity() {
    private var listen by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WatchApplication
        if (savedInstanceState == null) take(intent)
        setContent {
            val core by app.core.collectAsState()
            val reduced = reducedMotion()
            WatchRoot(app, core, reduced, listen, onListen = { listen = 0 })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        if (intent?.getBooleanExtra(WatchCaptureRules.LISTEN_EXTRA, false) == true) {
            listen++
            intent.removeExtra(WatchCaptureRules.LISTEN_EXTRA)
        }
    }

    /** The watch's "Remove animations" (accessibility) sets the animator scale to 0: cross-fades only. */
    private fun reducedMotion(): Boolean =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
}
