package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AppointmentBookSheet
import os.meka.core.domain.AppointmentNudge

/**
 * Appointment nudges, slice 1: one card in Needs you per appointment that has come due — "Due Mon 2 Nov · every 6
 * months" · "Dentist" · "Last went Sun 12 Apr. Booked the next one?" · Booked… · Went · Not yet. Booked… opens its
 * sheet ([book]: the next week's days and the usual times as chips, a field for a later day; walk-through week 5, A17)
 * over the page; Save takes the card away. A date that can't be read opens the sheet again with [refusal] in the accent. Booked… and Went press in with a light haptic,
 * Not yet with a tick; the card folds away with the list's item motion and the undo bar rises. Reduced motion:
 * cross-fades. MEKA books nothing and calls no one.
 *
 * Slice 2b: a second row, Call to book (a task on Today; the card asks again two days after the call) and Copy email
 * (a booking email on the clipboard for Meka to send himself), each pressing in with a tick.
 */
@Composable
internal fun AppointmentCardView(
    card: AppointmentNudge,
    book: AppointmentBookSheet,
    refusal: String?,
    onBooked: (String) -> Unit,
    onWent: () -> Unit,
    onNotYet: () -> Unit,
    onCall: () -> Unit,
    onEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    var asking by rememberSaveable(card.id) { mutableStateOf(false) }
    LaunchedEffect(refusal) { if (refusal != null) asking = true }
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
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CoverPill(card.bookedLabel, filled = true) { haptics.light(); asking = true }
            CoverPill(card.wentLabel, filled = false) { haptics.light(); onWent() }
            CoverPill(card.laterLabel, filled = false) { haptics.tick(); onNotYet() }
        }
        Row(Modifier.padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CoverPill(card.callLabel, filled = false) { haptics.tick(); onCall() }
            CoverPill(card.emailLabel, filled = false) { haptics.tick(); onEmail() }
        }
    }
    if (asking) AppointmentBookSheetView(book, refusal, onSave = { words -> asking = false; onBooked(words) }, onClose = { asking = false })
}
