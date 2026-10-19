package os.meka.android.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.shell.MoreItem
import os.meka.core.domain.AiFallbackLine
import os.meka.core.domain.AiSpendLine
import os.meka.core.domain.AiStatusView

/**
 * Settings → Navilife's AI (AI layer completeness slice 2): the status line as before ("On · $2.05 of $20 this month",
 * or "Key refused by Anthropic · …" in the accent), and once the server has metered something this month a tap unfolds
 * "This month by feature" with a row per kind of use ("Talk · 40 calls · $1.20") and a thin accent bar for its share.
 * The rows stagger in under the heading and each bar grows from the left; the chevron turns. Reduced motion: the rows
 * appear at once and the bars are drawn full. While the AI can't answer (slice 3) a list under the line says what each
 * AI feature does instead ("While it's off · Morning brief · read from your day as it is…"), fading in.
 */
@Composable
internal fun AiSpendRow(ai: AiStatusView?, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    var open by remember { mutableStateOf(false) }
    val canOpen = ai?.spend?.isNotEmpty() == true
    val line = ai?.line ?: "Checking…"
    val lineColor by animateColorAsState(
        if (ai?.lit == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "ai-line",
    )
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(reduced)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (canOpen) Modifier.clickable(role = Role.Button) { haptics.tick(); open = !open } else Modifier)
                .semantics { if (canOpen) stateDescription = if (open) "expanded" else "collapsed" }
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(MoreItem.AI.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                AnimatedContent(
                    targetState = line,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "ai-row-line",
                ) { l -> Text(l, style = MekaType.caption, color = lineColor, maxLines = 3) }
            }
            if (canOpen) {
                val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.appear(reduced), label = "ai-chevron")
                Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(start = MekaSpace.xs).rotate(turn))
            }
        }
        // AI layer completeness slice 3: while it can't answer, what each AI feature does instead, always shown.
        val fallbacks = ai?.fallbacks.orEmpty()
        AnimatedVisibility(fallbacks.isNotEmpty(), enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            Column(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                ai?.fallbackHeading?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                fallbacks.forEach { f -> FallbackLine(f) }
            }
        }
        if (open && ai != null && canOpen) {
            Column(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                ai.spendHeading?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                ai.spend.forEachIndexed { i, s -> SpendLine(s, i, reduced) }
            }
        }
    }
}

@Composable
private fun FallbackLine(f: AiFallbackLine) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(f.label, style = MekaType.caption, color = Meka.colors.textPrimary)
        Text(f.detail, style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

@Composable
private fun SpendLine(s: AiSpendLine, index: Int, reduced: Boolean) {
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { if (!reduced) { kotlinx.coroutines.delay(40L * index); shown = true } }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, MekaMotion.appear(reduced), label = "ai-spend-in")
    val grow by animateFloatAsState(if (shown) s.share.coerceIn(0.02f, 1f) else 0f, MekaMotion.expand(reduced), label = "ai-spend-bar")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, style = MekaType.caption, color = Meka.colors.textPrimary.copy(alpha = alpha), modifier = Modifier.weight(1f))
            Text(s.detail, style = MekaType.caption, color = Meka.colors.textSecondary.copy(alpha = alpha))
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(grow).height(3.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.accent.copy(alpha = alpha)))
        }
    }
}
