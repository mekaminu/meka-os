package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.TouchPill
import os.meka.core.domain.BookingCallRow
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.LoneWordGlue

/**
 * Obi on calls, slice 2a: one call card under CALLS FOR YOU (mockup `docs/design/calls/booking-call.dc.html`, Call
 * card): the business and its number line, the status (lit while it needs his tap), WHEN · WINDOW · PEOPLE tiles,
 * Party size − / +, OBI MAY SAY and OBI WON'T, the calendar · length · cost foot, why Call now is greyed when it is,
 * the server's result once the call is over, and Call now · Edit · Cancel. The words are the core's
 * [BookingCallRules.rows], the same as the Mac's.
 *
 * Motion: the card resizes on the expand spring when its state changes (reduced motion: no resize); the pane staggers
 * it in with the rest.
 */
@Composable
fun BookingCallCard(
    r: BookingCallRow, modifier: Modifier,
    onPeople: (Int) -> Unit, onCall: () -> Unit, onEdit: () -> Unit, onCancel: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m)
            .let { if (reduced) it else it.animateContentSize(MekaMotion.expand(false)) }
            .semantics(mergeDescendants = false) { contentDescription = r.spoken }
            .testTag("call-card"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(LoneWordGlue.kept(r.status).uppercase(), style = MekaType.sectionLabel,
            color = if (r.needsYou) Meka.colors.accent else Meka.colors.textTertiary)
        Text(r.business, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Text(LoneWordGlue.kept(r.numberLine), style = MekaType.caption,
            color = if (r.numberOk) Meka.colors.textSecondary else Meka.colors.textTertiary, modifier = Modifier.testTag("call-number"))
        Text(LoneWordGlue.kept(r.what), style = MekaType.body, color = Meka.colors.textSecondary)
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Tile(BookingCallRules.WHEN, r.whenTile, Modifier.weight(1f))
            Tile(BookingCallRules.WINDOW, r.windowTile, Modifier.weight(1f))
            Tile(BookingCallRules.PEOPLE, r.peopleTile, Modifier.weight(1f))
        }
        if (r.canChangePeople) {
            val n = r.peopleTile.toIntOrNull() ?: 1
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(BookingCallRules.PARTY, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                TouchPill("−", Meka.colors.surface, Meka.colors.accent, spoken = "One fewer person", modifier = Modifier.testTag("call-less")) {
                    if (n > 1) onPeople(n - 1)
                }
                TouchPill("+", Meka.colors.surface, Meka.colors.accent, spoken = "One more person", modifier = Modifier.testTag("call-more")) {
                    if (n < BookingCallRules.MAX_PEOPLE) onPeople(n + 1)
                }
            }
        }
        r.note?.let { Text(LoneWordGlue.kept("Ask for: $it"), style = MekaType.caption, color = Meka.colors.textSecondary) }
        Text(BookingCallRules.MAY_SAY.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.xs))
        r.maySay.forEach { Text("· $it", style = MekaType.caption, color = Meka.colors.textSecondary) }
        Text(BookingCallRules.WONT.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.xs))
        r.wont.forEach { Text("· $it", style = MekaType.caption, color = Meka.colors.textSecondary) }
        r.foot?.let {
            Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs).testTag("call-foot"))
        }
        r.result?.let { Text(LoneWordGlue.kept(it), style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("call-result")) }
        r.blocked?.let { Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.testTag("call-blocked")) }
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            if (r.canCancel) {
                TouchPill(BookingCallRules.CANCEL, Meka.colors.surface, Meka.colors.textSecondary, spoken = "${BookingCallRules.CANCEL}: ${r.business}", onClick = onCancel)
            }
            if (r.canEdit) {
                TouchPill(BookingCallRules.EDIT, Meka.colors.surface, Meka.colors.accent, spoken = "${BookingCallRules.EDIT}: ${r.business}",
                    modifier = Modifier.testTag("call-edit"), onClick = onEdit)
            }
            if (r.canEdit) {
                TouchPill(
                    BookingCallRules.CALL_NOW,
                    if (r.canCall) Meka.colors.accent else Meka.colors.surface,
                    if (r.canCall) Meka.colors.onAccent else Meka.colors.textTertiary,
                    spoken = "${BookingCallRules.CALL_NOW}: ${r.business}", modifier = Modifier.testTag("call-now"),
                ) { if (r.canCall) onCall() }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface).padding(MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text(value, style = MekaType.body, color = Meka.colors.textPrimary)
    }
}

/**
 * Edit the call card: who to ring, their number (checked on Call now) and anything Obi should ask for. Motion: the
 * scrim fades and the sheet rises on the expand spring; reduced motion cross-fades.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BookingCallEditSheet(row: BookingCallRow?, onClose: () -> Unit, onSave: (business: String, number: String, note: String) -> Unit) {
    val reduced = Meka.reducedMotion
    BackHandler(enabled = row != null, onBack = onClose)
    AnimatedVisibility(visible = row != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val r = row ?: return@AnimatedVisibility
        var business by remember(r.id) { mutableStateOf(r.business) }
        var number by remember(r.id) { mutableStateOf(if (r.numberOk) r.numberLine.substringBefore(" · ") else "") }
        var note by remember(r.id) { mutableStateOf(r.note.orEmpty()) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close ${BookingCallRules.EDIT_TITLE}" },
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
                    .heightIn(max = 560.dp)
                    .navigationBarsPadding().imePadding()
                    .padding(MekaSpace.m)
                    .testTag("call-edit-sheet"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(BookingCallRules.EDIT_TITLE, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                Field(BookingCallRules.EDIT_BUSINESS, business, "", KeyboardType.Text, BookingCallRules.MAX_BUSINESS, "call-edit-business") { business = it }
                Field(BookingCallRules.EDIT_NUMBER, number, "01234 567890", KeyboardType.Phone, 24, "call-edit-number") { number = it }
                Field(BookingCallRules.EDIT_NOTE, note, BookingCallRules.EDIT_NOTE_HINT, KeyboardType.Text, BookingCallRules.MAX_NOTE, "call-edit-note") { note = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TouchPill(BookingCallRules.CANCEL, Meka.colors.surfaceRaised, Meka.colors.textSecondary, onClick = onClose)
                    TouchPill(BookingCallRules.SAVE, Meka.colors.accent, Meka.colors.onAccent, modifier = Modifier.testTag("call-edit-save")) {
                        onSave(business, number, note)
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, type: KeyboardType, max: Int, tag: String, onChange: (String) -> Unit) {
    Text(label, style = MekaType.caption, color = Meka.colors.textSecondary)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(max)) },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = type, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag(tag),
        )
    }
}
