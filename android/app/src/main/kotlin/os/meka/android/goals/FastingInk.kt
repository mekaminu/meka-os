package os.meka.android.goals

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import os.meka.android.designsystem.MekaColors
import os.meka.core.domain.FastingScreenRules

/**
 * The inks of Fasting's rings and stages for one theme (walk-through 2026-11-08, A18: in Light the rings for days to
 * come, the ring's markers ahead, its knob and the stage dots not reached yet were near invisible). Every mark is at
 * least 3:1 on the page and the card, every track at least the `track` token's own floor ([FastingInkTest]).
 */
internal data class FastingInk(
    /** The big ring's and the week rings' empty track: the `track` token (was surfaceRaised, 1.1:1 in Light). */
    val track: Color,
    /** A marker on the ring not reached yet: the tertiary ink whole (was at 0.6, 2.0:1 in Light). */
    val markAhead: Color,
    /** The knob at the sweep's end. */
    val knob: Color,
    /** A ring round the knob, so a pale knob still has an edge on a pale page (Light); null in Dark, as before. */
    val knobEdge: Color?,
    /** The dot of the stage he's in: lifted toward white in Dark, deepened toward black in Light (as the week's today ring). */
    val stageHere: Color,
    /** A stage's dot before it is reached (was the hairline, 1.2:1 in Light). */
    val stageAhead: Color,
    /** How far a not-yet stage row is dimmed while a fast runs ([FastingScreenRules.notYetAlpha]). */
    val notYetAlpha: Float,
) {
    companion object {
        fun of(c: MekaColors, dark: Boolean): FastingInk {
            val light = lerp(c.accent, Color.White, 0.4f)
            return FastingInk(
                track = c.track,
                markAhead = c.textTertiary,
                knob = lerp(light, Color.White, 0.5f),
                knobEdge = if (dark) null else c.accent,
                stageHere = if (dark) lerp(c.accent, Color.White, 0.3f) else lerp(c.accent, Color.Black, 0.25f),
                stageAhead = c.textTertiary,
                notYetAlpha = FastingScreenRules.notYetAlpha(dark),
            )
        }
    }
}
