package os.meka.android.designsystem

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Calm Today's spacing rhythm (ADR-012 addendum 2026-10-10): a tappable row or chip insets only xs (8) around its
 * content and is never shorter than [MekaSpace.touch] (48); its content sits centred in that height. Put it after
 * `clickable` so the whole height takes the tap.
 */
fun Modifier.minTouch(): Modifier = heightIn(min = MekaSpace.touch).wrapContentHeight(Alignment.CenterVertically)

/**
 * Calmer Today (Meka 2026-10-25 15:03: "the circle check box is too large"): a task row's tick is drawn [TickSize.DRAWN]
 * (22 dp) inside a [TickSize.TOUCH] (44 dp) tap target. In a row that starts with the tick, the target bleeds
 * [TickSize.BLEED] into the row's start so the drawn circle lines up with the section label above, and the text sits
 * the target's own inset (11 dp, about the mockup's 12) after it, with no spacer.
 */
object TickSize {
    val DRAWN: Dp = 22.dp
    val TOUCH: Dp = 44.dp
    val BLEED: Dp = (TOUCH - DRAWN) / 2
}

/** Lays this out [start] further left than its slot and that much narrower, so it bleeds into the row's inset. */
fun Modifier.bleedStart(start: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val s = start.roundToPx()
    layout((placeable.width - s).coerceAtLeast(0), placeable.height) { placeable.place(-s, 0) }
}
