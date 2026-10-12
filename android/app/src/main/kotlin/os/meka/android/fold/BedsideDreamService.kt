package os.meka.android.fold

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import os.meka.android.MekaApplication
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.mekaRecomposer
import os.meka.core.domain.BedsideDreamRules

/**
 * The bedside clock as Android's screen saver (bedside clock on the Fold 8, slice 3; [BedsideDreamRules]): Android
 * starts it by itself while the phone charges and sits idle, once Meka picks it in Settings → Display → Screen saver.
 * It shows [BedsideDream]: the clock, the date, the watch face, the next alarm and this part of the day's lines, with
 * nothing to press. It isn't interactive: a touch ends it and the phone wakes to its lock screen, so nothing acts
 * without unlocking. In quiet hours the screen drops to its dimmest ([DreamService.setScreenBright]).
 *
 * A DreamService is no activity, so it hosts Compose itself: its own lifecycle and saved-state owners on the window,
 * and MEKA's motion clock ([mekaRecomposer]) so MEKA's Motion setting applies here too.
 */
class BedsideDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val core = (application as MekaApplication).core
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BedsideDreamService)
            setViewTreeSavedStateRegistryOwner(this@BedsideDreamService)
            setParentCompositionContext(mekaRecomposer())
            setContent { MekaTheme { BedsideDream(core, onDim = { dim -> isScreenBright = !dim }) } }
        }
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        /**
         * Whether MEKA is the screen saver, for Setup: Android's own two settings, read without any permission (null
         * when this phone doesn't let them be read).
         */
        fun isMeka(context: Context): Boolean? {
            fun read(name: String): String? = runCatching { Settings.Secure.getString(context.contentResolver, name) }.getOrNull()
            return BedsideDreamRules.isMeka(read("screensaver_enabled"), read("screensaver_components"), context.packageName)
        }

        /** Setup's Take me there: Android's screen saver page (Samsung: Display → Screen saver), else Display. */
        fun openSettings(context: Context) {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val opened = runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(flags)) }.isSuccess
            if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(flags)) }
        }
    }
}
