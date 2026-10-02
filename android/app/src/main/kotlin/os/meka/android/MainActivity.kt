package os.meka.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import os.meka.core.facade.MekaCore
import os.meka.core.sync.SyncStatus
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.ConnectHook
import os.meka.android.today.TodayRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MekaApplication
        setContent {
            MekaTheme {
                var connected by rememberSaveable { mutableStateOf(app.core.isConnected) }
                val sync by app.core.syncStatus.collectAsState()
                // Offer Connect when not enrolled, or when the server signed this device out (re-enrolling fixes it).
                val signedOut = (sync as? SyncStatus.Failing)?.reason == MekaCore.SIGNED_OUT_MESSAGE
                val hook = if (connected && !signedOut) null else ConnectHook(app.defaultServerUrl) { url, code ->
                    app.connect(url, code).also { if (it == null) connected = true }
                }
                TodayRoute(app.core, hook)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as MekaApplication).core.startSync()
    }

    override fun onStop() {
        (application as MekaApplication).core.stopSync()
        super.onStop()
    }
}
