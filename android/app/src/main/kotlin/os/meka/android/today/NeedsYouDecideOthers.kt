package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.lists.Field
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
 * Booked… · Went · Not yet and the quieter Call to book · Copy email. Booked… unfolds the When? field inside the card on
 * the expand spring (a cross-fade with reduced motion), as the full card did; a date it can't read leaves [refusal]
 * under the field in the accent. MEKA books nothing and calls no one.
 */
@Composable
internal fun AppointmentDecideView(
    card: AppointmentNudge,
    refusal: String?,
    onBooked: (String) -> Unit,
    onWent: () -> Unit,
    onNotYet: () -> Unit,
    onCall: () -> Unit,
    onEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = Meka.reducedMotion
    var asking by rememberSaveable(card.id) { mutableStateOf(false) }
    var text by rememberSaveable(card.id) { mutableStateOf("") }
    NeedsYouDecideShell(
        remember(card) { NeedsYouDecideRules.appointment(card) },
        onYes = { asking = !asking }, onChange = onWent, onNo = onNotYet,
        onMore = { i -> if (i == 0) onCall() else onEmail() },
        modifier = modifier,
    ) {
        AnimatedVisibility(
            visible = asking,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(card.whenHint, value = text, onValue = { text = it }) { typed -> onBooked(typed) }
                refusal?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
    }
}
