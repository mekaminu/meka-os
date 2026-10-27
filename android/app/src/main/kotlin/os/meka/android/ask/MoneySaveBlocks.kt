package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneySave
import os.meka.core.domain.MoneySaveBase
import os.meka.core.domain.MoneySaveBar

/**
 * Money redesign, Plan (mockup `docs/design/money/`, Plan): **What if I save…** as one card. The slider (£25 steps, a tick
 * haptic per step), the months and rate chips, month-by-month bars, the total and the month it's reached, the split line,
 * Obi's line on whether it fits (amber when it doesn't) and Make it a goal. Every word and number is the core's
 * [MoneySave.view], worked out on each change on this device, so dragging never rebuilds the money view; nothing is
 * stored while Money is closed.
 *
 * Motion: the bars grow to their heights on the appear spring when anything changes (snap with reduced motion); the
 * amount, total and Obi's line cross-fade; chips light on the appear tween.
 */
@Composable
internal fun MoneySaveCard(base: MoneySaveBase, modifier: Modifier = Modifier, onTick: () -> Unit, onGoal: (Long, Int, Int) -> Unit) {
    val reduced = Meka.reducedMotion
    var amount by rememberSaveable { mutableLongStateOf(base.startPence) }
    var months by rememberSaveable { mutableIntStateOf(MoneySave.DEFAULT_MONTHS) }
    var rate by rememberSaveable { mutableIntStateOf(0) }
    val v = remember(amount, months, rate, base) { MoneySave.view(amount, months, rate, base) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .testTag("money-save-card").padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(v.each, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Crossfade(v.amount, animationSpec = MekaMotion.appear(reduced), label = "money-save-amount") { a ->
                Text(a, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
            }
        }
        Slider(
            value = (v.amountPence / 100).toFloat(),
            onValueChange = { f ->
                val p = MoneySave.snap((f * 100).toLong())
                if (p != amount) { onTick(); amount = p }
            },
            valueRange = (MoneySave.MIN_PENCE / 100f)..(MoneySave.MAX_PENCE / 100f),
            colors = SliderDefaults.colors(
                thumbColor = Meka.colors.accent, activeTrackColor = Meka.colors.accent,
                inactiveTrackColor = Meka.colors.surfaceRaised,
            ),
            modifier = Modifier.testTag("money-save-slider").semantics { contentDescription = "Amount to save each month, ${v.amount}" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (c in v.months) TripPill(c.label, on = c.on, tag = "money-save-months-${c.value}", description = c.spoken) {
                if (!c.on) { onTick(); months = c.value }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (c in v.rates) TripPill(c.label, on = c.on, tag = "money-save-rate-${c.value}", description = c.spoken) {
                if (!c.on) { onTick(); rate = c.value }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(96.dp).padding(top = MekaSpace.xs)
                .semantics(mergeDescendants = true) { contentDescription = v.bars.joinToString(", ") { it.label } },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            v.bars.forEachIndexed { i, b -> SaveBar(b, i, reduced, Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Crossfade(v.total, animationSpec = MekaMotion.appear(reduced), label = "money-save-total") { t ->
                Text(t, style = MekaType.greeting, color = Meka.colors.accent, modifier = Modifier.testTag("money-save-total"))
            }
            Text(v.end, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xxs))
        }
        Text(v.split, style = MekaType.caption, color = Meka.colors.textTertiary)
        val obiColor by animateColorAsState(if (v.obiLit) Meka.colors.money3 else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-save-obi")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Box(
                Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Meka.colors.accent, Meka.colors.accent.copy(alpha = 0.35f)))),
            )
            Crossfade(v.obi, animationSpec = MekaMotion.appear(reduced), label = "money-save-obi-line", modifier = Modifier.weight(1f)) { o ->
                Text(o, style = MekaType.caption, color = obiColor, modifier = Modifier.testTag("money-save-obi"))
            }
        }
        Text(
            MoneySave.MAKE_GOAL, style = MekaType.captionStrong, color = Meka.colors.onAccent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                .clickable(role = Role.Button) { onGoal(v.amountPence, months, rate) }
                .semantics { contentDescription = "${MoneySave.MAKE_GOAL}: ${v.goalTitle}" }
                .testTag("money-save-goal")
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
        Text(v.basis, style = MekaType.micro, color = Meka.colors.textTertiary)
    }
}

/** One month's bar, growing to its share of the last month's total; the last one in the accent. */
@Composable
private fun SaveBar(b: MoneySaveBar, i: Int, reduced: Boolean, modifier: Modifier) {
    val h by animateFloatAsState(b.share.toFloat().coerceIn(0.04f, 1f), MekaMotion.appear(reduced), label = "money-save-bar-$i")
    Box(
        modifier.fillMaxHeight(h).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(if (b.last) Meka.colors.accent else Meka.colors.accent.copy(alpha = 0.28f)),
    )
}

