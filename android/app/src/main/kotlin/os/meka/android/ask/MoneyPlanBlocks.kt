package os.meka.android.ask

import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyPlanLayout
import os.meka.core.domain.MoneyTip
import os.meka.core.domain.MoneyTips

/**
 * Money's layout rules on Plan (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped… make it
 * easy on the eye"), after Spending and Bills. Every word is the core's ([MoneyPlanLayout]); this only draws it.
 *
 * Motion: the cards appear with the screen's stagger (the caller's appear); a row's line cross-fades when it changes
 * (MekaMotion.appear honours reduced motion); a row presses like every control and its sheet rises on the expand spring
 * ([MoneyDetailSheet]).
 */

/**
 * TIPS FROM YOUR NUMBERS as one card: each tip one line (the fact · ›, a lit one in the accent), three then "2 more ›";
 * "Tips hidden" with Show them again at the foot of the same card.
 */
@Composable
internal fun MoneyTipsCard(
    tips: List<MoneyTip>, more: String?, hidden: String?, modifier: Modifier = Modifier,
    onOpen: (String) -> Unit, onMore: () -> Unit, onShowAgain: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    MoneyCard(modifier, tag = "money-tips-card") {
        tips.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen(t.id) }
                    .semantics(mergeDescendants = true) { contentDescription = t.spoken }
                    .minTouch().padding(vertical = MekaSpace.xxs).testTag("money-tip-${t.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Text(t.title, style = MekaType.body, color = if (t.lit) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1,
                    modifier = Modifier.weight(1f))
                Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
            }
        }
        more?.let { l ->
            Text(l, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onMore)
                    .minTouch().testTag("money-tips-more"))
        }
        hidden?.let { h ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Crossfade(h, animationSpec = MekaMotion.appear(reduced), label = "money-tips-hidden", modifier = Modifier.weight(1f)) { l ->
                    Text(l, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                Text(MoneyTips.SHOW_AGAIN, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onShowAgain)
                        .minTouch().padding(MekaSpace.xs).testTag("money-tips-show-again"))
            }
        }
    }
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
