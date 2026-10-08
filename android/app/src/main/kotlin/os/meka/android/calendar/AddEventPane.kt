package os.meka.android.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.DayPickerDialog
import os.meka.core.domain.AddEventForm
import os.meka.core.domain.EditLine
import os.meka.core.domain.EventEditResult
import os.meka.core.facade.MekaCore

/**
 * Add event (calendar editing, slice 2b): a real event on one of Meka's Google/Outlook accounts where editing is
 * allowed. Title (focused when the pane opens), the day (Today · Tomorrow · a picked day · All day), the start stepped a
 * quarter hour at a time with the length chips, the calendar (the one added to last time), a place and notes. Add
 * makes a synced edit that waits five seconds for Undo (the screen's undo bar) before the server sends it; the
 * Calendar screen's line then says "Adding …" → "Added “Dentist” to Google".
 *
 * Motion (catalogue "Add event"): the pane springs up from the bottom; rows stagger in 40 ms apart; chips blend their
 * colour with a tick haptic; ‹ › tick and the digits cross-fade; Add gives a light haptic, the pane drops away and the
 * undo bar rises. Reduced motion: cross-fades.
 */
@Composable
fun AddEventPane(core: MekaCore, startDay: Long, onClose: () -> Unit, onAdded: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val accounts by core.calendarEditAccounts.collectAsState()
    var form by remember(startDay) { mutableStateOf<AddEventForm?>(null) }
    var refusal by remember(startDay) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var sending by remember(startDay) { mutableStateOf(false) }
    LaunchedEffect(startDay) { form = runCatching { core.addEventForm(startDay) }.getOrNull() }
    // An account allowed while the pane is open becomes the choice.
    LaunchedEffect(accounts) {
        val f = form ?: return@LaunchedEffect
        if (accounts.none { it.key == f.accountKey }) accounts.firstOrNull()?.let { form = f.withAccount(it.key) }
    }
    BackHandler(onBack = onClose)
    val f = form
    if (f == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val v = remember(f, accounts) { core.addEventView(f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val set: (AddEventForm) -> Unit = { form = it; refusal = null }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
    ) {
        Text(
            "Cancel", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s),
        )
        Text(
            "Add event", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.padding(top = MekaSpace.s).appear(rememberAppearance(0)).semantics { heading() },
        )
        Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(1))) {
            FormField(
                f.title, "Title", { set(f.withTitle(it)) }, singleLine = true,
                style = MekaType.itemTitle, modifier = Modifier.focusRequester(focus),
            )
            FadeLine(v.summary) { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs)) }
        }

        Label("When", Modifier.appear(rememberAppearance(2)))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).appear(rememberAppearance(2)),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            v.chips.forEach { c -> FormChip(c.label, c.selected) { if (!c.selected) { haptics.tick(); set(f.withDay(c.day)) } } }
            FormChip("Pick a date", false) { picking = true }
            FormChip("All day", f.isAllDay) { haptics.tick(); set(f.withAllDay(!f.isAllDay)) }
        }
        val start = v.startLabel
        if (start != null) {
            Row(
                Modifier.padding(top = MekaSpace.s).appear(rememberAppearance(3)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Step("‹", "15 minutes earlier") { haptics.tick(); set(f.stepTime(-1)) }
                FadeLine(start) { Text(it, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.padding(horizontal = MekaSpace.xs)) }
                Step("›", "15 minutes later") { haptics.tick(); set(f.stepTime(1)) }
                v.endLabel?.let { end ->
                    FadeLine("until $end") { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(start = MekaSpace.s)) }
                }
            }
            Row(
                Modifier.padding(top = MekaSpace.s).horizontalScroll(rememberScrollState()).appear(rememberAppearance(3)),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                v.lengths.forEach { l -> FormChip(l.label, l.selected) { if (!l.selected) { haptics.tick(); set(f.withLength(l.minutes)) } } }
            }
        }

        Label("Calendar", Modifier.appear(rememberAppearance(4)))
        if (v.accounts.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).appear(rememberAppearance(4)),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                v.accounts.forEach { a -> FormChip(a.label, a.selected) { if (!a.selected) { haptics.tick(); set(f.withAccount(a.key)) } } }
            }
        } else {
            Text(
                v.accounts.firstOrNull()?.label ?: "No calendar allows editing yet",
                style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(4)),
            )
        }

        Label("Place", Modifier.appear(rememberAppearance(5)))
        FormField(f.location, "Add a place", { set(f.withLocation(it)) }, singleLine = true, modifier = Modifier.appear(rememberAppearance(5)))

        Label("Notes", Modifier.appear(rememberAppearance(6)))
        FormField(f.notes, "Add notes…", { set(f.withNotes(it)) }, singleLine = false, modifier = Modifier.appear(rememberAppearance(6)))
        v.notesNote?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xxs)) }

        val problem = refusal ?: v.problem
        Column(Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(7))) {
            problem?.let { FadeLine(it) { p -> Text(p, style = MekaType.itemMeta, color = Meka.colors.critical, modifier = Modifier.padding(bottom = MekaSpace.s)) } }
            val enabled = v.canAdd && !sending
            Text(
                v.addLabel, style = MekaType.caption, maxLines = 1, color = Meka.colors.onAccent,
                modifier = Modifier.alpha(if (enabled) 1f else 0.45f)
                    .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(enabled = enabled, role = Role.Button) {
                        haptics.light()
                        sending = true
                        val chosen = f
                        scope.launch {
                            when (val r = runCatching { core.addEvent(chosen) }.getOrNull()) {
                                is EventEditResult.Made -> onAdded(r.id)
                                is EventEditResult.Refused -> refusal = r.reason
                                null -> refusal = "Couldn't add it · try again"
                            }
                            sending = false
                        }
                    }
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
            )
        }
    }
    if (picking) {
        DayPickerDialog(
            initialDay = f.day,
            onPick = { day -> if (day >= f.today) { haptics.tick(); set(f.withDay(day)) } },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The Calendar screen's lines about edits ("Adding “Dentist” to Google", "Added …", a clash or a refusal lit in the
 * accent colour). Each cross-fades as it changes.
 */
@Composable
fun EditLines(lines: List<EditLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(modifier) {
        lines.forEach { l ->
            FadeLine(l.text) {
                Text(
                    it, style = MekaType.caption, maxLines = 2,
                    color = if (l.needsMeka) Meka.colors.accent else Meka.colors.textSecondary,
                )
            }
        }
    }
}

/** "+ Add event" beside the Calendar title (only while an account allows editing). */
@Composable
fun AddEventButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Text(
        "+ Add event", style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MekaType.caption, color = Meka.colors.textTertiary,
        modifier = modifier.padding(top = MekaSpace.l, bottom = MekaSpace.xs).semantics { heading() },
    )
}

@Composable
private fun FormField(
    value: String, placeholder: String, onChange: (String) -> Unit, singleLine: Boolean,
    modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MekaType.body,
) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
    ) {
        if (value.isEmpty()) Text(placeholder, style = style, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = style.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.heightIn(min = 72.dp))
                .semantics { contentDescription = placeholder },
        )
    }
}

@Composable
private fun FadeLine(target: String, content: @Composable (String) -> Unit) {
    val reduced = Meka.reducedMotion
    AnimatedContent(target, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "add-event") {
        content(it)
    }
}

@Composable
private fun FormChip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "add-chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .semantics { selected = lit }
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun Step(symbol: String, description: String, onClick: () -> Unit) {
    Text(
        symbol, style = MekaType.itemTitle, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = description }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    )
}
