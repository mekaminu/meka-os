package os.meka.android.designsystem

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Walk-through 2026-11-08, A31 (Light contrast): inks for the quiet things that read in Dark but fade on Light's
 * off-white page. Dark keeps its approved look; Light gets the next ink up ([LightInkTest] measures each):
 * - [small]: small grey words (a caption's "34%"): the tertiary ink is 3.9:1 on Light's page, under the 4.5:1 small
 *   text needs, so Light uses the secondary ink.
 * - [barTrack]: a thin bar's empty track: surfaceRaised is 1.1:1 on Light's page, so Light uses the `track` token.
 * - [pillEdge]: a pill or chip filled with the surface on the page (an unchosen tab): 1.0:1 in Light, so it gets a 1 dp
 *   edge in the `track` ink there; none in Dark.
 * - [cardEdge]: walk-through 2026-11-15, A21: a white card (the surface) or a raised tile on Light's off-white page
 *   (1.0–1.1:1) gets the same 1 dp `track` edge; none in Dark, where the surfaces already step up from the page.
 */
object LightInk {
    fun small(c: MekaColors, dark: Boolean): Color = if (dark) c.textTertiary else c.textSecondary
    fun barTrack(c: MekaColors, dark: Boolean): Color = if (dark) c.surfaceRaised else c.track
    fun pillEdge(c: MekaColors, dark: Boolean): Color? = if (dark) null else c.track
    fun cardEdge(c: MekaColors, dark: Boolean): Color? = pillEdge(c, dark)
}

/** Walk-through 2026-11-15, A21: a card's 1 dp edge in Light ([LightInk.cardEdge]); nothing in Dark. */
@Composable
fun Modifier.lightCardEdge(shape: Shape): Modifier {
    val edge = LightInk.cardEdge(Meka.colors, Meka.theme.isDark) ?: return this
    return border(1.dp, edge, shape)
}
