package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Field
import os.meka.core.domain.AppointmentNudge

/**
 * Appointment nudges, slice 1: one card in Needs you per appointment that has come due — "Due Mon 2 Nov · every 6
 * months" · "Dentist" · "Last went Sun 12 Apr. Booked the next one?" · Booked… · Went · Not yet. Booked… unfolds a
 * field on the expand spring ("When? “Tue 20 Oct 9:30”"); the typed day and time take the card away. A date that
 * can't be read leaves the card open with [refusal] under the field, lit in the accent. Booked… and Went press in with a light haptic,
 * Not yet with a tick; the card folds away with the list's item motion and the undo bar rises. Reduced motion:
 * cross-fades. MEKA books nothing and calls no one.
 */
@Composable
internal fun AppointmentCardView(
    card: AppointmentNudge,
    refusal: String?,
    onBooked: (String) -> Unit,
    onWent: () -> Unit,
    onNotYet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var asking by rememberSaveable(card.id) { mutableStateOf(false) }
    var text by rememberSaveable(card.id) { mutableStateOf("") }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("appointment-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(card.line, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(card.question, style = MekaType.body, color = Meka.colors.textSecondary)
        Text(card.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
        AnimatedVisibility(
            visible = asking,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(card.whenHint, value = text, onValue = { text = it }) { typed ->
                    haptics.light()
                    onBooked(typed)
                }
                refusal?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CoverPill(card.bookedLabel, filled = true) { haptics.light(); asking = !asking }
            CoverPill(card.wentLabel, filled = false) { haptics.light(); onWent() }
            CoverPill(card.laterLabel, filled = false) { haptics.tick(); onNotYet() }
        }
    }
}
