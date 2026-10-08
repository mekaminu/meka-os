package os.meka.android.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import os.meka.core.domain.MotionCard
import os.meka.core.domain.MotionChoice
import os.meka.core.domain.MotionRules

val LocalMekaColors = staticCompositionLocalOf { MekaDarkColors }
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalExpressiveMotion = staticCompositionLocalOf { false }
val LocalMotionControl = staticCompositionLocalOf { MotionControl(null, false) {} }
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
 * Appearance → Motion on this phone (motion pass 2): Expressive · Subtle · Off, kept like the theme. [stored] is null
 * until Meka chooses; [systemOff] is the phone's "Remove animations" (animator duration scale 0), which only decides
 * while nothing is chosen (`MotionRules`).
 */
class MotionControl(val stored: MotionChoice?, val systemOff: Boolean, val set: (MotionChoice) -> Unit) {
    val effective: MotionChoice get() = MotionRules.effective(stored, systemOff)
    val line: String get() = MotionRules.line(stored, systemOff, mac = false)
    /** Today's one-time card, while the phone's animations are off and nothing is chosen. */
    val card: MotionCard? get() = MotionRules.systemCard(stored, systemOff, mac = false)
}

/** The stored Motion choice, shared by every composition in the process so a change shows everywhere at once. */
object MotionPrefs {
    private const val PREFS = "meka_ui"
    private const val KEY = "motion"
    private var loaded: MutableState<MotionChoice?>? = null

    fun state(context: Context): MutableState<MotionChoice?> = loaded ?: mutableStateOf(
        MotionRules.choice(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)),
    ).also { loaded = it }

    fun set(context: Context, choice: MotionChoice) {
        state(context).value = choice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.id).apply()
    }

    /** The phone's "Remove animations": animator duration scale 0. */
    fun systemOff(context: Context): Boolean = animatorScale(context) == 0f

    /** Developer options → Animator duration scale (1 when never changed; 0 is "Remove animations"). */
    fun animatorScale(context: Context): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /** The phone's power saving (Samsung's included). Reported in Motion check; it never stills MEKA. */
    fun powerSave(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
}

/**
 * Follows MEKA's own Motion setting (Appearance → Motion), not the phone's animator scale: the activity's recomposer
 * plays animations at full speed (MotionClock.kt) and Off means cross-fades only. With nothing chosen MEKA plays
 * Expressive whatever the phone's "Remove animations" says (Meka, 2026-10-08); only an explicit Off keeps it still.
 * Switching theme blends every colour across instead of snapping. Every clickable presses in while held
 * ([MekaPressIndication]).
 */
@Composable
fun MekaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemOff = remember { MotionPrefs.systemOff(context) }
    val stored by MotionPrefs.state(context.applicationContext)
    val motion = MotionControl(stored, systemOff) { MotionPrefs.set(context.applicationContext, it) }
    val reduced = MotionRules.reduced(stored, systemOff)
    val expressive = MotionRules.expressive(stored, systemOff)
    // The bouncier complete/approve springs read this when a spec is made (generated MekaMotion).
    SideEffect { MotionStyle.expressive = expressive }
    var choice by remember { mutableStateOf(ThemeChoice.load(context)) }
    val dark = when (choice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }
    val t by animateFloatAsState(if (dark) 1f else 0f, MekaMotion.themeBlend(reduced), label = "theme")
    val colors = blend(MekaLightColors, MekaDarkColors, t)
    val control = ThemeControl(choice, dark) { choice = it; ThemeChoice.save(context, it) }
    // Feedback motion: every clickable presses in (0.97) while held (PressIndication.kt).
    val press = remember(reduced, colors.textPrimary) { MekaPressIndication(reduced, colors.textPrimary) }
    CompositionLocalProvider(
        LocalMekaColors provides colors,
        LocalReducedMotion provides reduced,
        LocalExpressiveMotion provides expressive,
        LocalMotionControl provides motion,
        LocalThemeControl provides control,
        LocalIndication provides press,
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
        barca = lerp(a.barca, b.barca, t),
        calendar1 = lerp(a.calendar1, b.calendar1, t),
        calendar2 = lerp(a.calendar2, b.calendar2, t),
        calendar3 = lerp(a.calendar3, b.calendar3, t),
        calendar4 = lerp(a.calendar4, b.calendar4, t),
        calendar5 = lerp(a.calendar5, b.calendar5, t),
    )
}

/** A calendar's dot colour for a [os.meka.core.domain.CalendarTones] value: fixtures in Barça's colour, else 1–5. */
fun MekaColors.calendarTone(tone: Int): Color = when (tone) {
    os.meka.core.domain.CalendarTones.FIXTURE -> barca
    1 -> calendar1
    2 -> calendar2
    3 -> calendar3
    4 -> calendar4
    else -> calendar5
}

object Meka {
    val colors: MekaColors @Composable get() = LocalMekaColors.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
    /** Appearance → Motion → Expressive: bigger entrances and bouncier springs. */
    val expressiveMotion: Boolean @Composable get() = LocalExpressiveMotion.current
    val motion: MotionControl @Composable get() = LocalMotionControl.current
    val theme: ThemeControl @Composable get() = LocalThemeControl.current
}
