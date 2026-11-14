package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MotionMath
import os.meka.core.domain.AddChoice
import os.meka.core.domain.LifePageRules
import os.meka.core.domain.PageCard
import os.meka.core.domain.PageRow
import os.meka.core.domain.PageTile

/**
 * Every page under Ask in one shape (approved 2026-11-11, `docs/design/look/LifePage.dc.html`): the title with an ⓘ
 * that unfolds what the page does, one gold + Add, three number tiles, then cards of at most five rows. The pieces
 * here draw a [os.meka.core.domain.LifePageView]; each page keeps what its rows and + Add's choices do.
 *
 * Motion: the ⓘ's words unfold on the expand spring; rows arrive with the page's item motion; the sheets' scrim fades
 * and the sheet rises on the expand spring. Reduced motion: cross-fades.
 */
const val LIFE_ADD_SHEET_TAG = "life-add-sheet"

/** The title row: the page's title (with [titleModifier]'s shared motion), the ⓘ and + Add; the ⓘ's words under it. */
@Composable
internal fun LifePageHeader(
    title: String, info: String, infoOpen: Boolean, titleModifier: Modifier = Modifier,
    onInfo: () -> Unit, onAdd: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = titleModifier.semantics { heading() })
            Box(
                Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onInfo)
                    .semantics {
                        contentDescription = LifePageRules.INFO_LABEL
                        stateDescription = if (infoOpen) "Shown" else "Hidden"
                    }
                    .minTouch(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(MekaSpace.l).border(1.dp, Meka.colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("i", style = MekaType.caption, color = Meka.colors.accent) }
            }
            Box(Modifier.weight(1f))
            Text(
                LifePageRules.ADD, style = MekaType.metaStrong, color = Meka.colors.onAccent, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onAdd)
                    .semantics { contentDescription = "Add" }
                    .minTouch().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
        AnimatedVisibility(
            infoOpen,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Text(
                LifePageRules.shown(info), style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

/** Each number tile's tag (the screen tests check the three share one height). */
internal const val LIFE_TILE_TAG = "life-page-tile"

/**
 * The three number tiles in a row: the number (brass when lit) over its words. The row is as tall as its tallest
 * tile and every tile fills it, so a one-line label's tile is never shorter than its neighbours (walk-through
 * 2026-11-08 A19: "7 days / to Seville" sat short beside two-line "None booked for Seville").
 */
@Composable
internal fun LifeNumberTiles(tiles: List<PageTile>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        tiles.forEach { t ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
                    .testTag(LIFE_TILE_TAG)
                    .semantics(mergeDescendants = true) {}
                    .padding(MekaSpace.xs),
            ) {
                // One line, whole ("Not parked", "Level 3 · Row F"), stepping down on the closed Fold's narrow tiles.
                BasicText(
                    t.number, style = MekaType.metaStrong.copy(color = if (t.lit) Meka.colors.accent else Meka.colors.textPrimary),
                    maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MekaType.metaStrong.fontSize, stepSize = 0.5.sp), // type: ok (shrinks to fit, never below 10)
                )
                Text(LifePageRules.tileLabel(t.label), style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One card: its title and count, the gold link at the top right ([PageCard.more], "See all", "Find my car"), then at
 * most five rows (name, grey line, the day or time on the right), or the card's empty line.
 */
@Composable
internal fun LifePageCard(card: PageCard, modifier: Modifier = Modifier, onMore: () -> Unit, onRow: (PageRow) -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
            Text(
                "${card.count}", style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                    .background(Meka.colors.surfaceRaised).padding(horizontal = MekaSpace.xs),
            )
            Box(Modifier.weight(1f))
            card.more?.let { m ->
                Text("$m ›", style = MekaType.caption, color = Meka.colors.accent, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onMore)
                        .minTouch().padding(horizontal = MekaSpace.xs))
            }
        }
        if (card.rows.isEmpty()) {
            card.empty?.let {
                Text(LifePageRules.shown(it), style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(vertical = MekaSpace.xs))
            }
        }
        card.rows.forEach { r ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.hairline))
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onRow(r) }
                    .semantics(mergeDescendants = true) {}
                    .minTouch().padding(vertical = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // A row's name is matched as typed (search, the tests), so it wraps freely; the empty line and tiles are held.
                    // Two lines, so a long title (a book's) is read before it's cut (the quality gate's "cut after 0 characters").
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.line.isNotEmpty()) {
                        Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (r.whenText.isNotEmpty()) {
                    Text(r.whenText, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary,
                        maxLines = 1, softWrap = false, modifier = Modifier.padding(start = MekaSpace.xs))
                }
            }
        }
    }
}

/**
 * A sheet over the page ([open]): the scrim fades and the sheet rises on the expand spring (reduced motion: a
 * cross-fade); a tap on the scrim or Back closes it. [tag] names the sheet for the screen tests.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun LifeSheet(open: Boolean, title: String, tag: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val reduced = Meka.reducedMotion
    BackHandler(enabled = open, onBack = onClose)
    AnimatedVisibility(visible = open, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close $title" },
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .animateEnterExit(
                        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else slideInVertically(MekaMotion.expand(false)) { it / 3 },
                        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else slideOutVertically(MekaMotion.expand(false)) { it / 3 },
                    )
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 620.dp)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(MekaSpace.m)
                    .testTag(tag),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(title, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("Close", style = MekaType.metaStrong, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onClose)
                            .minTouch().padding(horizontal = MekaSpace.m))
                }
            }
        }
    }
}

/** + Add's choices as rows (label over its hint); [chosen] is lit. */
@Composable
internal fun AddChoiceRows(choices: List<AddChoice>, chosen: String?, onChoose: (AddChoice) -> Unit) {
    choices.forEach { c ->
        val lit = c.key == chosen
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                .background(if (lit) Meka.colors.surfaceRaised else Meka.colors.surface)
                .clickable(role = Role.Button) { onChoose(c) }
                .semantics(mergeDescendants = true) {}
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        ) {
            Text(c.label, style = MekaType.body, color = if (lit) Meka.colors.accent else Meka.colors.textPrimary)
            Text(LifePageRules.shown(c.hint), style = MekaType.caption, color = Meka.colors.textSecondary)
        }
    }
}
