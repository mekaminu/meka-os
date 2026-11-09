package os.meka.android.today

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.HeaderTile
import os.meka.core.domain.HeaderTileKind

internal const val HEADER_TILES_TAG = "today-header-tiles"
internal const val WEATHER_CHIP_TAG = "today-weather-chip"

/**
 * The Today rebuild, slice 2b: TASKS · NEXT · MAIL across the page under the greeting (core [os.meka.core.domain.TodayHeaderRules]).
 * Each tile presses in (the app's press indication) with a light haptic and opens its place; a figure that changes
 * cross-fades. Quiet surfaces: gold stays for what you act on.
 */
@Composable
internal fun HeaderTilesRow(tiles: List<HeaderTile>, onOpen: (HeaderTileKind) -> Unit, modifier: Modifier = Modifier) {
    if (tiles.isEmpty()) return
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Row(modifier.fillMaxWidth().testTag(HEADER_TILES_TAG), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        tiles.forEach { tile ->
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(MekaRadius.m))
                    .background(Meka.colors.surface)
                    .clickable(role = Role.Button, onClickLabel = openLabel(tile.kind)) { haptics.light(); onOpen(tile.kind) }
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs)
                    .clearAndSetSemantics { contentDescription = tile.spoken; role = Role.Button }
                    .testTag("header-tile-${tile.kind.name.lowercase()}"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                Text(tile.key, style = MekaType.cardLabel, color = Meka.colors.textSecondary, maxLines = 1)
                Crossfade(tile.value, animationSpec = MekaMotion.appear(reduced), label = "header-tile-${tile.kind}") { v ->
                    Text(v, style = MekaType.metaStrong.copy(fontFeatureSettings = "tnum"), color = Meka.colors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(tile.line, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun openLabel(kind: HeaderTileKind): String = when (kind) {
    HeaderTileKind.TASKS -> "Open today's list"
    HeaderTileKind.NEXT -> "Open it"
    HeaderTileKind.MAIL -> "Open mail"
}

/** "14° light rain": the weather as a small pill under the date; cross-fades as it changes. */
@Composable
internal fun WeatherChip(text: String, spoken: String, modifier: Modifier = Modifier) {
    Crossfade(text, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "weather-chip", modifier = modifier) { t ->
        Text(
            t, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs)
                .semantics { contentDescription = spoken }.testTag(WEATHER_CHIP_TAG),
        )
    }
}
