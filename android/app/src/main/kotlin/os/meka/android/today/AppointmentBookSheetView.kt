package os.meka.android.today

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Chip
import os.meka.android.lists.Field
import os.meka.core.domain.AppointmentBookRules
import os.meka.core.domain.AppointmentBookSheet

/** The tag the screen tests find Booked…'s sheet by. */
const val APPOINTMENT_BOOK_SHEET_TAG = "appointment-book-sheet"

/**
 * Booked…'s sheet (walk-through week 5, A17: no open input box on the page): the appointment's name and "When is it
 * booked?", the next seven days and the usual times as chips, a field for a later day whose example is always ahead of
 * today, the chosen day and time in one line, and Save. The sheet rises over a fading scrim (reduced motion: a
 * cross-fade); the scrim, Close or Back closes it. [refusal] (a typed day that couldn't be read) shows in the accent.
 * Save hands [onSave] the words the booking reader reads; Navilife books nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppointmentBookSheetView(
    sheet: AppointmentBookSheet,
    refusal: String?,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    var day by rememberSaveable(sheet.title) { mutableStateOf<Long?>(null) }
    var minute by rememberSaveable(sheet.title) { mutableStateOf<Int?>(null) }
    var typed by rememberSaveable(sheet.title) { mutableStateOf("") }
    LaunchedEffect(Unit) { rise.animateTo(1f, MekaMotion.appear(reduced)) }
    val close: () -> Unit = { scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose() } }
    val save: () -> Unit = {
        AppointmentBookRules.toSave(day, minute, typed)?.let { words ->
            haptics.light()
            onSave(words)
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = rise.value }
                    .background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { close() } }
                    .semantics { contentDescription = "Close ${sheet.title}" },
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer {
                        alpha = rise.value
                        if (!reduced) translationY = (1f - rise.value) * 48.dp.toPx()
                    }
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 640.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(MekaSpace.m)
                    .testTag(APPOINTMENT_BOOK_SHEET_TAG),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(sheet.title, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                Text(sheet.question, style = MekaType.body, color = Meka.colors.textSecondary)
                Text(sheet.dayLabel, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    sheet.days.forEach { d ->
                        Chip(d.label, day == d.day) { haptics.tick(); day = if (day == d.day) null else d.day; typed = "" }
                    }
                }
                Text(sheet.timeLabel, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    sheet.times.forEach { t ->
                        Chip(t.label, minute == t.minute) { haptics.tick(); minute = if (minute == t.minute) null else t.minute }
                    }
                }
                Field(sheet.typeHint, value = typed, onValue = { typed = it; if (it.isNotBlank()) day = null }, keep = true,
                    tag = "appointment-book-typed") { save() }
                refusal?.let { Text(needsShown(it), style = MekaType.caption, color = Meka.colors.accent) }
                Crossfade(if (typed.isNotBlank()) typed.trim() else AppointmentBookRules.chosenLine(day, minute),
                    animationSpec = MekaMotion.appear(reduced), label = "appointment-book-chosen") { line ->
                    Text(line, style = MekaType.body, color = Meka.colors.textPrimary)
                }
                Text(needsShown(sheet.note), style = MekaType.caption, color = Meka.colors.textTertiary)
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Close", style = MekaType.metaStrong, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = close)
                            .minTouch().padding(horizontal = MekaSpace.m))
                    val lit = AppointmentBookRules.canSave(day, typed)
                    Text(
                        sheet.saveLabel, style = MekaType.metaStrong,
                        color = if (lit) Meka.colors.onAccent else Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                            .clickable(role = Role.Button, enabled = lit, onClick = save)
                            .semantics { contentDescription = "${sheet.saveLabel}, ${sheet.title}" }
                            .minTouch().clip(RoundedCornerShape(MekaRadius.pill))
                            .background(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised)
                            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
                    )
                }
            }
        }
    }
}
