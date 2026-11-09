package os.meka.android.today

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AlsoKind
import os.meka.core.domain.AlsoTile
import os.meka.core.domain.AlsoTodayRules

internal const val ALSO_TODAY_TAG = "today-also"

/** How wide each Also today tile is (the mockup's 150 px), so the next one peeks in from the edge. */
private val TILE_WIDTH = 150.dp

/**
 * The Today rebuild, slice 2b-2: ALSO TODAY, one side-swiping row of small tiles below the schedule (core
 * [AlsoTodayRules]): a coloured dot and small caps key, the value, the quieter line under it. A tile with somewhere to
 * go (study, the week ahead, a renewal) presses in with a light haptic and opens it; a value that changes cross-fades.
 * The dot is gold only for something to do today, so gold still means "act here".
 */
@Composable
internal fun AlsoTodayRow(tiles: List<AlsoTile>, onOpen: (AlsoTile) -> Unit, opens: (AlsoTile) -> Boolean, modifier: Modifier = Modifier) {
    if (tiles.isEmpty()) return
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth().testTag(ALSO_TODAY_TAG), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(AlsoTodayRules.LABEL, style = MekaType.cardLabel, color = Meka.colors.textSecondary, maxLines = 1,
            modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            tiles.forEach { tile ->
                val tappable = opens(tile)
                Column(
                    Modifier.width(TILE_WIDTH)
                        .testTag("also-tile-${tile.kind.name.lowercase()}")
                        .clip(RoundedCornerShape(MekaRadius.m))
                        .background(Meka.colors.surface)
                        .then(if (tappable) Modifier.clickable(role = Role.Button) { haptics.light(); onOpen(tile) } else Modifier)
                        .padding(MekaSpace.xs)
                        .clearAndSetSemantics { contentDescription = tile.spoken; if (tappable) role = Role.Button },
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColour(tile)))
                        Text(tile.key, style = MekaType.cardLabel, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Crossfade(tile.value, animationSpec = MekaMotion.appear(reduced), label = "also-${tile.kind}") { v ->
                        Text(v, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (tile.line.isNotEmpty()) {
                        Text(tile.line, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun dotColour(tile: AlsoTile): Color = if (tile.lit) Meka.colors.accent else when (tile.kind) {
    AlsoKind.STUDY, AlsoKind.REFLECTION -> Meka.colors.calendar1
    AlsoKind.RENEWAL, AlsoKind.PARCELS -> Meka.colors.calendar3
    AlsoKind.WEEK_AHEAD, AlsoKind.TRIP, AlsoKind.LIFE_ADMIN -> Meka.colors.calendar2
    AlsoKind.BINS, AlsoKind.DINNER -> Meka.colors.calendar4
    AlsoKind.GIFTS, AlsoKind.DATE_NIGHT -> Meka.colors.calendar5
}
