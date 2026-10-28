package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyCheck
import os.meka.core.domain.MoneyCheckView
import os.meka.core.domain.MoneyIdea

/**
 * Money's layout rules on Plan (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped… make it
 * easy on the eye"), after Spending and Bills. Every word is the core's ([MoneyPlanLayout]); this only draws it.
 *
 * Motion: the cards appear with the screen's stagger (the caller's appear); a row's line cross-fades when it changes
 * (MekaMotion.appear honours reduced motion); a row presses like every control and its sheet rises on the expand spring
 * ([MoneyDetailSheet]).
 */

/**
 * OBI'S MONEY CHECK as one card: the yearly total big with "from 4 ideas", then each idea one line (its title · its
 * figure · ›, a lit one's figure in the accent), three then "2 more ›"; "2 put away" with Show them again at the foot.
 */
@Composable
internal fun MoneyCheckCard(
    c: MoneyCheckView, ideas: List<MoneyIdea>, more: String?, modifier: Modifier = Modifier,
    onOpen: (String) -> Unit, onMore: () -> Unit, onShowAgain: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    MoneyCard(modifier, tag = "money-check-card") {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = listOfNotNull(c.total, c.from).joinToString(", ") },
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            c.total?.let { t ->
                Crossfade(t, animationSpec = MekaMotion.appear(reduced), label = "money-check-total") { l ->
                    Text(l, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.testTag("money-check-total"))
                }
            }
            Text(c.from, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        c.empty?.let { e -> Text(e, style = MekaType.caption, color = Meka.colors.textSecondary) }
        ideas.forEach { i ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen(i.id) }
                    .semantics(mergeDescendants = true) { contentDescription = i.spoken }
                    .minTouch().padding(vertical = MekaSpace.xxs).testTag("money-idea-${i.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Text(i.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(i.figure, style = MekaType.body, color = if (i.lit) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
                Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
            }
        }
        more?.let { l ->
            Text(l, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onMore)
                    .minTouch().testTag("money-ideas-more"))
        }
        c.away?.let { h ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Crossfade(h, animationSpec = MekaMotion.appear(reduced), label = "money-ideas-away", modifier = Modifier.weight(1f)) { l ->
                    Text(l, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                Text(MoneyCheck.SHOW_AGAIN, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onShowAgain)
                        .minTouch().padding(MekaSpace.xs).testTag("money-ideas-show-again"))
            }
        }
    }
}

/**
 * An idea's sheet: its title and figure, why (two short sentences), THE PAYMENTS BEHIND IT as rows (who · when ·
 * amount), then its step in the app (when it has one), Remind me and Not for me.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyIdeaDetails(i: MoneyIdea, onAct: () -> Unit, onRemind: () -> Unit, onNotFor: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = i.spoken },
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            Text(i.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(i.figure, style = MekaType.itemTitle, color = if (i.lit) Meka.colors.accent else Meka.colors.textSecondary)
        }
        Text(i.why, style = MekaType.body, color = Meka.colors.textSecondary)
        if (i.behind.isNotEmpty()) {
            Text(MoneyCheck.BEHIND, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
            MoneyCard(tag = "money-idea-behind") {
                i.behind.forEach { p ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${p.label}, ${p.date}, ${p.amount}" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.date, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                        Text(p.amount, style = MekaType.body, color = Meka.colors.textPrimary)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            modifier = Modifier.padding(top = MekaSpace.xs)) {
            i.act?.let { a -> IdeaPill(a, Meka.colors.accent, "money-idea-act", onAct) }
            IdeaPill(MoneyCheck.REMIND, Meka.colors.textPrimary, "money-idea-remind", onRemind)
            Text(MoneyCheck.NOT_FOR_ME, style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onNotFor)
                    .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("money-idea-not-for-me"))
        }
    }
}

@Composable
private fun IdeaPill(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Text(label, style = MekaType.itemMeta, color = color,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick).minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag(tag))
}

/**
 * One row inside a Plan card that opens a sheet (What if…, Set a challenge): its name over a short line (in the accent
 * when [lit]) and a chevron. Layout rule (e): fields live in the sheet, never open on the page.
 */
@Composable
internal fun MoneyLinkRow(title: String, line: String, lit: Boolean, tag: String, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $line" }
            .minTouch().padding(vertical = MekaSpace.xxs).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1)
            Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "$tag-line") { l ->
                Text(l, style = MekaType.caption, color = if (lit) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
            }
        }
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}
