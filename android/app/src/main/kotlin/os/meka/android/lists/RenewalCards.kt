package os.meka.android.lists

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.BillRules
import os.meka.core.domain.RenewalBoard
import os.meka.core.domain.RenewalBoardRules
import os.meka.core.domain.RenewalCard
import os.meka.core.domain.RenewalEditKey
import os.meka.core.domain.RenewalItem
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.RenewalRules
import os.meka.core.domain.RenewalTab
import os.meka.core.domain.RenewalTile
import os.meka.core.domain.SessionRules
import os.meka.core.facade.MekaCore

/*
 * Renewals keep their email, slice 2 (Meka 2026-10-28, the "Renewals" mockup approved 22:20): the Renewals list as
 * cards. Three tiles, the tabs Coming up · All · Done (the lit pill cross-fades), and a card per renewal: date tile,
 * name, kind, cost and a "cancel in N days" badge. A tap unfolds it in place (the list's row motion; reduced motion:
 * a cross-fade) into Renews · Cancel by · Repeats tiles, the From the email box and Renewed · Cancelled it · Edit.
 * Edit rises a sheet of rows, each opening its own choices. The disclaimer is said once, at the foot.
 */

/** The tiles, the tabs and the cards for [board]; the suggestion rows and the foot follow ([renewalFoot]). */
internal fun LazyListScope.renewalBoard(
    board: RenewalBoard, open: String?, toggle: (String) -> Unit, setTab: (RenewalTab) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    item(key = "r-board-tiles") {
        Row(Modifier.animateItem().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            board.tiles.forEach { t -> BoardTile(t, Modifier.weight(1f)) }
        }
    }
    item(key = "r-board-tabs") {
        Row(
            Modifier.animateItem().fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                .background(Meka.colors.surface).padding(MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            RenewalBoardRules.TABS.forEach { t -> TabPill(t.label, t == board.tab, Modifier.weight(1f)) { tap(); setTab(t) } }
        }
    }
    board.empty?.let { line ->
        item(key = "r-board-empty") {
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.animateItem().padding(vertical = MekaSpace.xs))
        }
    }
    items(board.cards, key = { "r-${board.tab.name}-${it.id}" }) { c ->
        RenewalCardView(c, open == c.id, { toggle(c.id) }, core, act, leave, tap, Modifier.animateItem())
    }
}

/** The disclaimer, once, at the foot of the list. */
internal fun LazyListScope.renewalFoot() {
    item(key = "r-foot") {
        Text(
            RenewalBoardRules.FOOTER, style = MekaType.caption, color = Meka.colors.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.animateItem().fillMaxWidth().padding(top = MekaSpace.m),
        )
    }
}

@Composable
private fun BoardTile(t: RenewalTile, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "${t.label} ${t.value}" },
    ) {
        Text(t.label.uppercase(), style = MekaType.cardLabel, color = Meka.colors.textTertiary, maxLines = 2)
        AnimatedContent(t.value, transitionSpec = { fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true)) }, label = "renewal-tile") { v ->
            Text(v, style = MekaType.nowTitle, color = if (t.warn) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun TabPill(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(Meka.reducedMotion), label = "renewal-tab")
    Text(
        label, style = MekaType.captionStrong, color = if (on) Meka.colors.textPrimary else Meka.colors.textSecondary,
        textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .semantics { selected = on }
            .clickable(role = Role.Tab, onClick = onClick).minTouch().padding(vertical = MekaSpace.xs)
            .testTag("renewal-tab-$label"),
    )
}

@Composable
private fun RenewalCardView(
    c: RenewalCard, open: Boolean, toggle: () -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit, tap: () -> Unit, modifier: Modifier,
) {
    var editing by rememberSaveable(c.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface).testTag("renewal-card-${c.id}")) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { toggle() }
                .semantics(mergeDescendants = true) { contentDescription = c.spoken }
                .padding(MekaSpace.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            Column(
                Modifier.size(width = 48.dp, height = 52.dp).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(c.month, style = MekaType.micro, color = Meka.colors.textTertiary)
                Text(c.day, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(c.title, style = MekaType.upNextCardTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(c.sub, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                c.cost?.let { Text(it, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1) }
                Badge(c.badge, c.warn)
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    c.tiles.forEach { t ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xs)) {
                            Text(t.label.uppercase(), style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
                            Text(t.value, style = MekaType.metaStrong, color = if (t.warn) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                c.item.notes?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
                c.item.fromEmail?.let { RenewalEmailBox(c.id, it, core, tap) }
                if (c.closed) {
                    CardButton(RenewalBoardRules.PUT_BACK, primary = true, Modifier.fillMaxWidth()) { leave { core.reopenRenewal(c.id) } }
                } else {
                    c.item.payLink?.let { link ->
                        CardButton(BillRules.payLabel(link), primary = false, Modifier.fillMaxWidth()) { tap(); runCatching { uri.openUri(link) } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        CardButton(c.item.doneLabel, primary = true, Modifier.weight(1f)) {
                            if (c.item.repeats == RenewalRepeat.NONE) leave { core.renewalDone(c.id) } else { tap(); act { core.renewalDone(c.id) } }
                        }
                        CardButton(c.item.stopLabel, primary = false, Modifier.weight(1f)) { leave { core.stopRenewal(c.id) } }
                        CardButton(RenewalBoardRules.EDIT, primary = false, Modifier.weight(1f)) { tap(); editing = true }
                    }
                }
            }
        }
    }
    if (editing && !c.closed) RenewalEditSheet(c.item, core, act, onDelete = { editing = false; leave { core.deleteRenewal(c.id) } }) { editing = false }
}

@Composable
private fun Badge(text: String, warn: Boolean) {
    Text(
        text, style = MekaType.micro, color = if (warn) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (warn) Meka.colors.accent.copy(alpha = 0.16f) else Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun CardButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label, style = MekaType.captionStrong, color = if (primary) Meka.colors.onAccent else Meka.colors.textPrimary,
        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(MekaRadius.m)).background(if (primary) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick).minTouch().padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs)
            .testTag("renewal-button-$label"),
    )
}

/**
 * Edit: a sheet that rises from the foot (spring; reduced motion: no rise) with a row per setting, each showing what it
 * is now; a tap opens that row's chips (or the date picker, or a field) in place. Done closes it; Delete takes it off
 * the radar.
 */
@Composable
private fun RenewalEditSheet(item: RenewalItem, core: MekaCore, act: (suspend () -> Unit) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val reduced = Meka.reducedMotion
    val rise = remember { Animatable(if (reduced) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f)) }
    var row by rememberSaveable(item.id) { mutableStateOf<RenewalEditKey?>(null) }
    var picking by rememberSaveable(item.id) { mutableStateOf(false) }
    var costError by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    var badLink by rememberSaveable(item.id) { mutableStateOf(false) }
    // The newest copy of the item, so each row says what it is now after a change.
    val lists by core.listsView.collectAsState()
    val now = lists.renewals.all.firstOrNull { it.id == item.id } ?: item
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scrimTaps = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Meka.colors.background.copy(alpha = 0.6f)).clickable(interactionSource = scrimTaps, indication = null, onClickLabel = "Close") { onClose() })
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp)
                    .graphicsLayer { translationY = rise.value * size.height }
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    // A tap on the sheet itself stays on it (only the scrim closes it).
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.m)
                    .testTag("renewal-edit-sheet"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text("${RenewalBoardRules.EDIT} ${now.title}", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                core.renewalEditRows(now).forEach { r ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                                .clickable(role = Role.Button) {
                                    if (r.key == RenewalEditKey.DUE) picking = true else row = if (row == r.key) null else r.key
                                }
                                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
                                .testTag("renewal-edit-${r.key.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.label, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                            Text("${r.value} ›", style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        AnimatedVisibility(row == r.key, enter = unfold(), exit = fold()) {
                            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                                when (r.key) {
                                    RenewalEditKey.REPEATS -> ChipLine { RenewalRules.REPEATS.forEach { rp -> Chip(RenewalRules.repeatLabel(rp), now.repeats == rp) { act { core.setRenewalRepeat(now.id, rp) } } } }
                                    RenewalEditKey.REMIND -> ChipLine { RenewalRules.LEAD_CHOICES.forEach { c -> Chip(c.label, c.days == now.leadDays) { c.days?.let { d -> act { core.setRenewalLead(now.id, d) } } } } }
                                    RenewalEditKey.CANCEL_BY -> ChipLine {
                                        val current = now.cancelByDay?.let { (now.dueDay - it).toInt() }
                                        RenewalRules.CANCEL_CHOICES.forEach { c -> Chip(c.label, c.days == current) { act { core.setRenewalCancelBy(now.id, c.days) } } }
                                    }
                                    RenewalEditKey.KIND -> ChipLine { RenewalRules.KINDS.forEach { k -> Chip(RenewalRules.kindLabel(k), k == now.kind) { act { core.setRenewalKind(now.id, k) } } } }
                                    RenewalEditKey.COST -> {
                                        Field("Cost, e.g. 9.99") { text ->
                                            costError = RenewalRules.costError(text)
                                            if (costError == null) act { core.setRenewalCost(now.id, text) }
                                        }
                                        costError?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
                                    }
                                    RenewalEditKey.PAY_LINK -> {
                                        Field("Pay link, e.g. octopus.energy") { text ->
                                            act { badLink = !core.setRenewalPayLink(now.id, text.take(SessionRules.MAX_LINK)) }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(BillRules.linkLine(now, badLink), style = MekaType.caption, color = if (badLink) Meka.colors.critical else Meka.colors.textTertiary, modifier = Modifier.weight(1f))
                                            if (now.payLink != null) Action("Remove") { badLink = false; act { core.setRenewalPayLink(now.id, "") } }
                                        }
                                    }
                                    RenewalEditKey.DUE -> Unit
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    CardButton(RenewalBoardRules.SAVE, primary = true, Modifier.weight(1f)) { onClose() }
                    Text(
                        RenewalBoardRules.DELETE, style = MekaType.captionStrong, color = Meka.colors.critical, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onDelete() }
                            .minTouch().padding(vertical = MekaSpace.xs).testTag("renewal-edit-delete"),
                    )
                }
            }
        }
    }
    if (picking) DayPickerDialog(now.dueDay, onPick = { d -> act { core.setRenewalDue(now.id, d) } }, onDismiss = { picking = false })
}

@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}
