package os.meka.android.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import os.meka.core.domain.AppointmentBookSheet
import os.meka.core.domain.AppointmentNudge
import os.meka.core.domain.DateNightNudge
import os.meka.core.domain.NeedsYouDecideRules
import os.meka.core.domain.SchoolCover

/**
 * The approved Needs you look, slice 4 (`docs/design/needs-you/needs-you.dc.html`): a school day off on an office day
 * as one compact TO DECIDE card: the school icon, "Alex and Sam are off Mon 21 Dec" over "INSET day · in 7 days ·
 * You're in the office that day. Who's covering?", then I'll work from home · Covered. The taps do what the full card's
 * did; nothing is sent to anyone.
 */
@Composable
internal fun SchoolCoverDecideView(card: SchoolCover, onAnswer: (home: Boolean) -> Unit, modifier: Modifier = Modifier) {
    NeedsYouDecideShell(remember(card) { NeedsYouDecideRules.school(card) }, onYes = { onAnswer(true) }, onNo = { onAnswer(false) }, modifier = modifier)
}

/** Date night's week-before card, compact: "Date night · Fri 23 Oct" over its when and question; Booked · Skip this one. Nothing is booked. */
@Composable
internal fun DateNightDecideView(card: DateNightNudge, onBooked: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    NeedsYouDecideShell(remember(card) { NeedsYouDecideRules.dateNight(card) }, onYes = onBooked, onNo = onSkip, modifier = modifier)
}

/**
 * An appointment that has come due, compact: "Dentist" over "Due Mon 2 Nov · every 6 months · Last went …", then
 * Booked… · Went · Not yet and the quieter Call to book · Copy email. Booked… opens its sheet ([book]: the next week's
 * days and the usual times as chips, a field for a later day) over the page rather than a box inside the card
 * (walk-through week 5, A17); Save closes it at once, and a typed day it can't read opens it again with [refusal] in the
 * accent. MEKA books nothing and calls no one.
 */
@Composable
internal fun AppointmentDecideView(
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
    var asking by rememberSaveable(card.id) { mutableStateOf(false) }
    LaunchedEffect(refusal) { if (refusal != null) asking = true }
    NeedsYouDecideShell(
        remember(card) { NeedsYouDecideRules.appointment(card) },
        onYes = { asking = true }, onChange = onWent, onNo = onNotYet,
        onMore = { i -> if (i == 0) onCall() else onEmail() },
        modifier = modifier,
    )
    if (asking) AppointmentBookSheetView(book, refusal, onSave = { words -> asking = false; onBooked(words) }, onClose = { asking = false })
}
