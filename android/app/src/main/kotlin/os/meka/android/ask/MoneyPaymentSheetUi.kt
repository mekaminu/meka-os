package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import os.meka.android.designsystem.moneyTone
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Field
import os.meka.core.domain.MoneyIcon
import os.meka.core.domain.MoneyOwnCategories
import os.meka.core.domain.MoneyOwnCategory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyPaymentSheet
import os.meka.core.domain.MoneySorter
import os.meka.core.domain.MoneySaved
import os.meka.core.domain.MoneySheetMark
import os.meka.core.domain.MoneyTxnRow

/**
 * Money: the payment sheet (Meka 2026-10-26 23:18, replacing the unthemed white dropdown). A tap on a payment raises it
 * from the bottom over a 50 % dim (motion catalogue: Sheets and panes; reduced motion cross-fades): the payee and amount,
 * the day and account, category chips in his order (lit where it is), what a pick does, Shared · Always shared · Side
 * hustle, the last change's line with Undo, and Done. A tap on the dim, Back or Done closes it. The sheet stays open after a
 * pick so the chip lights and the "Moved N payments…" line shows under the chips.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun PaymentSheet(
    row: MoneyTxnRow?, order: List<String>, said: MoneySaved?,
    onCategory: (String) -> Unit, onMark: (MoneySheetMark, Boolean) -> Unit, onUndo: (String) -> Unit, onClose: () -> Unit,
    own: List<MoneyOwnCategory> = emptyList(),
    onNewCategory: (name: String, tone: Int, icon: MoneyIcon) -> Unit = { _, _, _ -> },
    /** Money review: the quick sorter's heading ("SORT YOUR PAYMENTS · 12 LEFT") and the payee's month; null for one payment. */
    sort: MoneySortHead? = null,
    onSkip: () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    val shownSort = remember { arrayOfNulls<MoneySortHead>(1) }
    if (row != null) shownSort[0] = sort
    // The last row shown, so the sheet keeps its content while it slides away.
    val shown = remember { arrayOfNulls<MoneyTxnRow>(1) }
    if (row != null) shown[0] = row
    BackHandler(enabled = row != null, onBack = onClose)
    AnimatedVisibility(visible = row != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val r = row ?: shown[0] ?: return@AnimatedVisibility
        // "+ New category": the form takes the chips' place until Create or Cancel (motion: Expand / collapse).
        var making by rememberSaveable(r.id) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close the payment" }
                    .testTag("money-sheet-dim"),
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
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(MekaSpace.m)
                    .testTag("money-sheet"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                val so = sort ?: shownSort[0].takeIf { row == null }
                so?.let { h ->
                    Crossfade(h.head, animationSpec = MekaMotion.appear(reduced), label = "money-sort-head") { t ->
                        Text(t, style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.testTag("money-sort-head"))
                    }
                }
                // In the sorter the next payee cross-fades in after a pick or Skip.
                AnimatedContent(
                    r, label = "money-sheet-payee",
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) using SizeTransform(clip = false) },
                    contentKey = { it.id },
                ) { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Row(
                            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                heading(); contentDescription = so?.spoken ?: MoneyPaymentSheet.spoken(p)
                            },
                            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
                        ) {
                            Text(p.merchant, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                            if (so == null) Text(p.amount, style = MekaType.nowTitle, color = if (p.moneyIn) Meka.colors.accent else Meka.colors.textPrimary)
                        }
                        Text(so?.line ?: MoneyPaymentSheet.sub(p), style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                r.receipt?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                Text(MoneyPaymentSheet.CATEGORY_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.xs))
                AnimatedContent(
                    making, label = "money-sheet-new",
                    transitionSpec = { fadeIn(MekaMotion.expand(reduced)) togetherWith fadeOut(MekaMotion.expand(reduced)) using SizeTransform(clip = false) },
                ) { form ->
                    if (form) NewCategoryForm(
                        own = own,
                        onCreate = { name, tone, icon -> making = false; onNewCategory(name, tone, icon) },
                        onCancel = { making = false },
                    ) else Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            for (c in MoneyPaymentSheet.chips(order, r.category, own)) {
                                TripPill(c.label, on = c.on, tag = "money-sheet-cat-${c.key}", description = c.spoken) { if (!c.on) onCategory(c.key) }
                            }
                            if (!r.moneyIn) TripPill(MoneyOwnCategories.NEW, tag = "money-sheet-new", description = MoneyOwnCategories.NEW_SPOKEN) { making = true }
                        }
                        Text(MoneyPaymentSheet.ruleLine(r), style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "money-sheet-said") { s ->
                    if (s != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(s.line, style = MekaType.caption, color = if (s.refused) Meka.colors.accent else Meka.colors.textSecondary,
                            modifier = Modifier.weight(1f).testTag("money-sheet-said"))
                        s.undo?.let { u ->
                            Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onUndo(u) }
                                    .minTouch().padding(MekaSpace.xs).testTag("money-sheet-undo"))
                        }
                    }
                }
                if (so == null) {
                    Text(MoneyPaymentSheet.MARKS_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.xs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        for (t in MoneyPaymentSheet.toggles(r)) {
                            TripPill(t.label, on = t.on, tag = "money-sheet-mark-${t.mark.name.lowercase()}", description = t.spoken) { onMark(t.mark, !t.on) }
                        }
                    }
                } else {
                    // The sorter: Skip leaves this payee for later (it stays in Other) and the next one comes up.
                    Box(
                        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button, onClick = onSkip).minTouch().padding(MekaSpace.xs).testTag("money-sort-skip"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(MoneySorter.SKIP, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .clickable(role = Role.Button, onClick = onClose).minTouch().padding(MekaSpace.xs).testTag("money-sheet-done"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(MoneyPaymentSheet.DONE, style = MekaType.metaStrong, color = Meka.colors.onAccent)
                }
            }
        }
    }
}

/**
 * The New category form in the payment sheet: a name, one of the money palette's seven colours
 * (swatches, the chosen one ringed) and an icon (the money set, drawn in the chosen colour), then Create (which also puts
 * this payment's payee in it) or Cancel. A swatch or icon tap ticks; nothing is written until Create.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewCategoryForm(
    own: List<MoneyOwnCategory>, onCreate: (String, Int, MoneyIcon) -> Unit, onCancel: () -> Unit,
    // Slice 3: the same form edits one of his (Accounts → YOUR CATEGORIES), starting from its name, colour and icon.
    title: String = MoneyOwnCategories.TITLE, action: String = MoneyOwnCategories.CREATE,
    startName: String = "", startTone: Int = MoneyOwnCategories.nextTone(own), startIcon: MoneyIcon = MoneyIcon.DOTS,
) {
    val haptics = rememberMekaHaptics()
    var name by rememberSaveable(startName) { mutableStateOf(startName) }
    var tone by rememberSaveable(startTone) { mutableStateOf(startTone) }
    var icon by rememberSaveable(startIcon) { mutableStateOf(startIcon) }
    val create = { MoneyOwnCategories.clean(name)?.let { onCreate(it, tone, icon) } }
    Column(Modifier.fillMaxWidth().testTag("money-new-category"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(title, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Field(
            MoneyOwnCategories.NAME_HINT, value = name, onValue = { name = it.take(MoneyOwnCategories.MAX_NAME) },
            modifier = Modifier.testTag("money-new-name"),
        ) { create() }
        Text(MoneyOwnCategories.COLOUR_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (t in MoneyOwnCategories.TONES) {
                val on = t.tone == tone
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton) { tone = t.tone; haptics.tick() }
                        .semantics { contentDescription = MoneyOwnCategories.toneSpoken(t, on); selected = on }
                        .testTag("money-new-tone-${t.tone}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Meka.colors.moneyTone(t.tone))
                            .then(if (on) Modifier.border(2.dp, Meka.colors.textPrimary, CircleShape) else Modifier),
                    )
                }
            }
        }
        Text(MoneyOwnCategories.ICON_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (i in MoneyOwnCategories.ICONS) {
                val on = i == icon
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(MekaRadius.s))
                        .then(if (on) Modifier.border(2.dp, Meka.colors.moneyTone(tone), RoundedCornerShape(MekaRadius.s)) else Modifier)
                        .clickable(role = Role.RadioButton) { icon = i; haptics.tick() }
                        .semantics(mergeDescendants = true) { contentDescription = MoneyOwnCategories.iconSpoken(i, on); selected = on }
                        .testTag("money-new-icon-${i.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) { MoneyIconChip(i, tone) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(MoneyOwnCategories.CANCEL, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onCancel)
                    .minTouch().padding(MekaSpace.xs).testTag("money-new-cancel"))
            Spacer(Modifier.weight(1f))
            val ready = MoneyOwnCategories.clean(name) != null
            Box(
                Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(if (ready) Meka.colors.accent else Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, enabled = ready) { create() }
                    .semantics { if (!ready) stateDescription = MoneyOwnCategories.NO_NAME }
                    .minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs).testTag("money-new-create"),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = MekaType.metaStrong, color = if (ready) Meka.colors.onAccent else Meka.colors.textTertiary)
            }
        }
    }
}

/** The quick sorter's heading over the payment sheet: [head] ("SORT YOUR PAYMENTS · 12 LEFT"), the payee's [line] and [spoken]. */
internal data class MoneySortHead(val head: String, val line: String, val spoken: String)
