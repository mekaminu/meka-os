package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import os.meka.core.domain.MoneyClear
import os.meka.core.domain.MoneyClearBase
import os.meka.core.domain.MoneyClearRow

/**
 * Money redesign, Plan (mockup `docs/design/money/`, Plan): **Clear my cards** as one card. Each credit card with its rate,
 * the month it clears and what it owes; one slider for the total a month (£25 steps, a tick haptic per step); Highest
 * interest first · Smallest balance first; the Debt-free and Interest tiles; what paying only the minimums would mean;
 * Obi's line (amber when it doesn't fit or never clears) and Use this plan. Every word and number is the core's
 * [MoneyClear.view], worked out on each change on this device, so dragging never rebuilds the money view.
 *
 * Motion: the dates, tiles and Obi's line cross-fade as he slides; an order chip lights on the appear tween.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyClearCard(base: MoneyClearBase, modifier: Modifier = Modifier, onTick: () -> Unit, onPlan: (Long, String) -> Unit) {
    val reduced = Meka.reducedMotion
    var pay by rememberSaveable { mutableLongStateOf(base.startPence) }
    var order by rememberSaveable { mutableStateOf(MoneyClear.BY_RATE) }
    val v = remember(pay, order, base) { MoneyClear.view(pay, order, base) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .testTag("money-clear-card").padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        v.rows.forEach { r -> ClearRow(r, reduced) }
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xxs), verticalAlignment = Alignment.Bottom) {
            Text(v.payLabel, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Crossfade(v.pay, animationSpec = MekaMotion.appear(reduced), label = "money-clear-pay") { a ->
                Text(a, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
            }
        }
        Slider(
            value = (v.payPence / 100).toFloat(),
            onValueChange = { f ->
                val p = MoneyClear.snap((f * 100).toLong(), base)
                if (p != pay) { onTick(); pay = p }
            },
            valueRange = (base.minPence / 100f)..(base.maxPence / 100f),
            colors = SliderDefaults.colors(
                thumbColor = Meka.colors.accent, activeTrackColor = Meka.colors.accent,
                inactiveTrackColor = Meka.colors.surfaceRaised,
            ),
            modifier = Modifier.testTag("money-clear-slider").semantics { contentDescription = "Total to pay off the cards each month, ${v.pay}" },
        )
        if (v.orders.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                for (c in v.orders) TripPill(c.label, on = c.on, tag = "money-clear-order-${c.key}", description = c.spoken) {
                    if (!c.on) { onTick(); order = c.key }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            ClearTile(v.freeLabel, v.free, if (v.freeLit) Meka.colors.money3 else Meka.colors.accent, reduced, "money-clear-free", Modifier.weight(1f))
            ClearTile(v.interestLabel, v.interest, Meka.colors.textPrimary, reduced, "money-clear-interest", Modifier.weight(1f))
        }
        Crossfade(v.vsMin, animationSpec = MekaMotion.appear(reduced), label = "money-clear-min") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.money3)
        }
        val obiColor by animateColorAsState(if (v.obiLit) Meka.colors.money3 else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-clear-obi")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Box(
                Modifier.padding(top = MekaSpace.xxs).size(18.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Meka.colors.accent, Meka.colors.accent.copy(alpha = 0.35f)))),
            )
            Crossfade(v.obi, animationSpec = MekaMotion.appear(reduced), label = "money-clear-obi-line", modifier = Modifier.weight(1f)) { o ->
                Text(o, style = MekaType.caption, color = obiColor, modifier = Modifier.testTag("money-clear-obi"))
            }
        }
        val goal = v.goalTitle
        Text(
            MoneyClear.USE, style = MekaType.captionStrong,
            color = if (goal != null) Meka.colors.onAccent else Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                .background(if (goal != null) Meka.colors.accent else Meka.colors.surfaceRaised)
                .clickable(role = Role.Button) { onPlan(v.payPence, order) }
                .semantics { contentDescription = "${MoneyClear.USE}: ${goal ?: MoneyClear.NOT_AT_PACE}" }
                .testTag("money-clear-use")
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
        // Layout rule (c): how this is worked out is said once, behind Plan's foot ⓘ (MoneyTabs.info).
    }
}

/** One card: a CC badge, its name over its rate and clear date (amber when the rate is unknown), what it owes. */
@Composable
private fun ClearRow(r: MoneyClearRow, reduced: Boolean) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = r.spoken }.testTag("money-clear-row-${r.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) { Text("CC", style = MekaType.captionStrong, color = Meka.colors.money3) }
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(r.rate, animationSpec = MekaMotion.appear(reduced), label = "money-clear-rate-${r.id}") { l ->
                Text(l, style = MekaType.caption, color = if (r.rateLit) Meka.colors.money3 else Meka.colors.textSecondary)
            }
        }
        Text(r.owed, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
    }
}

/** A tile (layout rule (b)): label 12 sp over its 17 sp semibold value, the value cross-fading as he slides. */
@Composable
private fun ClearTile(label: String, value: String, color: androidx.compose.ui.graphics.Color, reduced: Boolean, tag: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $value" }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        Text(label, style = MekaType.micro, color = Meka.colors.textSecondary)
        Crossfade(value, animationSpec = MekaMotion.appear(reduced), label = tag) { t ->
            Text(t, style = MekaType.upNextCardTitle, color = color, modifier = Modifier.testTag(tag))
        }
    }
}
