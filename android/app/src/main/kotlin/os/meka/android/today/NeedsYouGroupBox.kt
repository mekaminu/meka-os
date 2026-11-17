package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
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
internal fun Modifier.groupBoxFill(fill: Color): Modifier = drawBehind {
    val up = NEEDS_YOU_BOX_GAP.toPx()
    drawRect(fill, topLeft = Offset(0f, -up), size = Size(size.width, size.height + up))
}

/** The foot of a group's box: its rounded bottom corners, joined across the gap to the last row. Decoration only. */
@Composable
internal fun NeedsYouGroupBoxEnd(modifier: Modifier = Modifier) {
    val fill = Meka.colors.surface
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
            },
    )
}
