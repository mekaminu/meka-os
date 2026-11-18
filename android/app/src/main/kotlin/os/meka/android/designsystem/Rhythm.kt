package os.meka.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Calm Today's spacing rhythm (ADR-012 addendum 2026-10-10): a tappable row or chip insets only xs (8) around its
 * content and is never shorter than [MekaSpace.touch] (48); its content sits centred in that height. Put it after
 * `clickable` so the whole height takes the tap.
 */
fun Modifier.minTouch(): Modifier = heightIn(min = MekaSpace.touch).wrapContentHeight(Alignment.CenterVertically)

/**
 * Walk-through 2026-11-15 A20: a one-line link on Today (the battery line, the folded Connect line, the sign-in line)
 * takes a whole 48 dp tap but keeps the height it had in the layout, so nothing below it moves (a plain [minTouch]
 * there pushed the open Fold's Schedule below Health and Setup). The target bleeds evenly above and below the line
 * into the gaps round it; Compose hands a touch to a child laid out past its parent's edge unless something clips.
 * Put it before `clickable`, and `wrapContentHeight()` after it so the words stay centred in the taller target.
 *
 * [line] is where the line's own height sits in the target: centred by default (the target bleeds evenly), or
 * [Alignment.Top] when only the gap below the line is free (the target hangs below it; pass the same alignment to
 * `wrapContentHeight` after `clickable`), as under the cover's "now" card, whose own bottom edge sits right above.
 */
fun Modifier.minTouchInPlace(line: Alignment.Vertical = Alignment.CenterVertically): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
    val natural = measurable.minIntrinsicHeight(width).coerceIn(constraints.minHeight, constraints.maxHeight)
    val target = maxOf(natural, MekaSpace.touch.roundToPx())
    val p = measurable.measure(constraints.copy(minHeight = target, maxHeight = maxOf(target, constraints.maxHeight)))
    // Cancels wrapContentHeight's alignment to the pixel, so the words sit exactly where they were.
    layout(p.width, natural) { p.place(0, -line.align(natural, p.height)) }
}

/**
 * A glyph button ("⋯", "⌄") is a whole 48 × 48 target with the glyph centred, never only as wide as the glyph
 * (walk-through 2026-11-02 A9: Trip lists' "⋯" had about 10 dp to hit). Put it after `clickable`, like [minTouch].
 */
fun Modifier.minTouchSquare(): Modifier =
    sizeIn(minWidth = MekaSpace.touch, minHeight = MekaSpace.touch).wrapContentSize(Alignment.Center)

/**
 * Walk-through 2026-11-15 A20: a small drawn glyph button ([drawn] wide, e.g. a Life page title's 24 dp ⓘ) takes a whole
 * 48 × 48 tap but keeps only its drawn width in the row, so the words beside it keep their room (a plain
 * [minTouchSquare] there took 24 dp more and broke "Appointments" across two lines on the closed Fold). The target
 * bleeds evenly left and right of the glyph; Compose hands a touch to a child laid out past its slot unless something
 * clips. Put it first, before `clip` and `clickable`, so the clip and the tap are the whole square.
 */
fun Modifier.minTouchSquareInPlace(drawn: Dp): Modifier = layout { measurable, constraints ->
    val touch = MekaSpace.touch.roundToPx()
    val p = measurable.measure(Constraints.fixed(touch, maxOf(touch, constraints.minHeight)))
    val slot = drawn.roundToPx().coerceIn(constraints.minWidth, maxOf(constraints.minWidth, constraints.maxWidth))
    layout(slot, p.height) { p.place((slot - p.width) / 2, 0) }
}

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

/**
 * Calmer Today, slice 2 (the approved mockup's Up next buttons and update banner): a compact pill is drawn
 * [PillSize.DRAWN] (36 dp) tall inside the [MekaSpace.touch] (48 dp) tap target, which reaches [PillSize.INSET] above
 * and below the drawn pill; a card whose last row is pills trims its bottom padding by that much so it looks even.
 */
object PillSize {
    val DRAWN: Dp = 36.dp
    val INSET: Dp = (MekaSpace.touch - DRAWN) / 2
}

/** Lays this out [start] further left than its slot and that much narrower, so it bleeds into the row's inset. */
fun Modifier.bleedStart(start: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val s = start.roundToPx()
    layout((placeable.width - s).coerceAtLeast(0), placeable.height) { placeable.place(-s, 0) }
}

/**
 * Words you tap straight on the page or in a card (the date, a link line, a course's rows in the study check-in): the
 * tap's rounded highlight reaches [TextTap.BLEED] past the words on each side and insets them by the same, so the words
 * stay exactly where they were and the clip's corners never cut a first or last letter (Meka 2026-11-08 22:09, Today:
 * "Orochi" showed as "Jrochi" and "Next week" as "\ext week", because a 16 dp rounded clip sat right on the words).
 * Use it in place of `clip(RoundedCornerShape(..)).clickable(..)` on words.
 */
fun Modifier.wordsTap(role: Role? = Role.Button, onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    bleedSides(TextTap.BLEED)
        .clip(RoundedCornerShape(TextTap.RADIUS))
        .clickable(role = role, onClickLabel = onClickLabel, onClick = onClick)
        .padding(horizontal = TextTap.BLEED)

/** [wordsTap]'s sizes: the bleed is at least the corner's reach on a one-line row, so the curve stays clear of the words. */
object TextTap {
    val BLEED: Dp = MekaSpace.xs
    val RADIUS: Dp = MekaRadius.s
}

/** Lays this out [by] wider on each side than its slot, reporting the slot's own size, so it bleeds into the insets. */
fun Modifier.bleedSides(by: Dp): Modifier = layout { measurable, constraints ->
    val px = by.roundToPx()
    val wider = constraints.copy(
        minWidth = if (constraints.minWidth > 0) constraints.minWidth + 2 * px else 0,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * px else constraints.maxWidth,
    )
    val p = measurable.measure(wider)
    layout((p.width - 2 * px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}
