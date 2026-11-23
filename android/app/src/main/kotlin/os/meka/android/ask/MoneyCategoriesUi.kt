package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.lightCardEdge
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.edgeFades
import os.meka.android.designsystem.minTouch
import os.meka.android.lists.Field
import os.meka.core.domain.MoneyChallenges
import os.meka.core.domain.MoneyIcon
import os.meka.core.domain.MoneyOwnCategories
import os.meka.core.domain.MoneyOwnCategory
import os.meka.core.domain.MoneyOwnRow
import os.meka.core.domain.MoneySaved

/**
 * Money → Accounts → YOUR CATEGORIES (slice 3, Meka 2026-10-26 23:18 (3)): one card listing the categories he made, each
 * with its icon in its colour, name and "2 payees · hidden from the ring"; a tap opens its sheet.
 */
@Composable
internal fun OwnCategoriesCard(rows: List<MoneyOwnRow>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).lightCardEdge(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("money-own-card"),
    ) {
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen(r.id) }
                    .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                    .minTouch().padding(vertical = MekaSpace.xs).testTag("money-own-${r.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                MoneyIconChip(r.icon, r.tone)
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1)
                    Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
                }
                Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
            }
        }
    }
}

/**
 * One of his categories' sheet: rises from the bottom over a 50 % dim (motion catalogue: Sheets and panes; reduced motion
 * cross-fades). The edit form (name, colour, icon, Save), Hide from the ring, MERGE INTO chips (his others first) and
 * Delete category, which asks first ("Delete Vape? Its 2 payees go to Other."). MONTHLY CAP: a field ("£60") caps it a
 * month, then its bar this month ("£17.50 of £20 · £2.50 left") with Remove cap. Merge and Delete close the sheet; the line
 * with Undo shows on the page. A tap on the dim, Back or Done closes it.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun OwnCategorySheet(
    row: MoneyOwnRow?, own: List<MoneyOwnCategory>, said: MoneySaved?,
    onSave: (String, Int, MoneyIcon) -> Unit, onHide: (Boolean) -> Unit, onMerge: (String) -> Unit, onDelete: () -> Unit,
    onUndo: (String) -> Unit, onClose: () -> Unit, onCap: (String) -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    val shown = remember { arrayOfNulls<MoneyOwnRow>(1) }
    if (row != null) shown[0] = row
    BackHandler(enabled = row != null, onBack = onClose)
    AnimatedVisibility(visible = row != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val r = row ?: shown[0] ?: return@AnimatedVisibility
        var asking by rememberSaveable(r.id) { mutableStateOf(false) }
        val draft = rememberCategoryDraft(r.name, r.tone, r.icon)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close ${r.name}" }
                    .testTag("money-own-dim"),
            )
            Column(
                // Walk-through A26: a tall sheet stops short of the status bar with a gap, never against the top edge.
                Modifier.statusBarsPadding().padding(top = MekaSpace.xl).widthIn(max = 560.dp).fillMaxWidth()
                    .animateEnterExit(
                        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else slideInVertically(MekaMotion.expand(false)) { it / 3 },
                        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else slideOutVertically(MekaMotion.expand(false)) { it / 3 },
                    )
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 680.dp)
                    .navigationBarsPadding()
                    .testTag("money-own-sheet"),
            ) {
                // Walk-through 2026-11-08, A17: the category's head stays put above the sheet's scrolling body, so a
                // scrolled sheet (the cap field brought into view) never leaves a sliver of it over "Edit category".
                Row(
                    Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.m, bottom = MekaSpace.xs)
                        .semantics(mergeDescendants = true) { heading(); contentDescription = "${r.name}, ${r.line}" }
                        .testTag("money-own-head"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
                ) {
                    MoneyIconChip(r.icon, r.tone)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
                        Text(moneyShown(r.line), style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                // Whatever scrolls under the head fades out rather than being cut to a hard edge, and while more is below,
                // the body fades into the Cancel · Save foot too (walk-through week 5, A12: the name field's "Treats" showed
                // its lower half under the head, the cap field's hint its top half over the foot, both with a hard edge).
                val body = rememberScrollState()
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().clipToBounds()
                        .edgeFades(scrolled = { body.value > 0 }, more = { body.value < body.maxValue }).verticalScroll(body)
                        .padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.m, top = MekaSpace.xs)
                        .testTag("money-own-body"),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NewCategoryForm(
                        own = own, onCreate = onSave, onCancel = onClose,
                        title = MoneyOwnCategories.EDIT_TITLE, action = MoneyOwnCategories.SAVE,
                        startName = r.name, startTone = r.tone, startIcon = r.icon, draft = draft, buttons = false,
                    )
                    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "money-own-said") { s ->
                        if (s != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(moneyShown(s.line), style = MekaType.caption, color = if (s.refused) Meka.colors.accent else Meka.colors.textSecondary,
                                modifier = Modifier.weight(1f).testTag("money-own-said"))
                            s.undo?.let { u ->
                                Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onUndo(u) }
                                        .minTouch().padding(MekaSpace.xs))
                            }
                        }
                    }
                    FlowRow(Modifier.padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        TripPill(MoneyOwnCategories.HIDE, on = r.hidden, tag = "money-own-hide",
                            description = MoneyOwnCategories.HIDE + if (r.hidden) ", on" else ", off") { onHide(!r.hidden) }
                    }
                    // Money, his categories in caps (2026-10-27): a monthly cap on this one, counting its payees.
                    Text(MoneyChallenges.OWN_CAP_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.xs))
                    AnimatedContent(
                        r.cap, label = "money-own-cap",
                        transitionSpec = { fadeIn(MekaMotion.expand(reduced)) togetherWith fadeOut(MekaMotion.expand(reduced)) using SizeTransform(clip = false) },
                        contentKey = { it != null },
                    ) { cap ->
                        if (cap != null) Column(Modifier.testTag("money-own-cap")) {
                            ChallengeBar(cap) { onCap("") }
                        } else Field(
                            MoneyChallenges.OWN_CAP_HINT, modifier = Modifier.testTag("money-own-cap-field"),
                            description = "${MoneyChallenges.OWN_CAP_HINT} for ${r.name}",
                        ) { onCap(it) }
                    }
                    Text(MoneyOwnCategories.MERGE_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.xs))
                    Text(moneyShown(MoneyOwnCategories.MERGE_HINT), style = MekaType.caption, color = Meka.colors.textTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        for (t in MoneyOwnCategories.mergeTargets(own, r.id)) {
                            TripPill(t.label, tag = "money-own-merge-${t.key}", description = "Merge ${r.name} into ${t.label}") { onMerge(t.key) }
                        }
                    }
                    AnimatedContent(
                        asking, label = "money-own-delete",
                        transitionSpec = { fadeIn(MekaMotion.expand(reduced)) togetherWith fadeOut(MekaMotion.expand(reduced)) using SizeTransform(clip = false) },
                    ) { ask ->
                        if (!ask) Text(MoneyOwnCategories.DELETE, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { asking = true }.minTouch().padding(MekaSpace.xs).testTag("money-own-delete"))
                        else Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(MoneyOwnCategories.deleteAsk(r.name, r.payees), style = MekaType.body, color = Meka.colors.textPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                                Text(MoneyOwnCategories.DELETE_YES, style = MekaType.itemMeta, color = Meka.colors.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = false; onDelete() }
                                        .minTouch().padding(MekaSpace.xs).testTag("money-own-delete-yes"))
                                Text(MoneyOwnCategories.KEEP, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = false }
                                        .minTouch().padding(MekaSpace.xs))
                            }
                        }
                    }
                }
                // Walk-through A26: Cancel · Save at the sheet's foot, under everything they finish, and always in view
                // (the body scrolls above them). Save writes the name, colour and icon only when they changed, then closes;
                // Hide, the cap, Merge and Delete act as they are tapped.
                val ready = MoneyOwnCategories.clean(draft.name.value) != null
                Row(
                    Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xs, bottom = MekaSpace.m)
                        .testTag("money-own-foot"),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(MoneyOwnCategories.CANCEL, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClose)
                            .minTouch().padding(MekaSpace.xs).testTag("money-own-cancel"))
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(if (ready) Meka.colors.accent else Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button, enabled = ready) {
                                val name = MoneyOwnCategories.clean(draft.name.value)
                                if (name != null && MoneyOwnCategories.edited(r, name, draft.tone.value, draft.icon.value.name)) {
                                    onSave(name, draft.tone.value, draft.icon.value)
                                }
                                onClose()
                            }
                            .semantics { if (!ready) stateDescription = MoneyOwnCategories.NO_NAME }
                            .minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs).testTag("money-own-save"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(MoneyOwnCategories.SAVE, style = MekaType.metaStrong, color = if (ready) Meka.colors.onAccent else Meka.colors.textTertiary)
                    }
                }
            }
        }
    }
}
