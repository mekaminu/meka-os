package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
import os.meka.core.domain.MoneyCheckWords
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
 * OBI'S MONEY CHECK as the mockup (README item 6, 2026-10-28): a **brass-bordered head card** with its label inside, the
 * yearly total large over "from 4 ideas" and "Written by Obi · …" with Ask Obi to look again; then the **lead idea as its
 * own card** (title · figure, why, its step, Remind me, Not for me; a tap on its title opens the payments behind it);
 * then the next ideas as one-line rows in a card (title · figure · ›), "1 more ›", and "2 put away" with Show them again.
 *
 * Motion: the cards appear with the screen's stagger (the caller's appear); the total, the by-line and the lead
 * cross-fade when they change (MekaMotion.appear honours reduced motion); the buttons press like every control.
 */
@Composable
internal fun MoneyCheckCard(
    c: MoneyCheckView, open: Boolean, modifier: Modifier = Modifier,
    onOpen: (String) -> Unit, onMore: () -> Unit, onShowAgain: () -> Unit,
    onAct: (MoneyIdea) -> Unit, onRemind: (String) -> Unit, onNotFor: (String) -> Unit,
    looking: Boolean = false, onLookAgain: (() -> Unit)? = null,
) {
    val reduced = Meka.reducedMotion
    val lead = MoneyCheck.lead(c.ideas)
    val rest = MoneyCheck.rest(c.ideas, open)
    val more = MoneyCheck.more(c.ideas.size, open)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        MoneyBrassCard(tag = "money-check-card") {
            Text(MoneyCheck.HEAD, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.semantics { heading() })
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = listOfNotNull(c.total, c.from).joinToString(", ") },
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                c.total?.let { t ->
                    Crossfade(t, animationSpec = MekaMotion.appear(reduced), label = "money-check-total") { l ->
                        Text(l, style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.testTag("money-check-total"))
                    }
                }
                Text(c.from, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xxs))
            }
            c.empty?.let { e -> Text(e, style = MekaType.caption, color = Meka.colors.textSecondary) }
            // Obi's wording: "Written by Obi · Sun 25 Oct" (or that he's reading them) and Ask Obi to look again.
            onLookAgain?.let { look ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    val line = if (looking) MoneyCheckWords.LOOKING else c.by.orEmpty()
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "money-check-by", modifier = Modifier.weight(1f)) { l ->
                        Text(l, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.testTag("money-check-by"))
                    }
                    if (!looking) {
                        Text(MoneyCheckWords.LOOK_AGAIN, style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = look)
                                .minTouch().padding(MekaSpace.xs).testTag("money-check-look-again"))
                    }
                }
            }
        }
        lead?.let { i ->
            Crossfade(i, animationSpec = MekaMotion.appear(reduced), label = "money-check-lead") { l ->
                MoneyLeadIdeaCard(l, onOpen = { onOpen(l.id) }, onAct = { onAct(l) }, onRemind = { onRemind(l.id) }, onNotFor = { onNotFor(l.id) })
            }
        }
        if (rest.isNotEmpty() || more != null || c.away != null) {
            MoneyCard(tag = "money-check-rest") {
                rest.forEach { i ->
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
    }
}

/** The mockup's brass-bordered card (Obi's money check): a warm tint of the accent on the surface, a thin accent line. */
@Composable
internal fun MoneyBrassCard(modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(MekaRadius.l)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Meka.colors.accent.copy(alpha = 0.06f).compositeOver(Meka.colors.surface))
            .border(1.dp, Meka.colors.accent.copy(alpha = 0.32f), shape)
            .let { m -> tag?.let { m.testTag(it) } ?: m }.padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        content = content,
    )
}

/**
 * The lead idea as its own card: its title (a tap opens the payments behind it) and figure, why, then its step in the
 * app (when it has one), Remind me and Not for me, as the mockup.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoneyLeadIdeaCard(i: MoneyIdea, onOpen: () -> Unit, onAct: () -> Unit, onRemind: () -> Unit, onNotFor: () -> Unit) {
    MoneyCard(tag = "money-check-lead") {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = i.spoken }.testTag("money-idea-${i.id}"),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            Text(i.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(i.figure, style = MekaType.itemTitle, color = if (i.lit) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
        }
        Text(i.why, style = MekaType.caption, color = Meka.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            i.act?.let { a -> IdeaPill(a, Meka.colors.accent, "money-lead-act", onAct) }
            IdeaPill(MoneyCheck.REMIND, Meka.colors.textPrimary, "money-lead-remind", onRemind)
            Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onNotFor)
                .minTouch().testTag("money-lead-not-for-me"), contentAlignment = Alignment.Center) {
                Text(MoneyCheck.NOT_FOR_ME, style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = MekaSpace.xs))
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

/** A small pill (the mockup's 13 px buttons) drawn inside a 48 dp target. */
@Composable
private fun IdeaPill(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onClick).minTouch().testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, style = MekaType.itemMeta, color = color,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}

/**
 * A small choice chip (What if I save's months and rates; the mockup's 13 px chips): the drawn pill is the text plus
 * 8 dp, the tap target still 48 dp; the chosen one fills with the accent (the appear tween, snapped with reduced motion).
 */
@Composable
internal fun MoneyChip(label: String, on: Boolean, tag: String, description: String = label, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "$tag-fill")
    val ink by animateColorAsState(if (on) Meka.colors.onAccent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "$tag-ink")
    Box(Modifier.testTag(tag).clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; if (on) stateDescription = "Selected" }.minTouch(),
        contentAlignment = Alignment.Center) {
        Text(label, style = MekaType.caption, color = ink, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
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
