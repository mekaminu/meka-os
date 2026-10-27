package os.meka.android.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp

/**
 * Material's own pieces (menus, the date picker, dialogs, sliders) in MEKA's colours (Meka 2026-10-26 23:18, (5): a
 * white dropdown with near-invisible light text). Without this, Material falls back to its default light scheme while
 * MEKA's text is drawn in the dark theme's light colours. [MekaTheme] provides it app-wide, so every popup follows Dark
 * and Light and blends with the theme switch.
 */
fun mekaColorScheme(c: MekaColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.surfaceRaised, onPrimaryContainer = c.textPrimary,
        secondary = c.accent, onSecondary = c.onAccent, secondaryContainer = c.surface, onSecondaryContainer = c.textPrimary,
        tertiary = c.accent, onTertiary = c.onAccent,
        background = c.background, onBackground = c.textPrimary,
        surface = c.surfaceRaised, onSurface = c.textPrimary, surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary,
        surfaceTint = c.surfaceRaised, inverseSurface = c.textPrimary, inverseOnSurface = c.background,
        surfaceContainerLowest = c.background, surfaceContainerLow = c.surface, surfaceContainer = c.surfaceRaised,
        surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
        surfaceBright = c.surfaceRaised, surfaceDim = c.surface,
        outline = c.hairline, outlineVariant = c.hairline,
        error = c.critical, onError = c.onAccent, scrim = c.background,
    )
}

/** Menus (Material's extraSmall) and dialogs get MEKA's rounded corners instead of Material's 4 dp. */
val MekaMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(MekaRadius.s),
    small = RoundedCornerShape(MekaRadius.s),
    medium = RoundedCornerShape(MekaRadius.m),
    large = RoundedCornerShape(MekaRadius.l),
    extraLarge = RoundedCornerShape(MekaRadius.l),
)

/** A menu's 1 dp hairline edge, so a dark menu still reads against a dark page. */
fun mekaMenuBorder(c: MekaColors) = BorderStroke(1.dp, c.hairline)
