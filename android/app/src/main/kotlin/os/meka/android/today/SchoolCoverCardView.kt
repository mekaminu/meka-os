package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.SchoolCover

/**
 * School rhythm, slice 1: the week-ahead question in Needs you, above the requests — "Rex and Logan are off Mon
 * 27 Oct" · "INSET day · in 5 days" · "You're in the office that day. Who's covering?" · what working from home changes
 * · I'll work from home · Covered. Each press gives a light haptic (Covered a tick); the card folds away with the list's
 * item motion and the undo bar rises. Nothing is sent to anyone.
 */
@Composable
internal fun SchoolCoverCardView(card: SchoolCover, onAnswer: (home: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("school-cover-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(card.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(card.question, style = MekaType.body, color = Meka.colors.textSecondary)
        Text(card.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CoverPill(card.homeLabel, filled = true) { haptics.light(); onAnswer(true) }
            CoverPill(card.coveredLabel, filled = false) { haptics.tick(); onAnswer(false) }
        }
    }
}

@Composable
private fun CoverPill(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = if (filled) Meka.colors.onAccent else Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
