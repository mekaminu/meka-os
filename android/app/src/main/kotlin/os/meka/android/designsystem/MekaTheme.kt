package os.meka.android.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

val LocalMekaColors = staticCompositionLocalOf { MekaDarkColors }
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalThemeControl = staticCompositionLocalOf { ThemeControl(ThemeChoice.DARK, false) {} }

/** The owner's appearance choice. Dark is the default: it is the look MEKA OS is designed around. */
enum class ThemeChoice(val label: String) {
    DARK("Dark"), LIGHT("Light"), SYSTEM("Auto");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "meka_ui"
        private const val KEY = "theme"
        fun load(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { s -> entries.firstOrNull { it.name == s } } ?: DARK
        fun save(context: Context, choice: ThemeChoice) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.name).apply()
    }
}

class ThemeControl(val choice: ThemeChoice, val isDark: Boolean, val set: (ThemeChoice) -> Unit)

/**
 * Reads the system "Remove animations" setting (animator duration scale = 0) to honour reduced motion.
 * Switching theme blends every colour across instead of snapping.
 */
@Composable
fun MekaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var choice by remember { mutableStateOf(ThemeChoice.load(context)) }
    val dark = when (choice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }
    val t by animateFloatAsState(if (dark) 1f else 0f, MekaMotion.themeBlend(reduced), label = "theme")
    val colors = blend(MekaLightColors, MekaDarkColors, t)
    val control = ThemeControl(choice, dark) { choice = it; ThemeChoice.save(context, it) }
    CompositionLocalProvider(
        LocalMekaColors provides colors,
        LocalReducedMotion provides reduced,
        LocalThemeControl provides control,
        content = content,
    )
}

private fun blend(a: MekaColors, b: MekaColors, t: Float): MekaColors = when (t) {
    0f -> a
    1f -> b
    else -> MekaColors(
        background = lerp(a.background, b.background, t),
        surface = lerp(a.surface, b.surface, t),
        surfaceRaised = lerp(a.surfaceRaised, b.surfaceRaised, t),
        hairline = lerp(a.hairline, b.hairline, t),
        textPrimary = lerp(a.textPrimary, b.textPrimary, t),
        textSecondary = lerp(a.textSecondary, b.textSecondary, t),
        textTertiary = lerp(a.textTertiary, b.textTertiary, t),
        accent = lerp(a.accent, b.accent, t),
        onAccent = lerp(a.onAccent, b.onAccent, t),
        critical = lerp(a.critical, b.critical, t),
        approval = lerp(a.approval, b.approval, t),
        offline = lerp(a.offline, b.offline, t),
        success = lerp(a.success, b.success, t),
    )
}

object Meka {
    val colors: MekaColors @Composable get() = LocalMekaColors.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
    val theme: ThemeControl @Composable get() = LocalThemeControl.current
}
