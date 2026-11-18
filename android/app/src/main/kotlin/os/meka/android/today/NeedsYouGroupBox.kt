package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import os.meka.android.designsystem.LightInk
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace

/*
 * Walk-through 2026-11-15 A17 (last part): To decide's head and its cards were drawn as two boxes with a gap, the head's
 * bottom squared off over nothing. In the approved look (`docs/design/needs-you/needs-you-grouped.dc.html`) a group is
 * one box: the head, then what it holds on the same surface, rounded only at the box's foot. The list keeps its rows as
 * separate items (they glide and fold away one by one), so each row in the box paints the box's surface behind itself and
 * up across the list's gap above it, and a short foot item closes the box with its rounded corners.
 */

/** The list's gap between items, which a row in a box paints over so the box reads as one. */
internal val NEEDS_YOU_BOX_GAP = MekaSpace.xs

internal const val NEEDS_YOU_BOX_END_TAG = "needs-you-box-end"

/** A row inside a group's box: the box's [fill] behind it and across the gap above it, joining it to the row before. */
internal fun Modifier.groupBoxFill(fill: Color, edge: Color? = null): Modifier = drawWithContent {
    val up = NEEDS_YOU_BOX_GAP.toPx()
    drawRect(fill, topLeft = Offset(0f, -up), size = Size(size.width, size.height + up))
    drawContent()
    boxSides(edge)
}

/*
 * Walk-through 2026-11-15, A21 part 5 (Light contrast): the box is the surface on Light's off-white page (1.0:1), so in
 * Light it gets the 1 dp track-ink edge every other card has ([LightInk.cardEdge]); none in Dark. The box is several
 * list items, so each piece draws its share: the head its top (and its sides and foot while closed), each row its two
 * sides across the gap above it (over its content, so a full-width card can't hide them), the foot its sides and
 * rounded bottom.
 */

/** The box's two 1 dp sides down this piece and across the list's gap above it; nothing when [edge] is null (Dark). */
private fun DrawScope.boxSides(edge: Color?) {
    if (edge == null) return
    val up = NEEDS_YOU_BOX_GAP.toPx()
    val w = 1.dp.toPx()
    drawRect(edge, topLeft = Offset(0f, -up), size = Size(w, size.height + up))
    drawRect(edge, topLeft = Offset(size.width - w, -up), size = Size(w, size.height + up))
}

/**
 * The box's outline round this piece: the top with [top] corners when [closedTop], the sides, and the bottom with
 * [bottom] corners when [closedBottom]; an open end runs its sides on across the list's gap ([extendUp] above). A 1 dp
 * stroke on the line half a dp inside the piece, so the clip keeps all of it.
 */
internal fun DrawScope.boxOutline(edge: Color, top: Dp, bottom: Dp, closedTop: Boolean, closedBottom: Boolean, extendUp: Boolean) {
    val w = 1.dp.toPx()
    val h = w / 2f
    val l = h
    val r = size.width - h
    val t = if (extendUp) -NEEDS_YOU_BOX_GAP.toPx() else h
    val b = size.height - h
    val rt = if (closedTop) top.toPx().coerceAtLeast(0f) else 0f
    val rb = if (closedBottom) bottom.toPx().coerceAtLeast(0f) else 0f
    val path = Path().apply {
        // Left side, from the bottom (or its corner) up to the top (or its corner).
        moveTo(l, if (closedBottom) b - rb else size.height)
        lineTo(l, t + rt)
        if (closedTop) {
            if (rt > 0f) arcTo(Rect(l, t, l + 2 * rt, t + 2 * rt), 180f, 90f, false)
            lineTo(r - rt, t)
            if (rt > 0f) arcTo(Rect(r - 2 * rt, t, r, t + 2 * rt), 270f, 90f, false)
        } else {
            moveTo(r, t)
        }
        lineTo(r, if (closedBottom) b - rb else size.height)
        if (closedBottom) {
            if (rb > 0f) arcTo(Rect(r - 2 * rb, b - 2 * rb, r, b), 0f, 90f, false)
            lineTo(l + rb, b)
            if (rb > 0f) arcTo(Rect(l, b - 2 * rb, l + 2 * rb, b), 90f, 90f, false)
        }
    }
    drawPath(path, edge, style = Stroke(width = w))
}

/**
 * A row in a box whose rows share the box's colour (Messages, FYI): the box's [fill] behind it and across the gap above,
 * with the mockup's 1 dp [line] through the middle of that gap, so the rows part as in `needs-you-grouped.dc.html`.
 * [first] leaves the line out under the head or a lead line.
 */
internal fun Modifier.groupBoxRow(fill: Color, line: Color, first: Boolean, edge: Color? = null): Modifier = drawWithContent {
    val up = NEEDS_YOU_BOX_GAP.toPx()
    drawRect(fill, topLeft = Offset(0f, -up), size = Size(size.width, size.height + up))
    if (!first) {
        val inset = MekaSpace.m.toPx()
        drawRect(line, topLeft = Offset(inset, -up / 2f - 0.5f), size = Size((size.width - 2 * inset).coerceAtLeast(0f), 1f))
    }
    drawContent()
    boxSides(edge)
}

/** The foot of a group's box: its rounded bottom corners, joined across the gap to the last row. Decoration only. */
@Composable
internal fun NeedsYouGroupBoxEnd(modifier: Modifier = Modifier) {
    val fill = Meka.colors.surface
    val edge = LightInk.cardEdge(Meka.colors, Meka.theme.isDark)
    Box(
        modifier.fillMaxWidth().height(MekaRadius.m).testTag(NEEDS_YOU_BOX_END_TAG)
            .drawBehind {
                val up = NEEDS_YOU_BOX_GAP.toPx()
                val r = CornerRadius(MekaRadius.m.toPx())
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, -up, size.width, size.height,
                        topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                        bottomRightCornerRadius = r, bottomLeftCornerRadius = r))
                }
                drawPath(path, fill)
                edge?.let { boxOutline(it, 0.dp, MekaRadius.m, closedTop = false, closedBottom = true, extendUp = true) }
            },
    )
}
