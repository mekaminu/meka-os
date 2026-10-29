package os.meka.android.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.today.MailReaderSheet
import os.meka.core.domain.MailSourceRules
import os.meka.core.domain.RenewalEmail
import os.meka.core.facade.MekaCore

/**
 * Renewals keep their email (Meka, 2026-10-28), slice 1: **From the email** on a Renewals card made from an email —
 * who sent it, the subject, the day it came, and what Navilife read from it as small tiles (amount, plan, renews, cancel
 * by) — with **Open the email**, which raises the in-app reader on that exact message (the reader's own rise; reduced
 * motion: its cross-fade), Open in Gmail / Outlook at its foot. Plain text only: the email's words are never acted on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RenewalEmailBox(id: String, box: RenewalEmail, core: MekaCore, tap: () -> Unit) {
    var reading by rememberSaveable(id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m).testTag("renewal-email-$id"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = box.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(MailSourceRules.BOX_TITLE.uppercase(), style = MekaType.captionStrong, color = Meka.colors.textTertiary)
            Text(box.from, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
            box.subject?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2) }
            Text(box.received, style = MekaType.caption, color = Meka.colors.textTertiary)
            if (box.tiles.isNotEmpty()) {
                FlowRow(Modifier.padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    box.tiles.forEach { t ->
                        Column(Modifier.clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs)) {
                            Text(t.label, style = MekaType.caption, color = Meka.colors.textTertiary)
                            Text(t.value, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
                        }
                    }
                }
            }
        }
        Actions { Action(box.openLabel) { tap(); reading = true } }
    }
    if (reading) {
        MailReaderSheet(
            load = { images -> core.readRenewalEmail(id, images) },
            actions = emptyList(),
            openAttachment = { v, i -> core.openMailAttachment(v, i) },
            onClose = { reading = false },
        )
    }
}
