package os.meka.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import os.meka.android.designsystem.Meka
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
import os.meka.android.shell.AppShell
import os.meka.android.shell.ShellDestination
import os.meka.android.today.ConnectHook

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MekaApplication
        handleOpen(intent)
        setContent {
            MekaTheme {
                // Status-bar icons follow MEKA's own theme, not the phone's.
                val dark = Meka.theme.isDark
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                var connected by rememberSaveable { mutableStateOf(app.core.isConnected) }
                val sync by app.core.syncStatus.collectAsState()
                // Offer Connect when not enrolled, or when the server signed this device out (re-enrolling fixes it).
                val signedOut = (sync as? SyncStatus.Failing)?.reason == MekaCore.SIGNED_OUT_MESSAGE
                val hook = if (connected && !signedOut) null else ConnectHook(app.defaultServerUrl) { url, code ->
                    app.connect(url, code).also { if (it == null) connected = true }
                }
                AppShell(app.core, hook)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** The after-work nudge opens Needs you with the summary; other MEKA notifications open where they belong. */
    private fun handleOpen(intent: Intent?) {
        if (intent == null) return
        val open = intent.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN) // not again on rotation
        val app = application as MekaApplication
        when {
            open == OPEN_AFTER_WORK -> app.openAfterWork.value = true
            open.startsWith(OPEN_DESTINATION_PREFIX) ->
                ShellDestination.entries.firstOrNull { it.name == open.removePrefix(OPEN_DESTINATION_PREFIX) }?.let { app.openDestination.value = it }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as MekaApplication
        app.isOnScreen = true
        app.core.startSync()
        // Self-updating phone app: look for a newer build, and bring back Android's Install prompt if one is waiting.
        app.lookForUpdate()
        app.updater.showPrompt(this)
    }

    override fun onStop() {
        val app = application as MekaApplication
        app.isOnScreen = false
        app.core.stopSync()
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN = "os.meka.open"
        const val OPEN_AFTER_WORK = "after_work"
        /** "dest:LISTS" opens Lists. */
        const val OPEN_DESTINATION_PREFIX = "dest:"
    }
}
