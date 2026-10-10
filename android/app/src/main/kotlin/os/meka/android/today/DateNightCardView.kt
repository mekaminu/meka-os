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
import os.meka.core.domain.DateNightNudge

/**
 * Date night, slice 2: the week-before card in Needs you, beside the school's questions — "In 7 days · from 19:00" ·
 * "Date night · Fri 23 Oct" · "Book somewhere and arrange cover" · Booked · Skip this one. Booked presses in with a
 * light haptic, Skip this one with a tick; the card folds away with the list's item motion and the undo bar rises.
 * MEKA books nothing and messages no one: Booked only takes the card (and its heads-up) away.
 */
@Composable
internal fun DateNightCardView(card: DateNightNudge, onBooked: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("date-night-${card.day}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(card.line, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(card.question, style = MekaType.body, color = Meka.colors.textSecondary)
        Text(card.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CoverPill(card.bookedLabel, filled = true) { haptics.light(); onBooked() }
            CoverPill(card.skipLabel, filled = false) { haptics.tick(); onSkip() }
        }
    }
}
