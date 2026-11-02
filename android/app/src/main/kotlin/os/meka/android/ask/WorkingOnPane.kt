package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TouchPill
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.HelpPlace
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ObiJobRow
import os.meka.core.domain.ObiJobRules
import os.meka.core.domain.WorkingOnCard
import os.meka.core.domain.WorkingOnRules
import os.meka.core.domain.WorkingOnSwitch
import os.meka.core.domain.WorkingOnView
import os.meka.core.facade.MekaCore

/**
 * Ask → Daily → Obi is working on (Ada plans with you, part 3 slices 1 and 4): first ERRANDS YOU ASKED FOR, with Ask
 * Obi for an errand (a sheet with one field) and a card per errand (status, WHAT I DID, WHAT I NEED FROM YOU, the draft
 * with Copy, Stop); then what the standing errands watch, each a card with WHAT I DID, WHAT I NEED FROM YOU (in the
 * accent) and the button to the screen that holds it; then STANDING ERRANDS with Turn off / Turn on for each (synced).
 * The words are the core's [WorkingOnRules.view] and [ObiJobRules], the same as the Mac's sheet. Nothing is sent from
 * here: a draft is copied for him to send himself.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; cards and switches stagger in 40 ms apart;
 * the ask sheet rises on the expand spring over a fading scrim;
 * a switch presses in with a tick haptic and the list resizes on the expand spring as a card comes or goes. Reduced
 * motion: cross-fades and no resize spring.
 */
@Composable
fun WorkingOnPane(core: MekaCore, onClose: () -> Unit, onOpen: (HelpPlace) -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var view by remember { mutableStateOf<WorkingOnView?>(null) }
    var asking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    // The server works an errand a step a minute and sync brings each step in: read again while the pane is open.
    LaunchedEffect(Unit) {
        while (true) {
            view = runCatching { core.workingOnView() }.getOrNull() ?: view
            delay(REFRESH_MS)
        }
    }
    val reduced = Meka.reducedMotion
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag("working-on-pane")
                .let { if (reduced) it else it.animateContentSize(MekaMotion.expand(false)) },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            Text(MoreItem.WORKING_ON.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WORKING_ON)).appear(rememberAppearance(0)))
            val v = view ?: return@Column
            Text(LoneWordGlue.kept(v.summary), style = MekaType.body,
                color = if (v.needCount > 0) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.appear(rememberAppearance(0)).testTag("working-on-summary"))
            Text(LoneWordGlue.kept(WorkingOnRules.INTRO), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.appear(rememberAppearance(0)))
            // Part 3 slice 4: the errands he asked for, first, with the row that opens the ask sheet.
            Text(ObiJobRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(1)))
            Text(LoneWordGlue.kept(ObiJobRules.INTRO), style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.appear(rememberAppearance(1)))
            Row(Modifier.fillMaxWidth().appear(rememberAppearance(1)), verticalAlignment = Alignment.CenterVertically) {
                TouchPill(ObiJobRules.ASK_ROW, Meka.colors.surfaceRaised, Meka.colors.accent, modifier = Modifier.testTag("errand-ask")) {
                    haptics.tick(); said = null; asking = true
                }
            }
            said?.let {
                Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.testTag("errand-said"))
            }
            if (v.jobs.isEmpty()) {
                Text(LoneWordGlue.kept(ObiJobRules.EMPTY), style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.appear(rememberAppearance(2)))
            }
            v.jobs.forEachIndexed { i, j ->
                JobCard(
                    j, Modifier.appear(rememberAppearance(i + 2)),
                    onCopy = { draft ->
                        haptics.tick()
                        // On the clipboard for him to paste and send himself; Navilife never sends it.
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText(ObiJobRules.DRAFT_LABEL, draft))
                        said = ObiJobRules.COPIED
                    },
                    onStop = {
                        haptics.tick()
                        scope.launch {
                            val stopped = runCatching { core.stopObiErrand(j.id) }.getOrDefault(false)
                            said = if (stopped) ObiJobRules.STOPPED_LINE else ObiJobRules.STOP_GONE
                            view = runCatching { core.workingOnView() }.getOrNull() ?: view
                        }
                    },
                )
            }
            val base = v.jobs.size + 2
            if (v.cards.isNotEmpty()) {
                Text(WorkingOnRules.WATCHING.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(base)))
            }
            v.cards.forEachIndexed { i, c ->
                ErrandCard(c, Modifier.appear(rememberAppearance(base + i + 1))) { onOpen(c.errand.place) }
            }
            Text(WorkingOnRules.SWITCHES.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(base + v.cards.size + 1)))
            v.switches.forEachIndexed { i, s ->
                ErrandSwitch(s, Modifier.appear(rememberAppearance(base + v.cards.size + 2 + i))) {
                    haptics.tick()
                    scope.launch { view = runCatching { core.setStandingErrand(s.errand.code, !s.on) }.getOrNull() ?: view }
                }
            }
        }
        AskErrandSheet(
            open = asking,
            onClose = { asking = false },
            onAsk = { text ->
                haptics.tick()
                scope.launch {
                    val asked = runCatching { core.askObiErrand(text) }.getOrNull()
                    said = asked?.line ?: ObiJobRules.BLANK
                    if (asked?.id != null) asking = false
                    view = runCatching { core.workingOnView() }.getOrNull() ?: view
                }
            },
        )
    }
}

/** How often the open pane reads the errands again (each step is at least a minute apart on the server). */
private const val REFRESH_MS = 15_000L

/**
 * One errand he asked for: his words as the title, the status (lit when it needs him), WHAT I DID, WHAT I NEED FROM
 * YOU, the draft in its own box with Copy (and that Navilife never sends it), and Stop while it is open.
 */
@Composable
private fun JobCard(j: ObiJobRow, modifier: Modifier, onCopy: (String) -> Unit, onStop: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m)
            .semantics(mergeDescendants = false) { contentDescription = j.spoken }
            .testTag("errand-card"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(LoneWordGlue.kept(j.title), style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Text(LoneWordGlue.kept(j.status), style = MekaType.caption,
            color = if (j.needsYou) Meka.colors.accent else Meka.colors.textSecondary)
        Text(WorkingOnRules.DID.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.xs))
        Text(LoneWordGlue.kept(j.did), style = MekaType.body, color = Meka.colors.textSecondary)
        j.need?.let { need ->
            Text(WorkingOnRules.NEED.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs))
            Text(LoneWordGlue.kept(need), style = MekaType.body, color = Meka.colors.accent)
        }
        j.draft?.let { draft ->
            Text(ObiJobRules.DRAFT_LABEL.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs))
            Text(draft, style = MekaType.body, color = Meka.colors.textPrimary,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
                    .padding(MekaSpace.s).testTag("errand-draft"))
            Text(LoneWordGlue.kept(ObiJobRules.DRAFT_FOOT), style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            j.draft?.let { draft ->
                TouchPill(ObiJobRules.COPY, Meka.colors.surface, Meka.colors.accent, spoken = "${ObiJobRules.COPY} the draft: ${j.title}") { onCopy(draft) }
            }
            if (j.canStop) {
                TouchPill(ObiJobRules.STOP, Meka.colors.surface, Meka.colors.textSecondary, spoken = "${ObiJobRules.STOP}: ${j.title}", onClick = onStop)
            }
        }
    }
}

/**
 * The ask sheet (no open field on the pane, the layout rules): "What should Obi look into?", one field with the hint,
 * Ask Obi and Cancel. Motion: the scrim fades and the sheet rises on the expand spring; reduced motion cross-fades.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun AskErrandSheet(open: Boolean, onClose: () -> Unit, onAsk: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    BackHandler(enabled = open, onBack = onClose)
    AnimatedVisibility(visible = open, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        var text by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close ${ObiJobRules.SHEET_TITLE}" },
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
                    .heightIn(max = 520.dp)
                    .navigationBarsPadding().imePadding()
                    .padding(MekaSpace.m)
                    .testTag("errand-sheet"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(ObiJobRules.SHEET_TITLE, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                Text(LoneWordGlue.kept(ObiJobRules.INTRO), style = MekaType.caption, color = Meka.colors.textSecondary)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                        .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
                ) {
                    if (text.isEmpty()) Text(ObiJobRules.HINT, style = MekaType.body, color = Meka.colors.textTertiary)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.take(ObiJobRules.MAX_ASK) },
                        textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                        cursorBrush = SolidColor(Meka.colors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onAsk(text) }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = ObiJobRules.SHEET_TITLE }.testTag("errand-field"),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TouchPill(ObiJobRules.SHEET_CANCEL, Meka.colors.surfaceRaised, Meka.colors.textSecondary, onClick = onClose)
                    TouchPill(ObiJobRules.SHEET_GO, Meka.colors.accent, Meka.colors.onAccent, modifier = Modifier.testTag("errand-go")) {
                        if (text.isNotBlank()) onAsk(text)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrandCard(c: WorkingOnCard, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m)
            .semantics(mergeDescendants = false) { contentDescription = c.spoken }
            .testTag("working-on-card"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(c.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Text(WorkingOnRules.DID.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text(LoneWordGlue.kept(c.did), style = MekaType.body, color = Meka.colors.textSecondary)
        c.need?.let { need ->
            Text(WorkingOnRules.NEED.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs))
            Text(LoneWordGlue.kept(need), style = MekaType.body, color = Meka.colors.accent)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TouchPill(c.errand.openLabel, Meka.colors.surface, Meka.colors.accent, spoken = "${c.errand.openLabel}: ${c.title}", onClick = onOpen)
        }
    }
}

@Composable
private fun ErrandSwitch(s: WorkingOnSwitch, modifier: Modifier, onToggle: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(s.errand.title, style = MekaType.body, color = if (s.on) Meka.colors.textPrimary else Meka.colors.textTertiary)
        Text(LoneWordGlue.kept(s.line), style = MekaType.caption, color = Meka.colors.textSecondary)
        TouchPill(s.action, Meka.colors.surfaceRaised, Meka.colors.accent, spoken = "${s.action}: ${s.errand.title}", onClick = onToggle)
    }
}
