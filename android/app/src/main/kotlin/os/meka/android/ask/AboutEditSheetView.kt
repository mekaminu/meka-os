package os.meka.android.ask

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import os.meka.core.domain.AboutEditSheet
import os.meka.core.domain.AboutField

/** The tag the screen tests find an About you line's sheet by. */
const val ABOUT_EDIT_SHEET_TAG = "about-edit-sheet"

/**
 * One About you line's sheet (walk-through week 5, A17: no open input boxes on a page): the line's name, a field
 * holding what's there with its short example, the fuller how-to or what the line is for, and Close · Clear · Save.
 * Save (or Done on the keyboard) hands [onSave] what's typed (a blank clears, as before); Clear hands it a blank.
 * [refusal] (the words couldn't be kept) shows in the accent and the sheet stays open with the words to fix.
 *
 * Motion: rises over a fading scrim (reduced motion: a cross-fade); the refusal cross-fades in; the scrim, Close or
 * Back closes it.
 */
@Composable
internal fun AboutEditSheetView(
    sheet: AboutEditSheet,
    refusal: String?,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    var text by rememberSaveable(sheet.title) { mutableStateOf(sheet.value) }
    LaunchedEffect(Unit) { rise.animateTo(1f, MekaMotion.appear(reduced)) }
    val close: () -> Unit = { scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose() } }
    // The add-a-child sheet needs words; a line's sheet saves a blank as a clear (Calendars' places: follow About you).
    val canSave = sheet.blankSaves || text.isNotBlank()
    val save: () -> Unit = { if (canSave) { haptics.light(); onSave(text) } }

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
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(MekaSpace.m)
                    .testTag(ABOUT_EDIT_SHEET_TAG),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(sheet.title, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
                ) {
                    if (text.isEmpty()) Text(sheet.hint, style = MekaType.body, color = Meka.colors.textTertiary)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.take(sheet.maxLength) },
                        singleLine = true,
                        textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                        cursorBrush = SolidColor(Meka.colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done,
                            capitalization = if (sheet.field == null) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                            keyboardType = when (sheet.field) {
                            AboutField.PARTNER_EMAIL -> KeyboardType.Email
                            AboutField.SCHOOL_WEBSITE -> KeyboardType.Uri
                            AboutField.PARTNER_PHONE, AboutField.MOBILE, AboutField.ASSISTANT_NUMBER, AboutField.VOICEMAIL_NUMBER -> KeyboardType.Phone
                            else -> KeyboardType.Text
                        }),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = sheet.spokenHint },
                    )
                }
                Crossfade(refusal, animationSpec = MekaMotion.appear(reduced), label = "about-sheet-refusal") { r ->
                    if (r != null) Text(r, style = MekaType.caption, color = Meka.colors.accent)
                }
                Text(os.meka.core.domain.LoneWordGlue.kept(sheet.note), style = MekaType.caption, color = Meka.colors.textTertiary)
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    sheet.clearLabel?.let { clear ->
                        Text(clear, style = MekaType.metaStrong, color = Meka.colors.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                .clickable(role = Role.Button) { haptics.tick(); onSave("") }
                                .semantics { contentDescription = "$clear ${sheet.title}" }
                                .minTouch().padding(horizontal = MekaSpace.m))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Close", style = MekaType.metaStrong, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = close)
                            .minTouch().padding(horizontal = MekaSpace.m))
                    Text(
                        sheet.saveLabel, style = MekaType.metaStrong,
                        color = if (canSave) Meka.colors.onAccent else Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                            .clickable(role = Role.Button, enabled = canSave, onClick = save)
                            .semantics { contentDescription = "${sheet.saveLabel}, ${sheet.title}" }
                            .minTouch().clip(RoundedCornerShape(MekaRadius.pill))
                            .background(if (canSave) Meka.colors.accent else Meka.colors.surfaceRaised)
                            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
                    )
                }
            }
        }
    }
}
