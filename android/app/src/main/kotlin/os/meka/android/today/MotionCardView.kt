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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionControl
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Chip
import os.meka.core.domain.MotionCard
import os.meka.core.domain.MotionRules

/**
 * Motion pass 2: the one-time card on Today when the phone's "Remove animations" is on and nothing is chosen in
 * Appearance → Motion. "Turn on motion" chooses Expressive (Today plays its entrance from then on), "Keep it still"
 * chooses Off; either way it's a choice, so the card leaves (the list's item motion) and doesn't come back.
 */
@Composable
internal fun MotionSystemCard(card: MotionCard, motion: MotionControl, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised).padding(MekaSpace.l)
            .semantics { contentDescription = "${card.title}. ${card.line}" },
    ) {
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(card.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Chip(card.turnOn, lit = true) { haptics.light(); motion.set(MotionRules.CARD_TURN_ON) }
            Chip(card.keepStill, lit = false) { haptics.tick(); motion.set(MotionRules.CARD_KEEP_STILL) }
        }
    }
}
