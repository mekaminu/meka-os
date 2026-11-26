package os.meka.android.notify

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.selected
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
import os.meka.core.domain.NotifyChoice
import os.meka.core.domain.NotifyChoiceRow
import os.meka.core.domain.NotifyChoiceSheet
import os.meka.core.domain.LoneWordGlue

/** The tags the screen tests find Notifications' choice rows and sheet by. */
const val NOTIFY_CHOICE_ROW_TAG = "notify-choice-row"
const val NOTIFY_CHOICE_SHEET_TAG = "notify-choice-sheet"

/**
 * A Notifications choice row (walk-through week 5, A17: no open chip rows on a page): one card row, the title on the
 * left, what's chosen on the right ("Morning brief" · "Digest"), and ›; a tap opens [NotifyChoiceSheetView].
 */
@Composable
internal fun NotifyChoiceRowView(row: NotifyChoiceRow, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = row.spoken }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .testTag(NOTIFY_CHOICE_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(LoneWordGlue.kept(row.title), style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(row.value, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1)
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}

/**
 * A Notifications choice sheet: its title, one row per choice with what it does, the chosen one lit, and a note on
 * what the choice touches. A tap on a choice saves it and closes the sheet; Close, the scrim or Back closes it as it was.
 *
 * With [several] (Study's When you can study, walk-through week 5 A17) a tap lights or dims its choice and the sheet
 * stays open; its button reads Done.
 *
 * Motion: rises over a fading scrim (reduced motion: a cross-fade); the lit dot blends its colour; a choice gives a
 * tick haptic.
 */
@Composable
internal fun NotifyChoiceSheetView(sheet: NotifyChoiceSheet, onChoose: (String) -> Unit, onClose: () -> Unit, several: Boolean = false) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, MekaMotion.appear(reduced)) }
    val close: () -> Unit = { scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose() } }

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
                    .heightIn(max = 560.dp)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(MekaSpace.m)
                    .testTag(NOTIFY_CHOICE_SHEET_TAG),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(LoneWordGlue.kept(sheet.title), style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                sheet.choices.forEach { c ->
                    ChoiceRow(c, several) {
                        if (several) { haptics.tick(); onChoose(c.name) }
                        else {
                            if (!c.lit) { haptics.tick(); onChoose(c.name) }
                            close()
                        }
                    }
                }
                Text(LoneWordGlue.kept(sheet.note), style = MekaType.caption, color = Meka.colors.textTertiary)
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(if (several) "Done" else "Close", style = MekaType.metaStrong, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = close)
                            .minTouch().padding(horizontal = MekaSpace.m))
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(c: NotifyChoice, several: Boolean, onTap: () -> Unit) {
    val dot by animateColorAsState(if (c.lit) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "notify-choice-dot")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = if (several) Role.Checkbox else Role.RadioButton, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = c.spoken; selected = c.lit }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(c.label, style = MekaType.body, color = if (c.lit) Meka.colors.accent else Meka.colors.textPrimary)
            Text(LoneWordGlue.kept(c.line), style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
    }
}
