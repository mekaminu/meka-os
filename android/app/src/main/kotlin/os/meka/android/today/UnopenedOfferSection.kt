package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.MailUnopenedCard
import os.meka.core.domain.MailUnopenedRow
import os.meka.core.domain.MailUnopenedRules
import os.meka.core.facade.MekaCore

/**
 * Inbox autopilot, slice 5c: the weekly "Not opened in 30 days · Unsubscribe from 11?" in Needs you, from Saturday 09:00
 * until answered that week (on either app). The senders (busiest first) each with Keep; **Unsubscribe from all N** asks
 * the server to unsubscribe from each at its next check; Not now folds it away until next Saturday. Every answer has Undo
 * on the bar. Show/Hide unfolds the senders in place (expand spring; reduced motion: cross-fade).
 */
@Composable
internal fun UnopenedOfferSection(
    card: MailUnopenedCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    fun answer(action: suspend () -> String) {
        scope.launch {
            val said = runCatching { action() }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoUnopened() } }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-unopened"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = card.spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(card.title)
                // The headline cross-fades as senders are kept.
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "unopened-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("unopened-headline"))
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            // A kept sender leaves and the list closes up on the expand spring.
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                card.rows.forEach { row ->
                    androidx.compose.runtime.key(row.id) {
                        UnopenedRow(row) { haptics.light(); answer { core.keepMailSender(row.id) } }
                    }
                }
                card.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                Text(card.note, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            }
        }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            UnopenedPill(card.unsubscribeLabel, spoken = "${card.unsubscribeLabel}: Navilife unsubscribes at its next check") {
                haptics.medium(); answer { core.unsubscribeUnopened() }
            }
            UnopenedPill(MailUnopenedRules.NOT_NOW, quiet = true, spoken = "${MailUnopenedRules.NOT_NOW}, ask again next Saturday") {
                haptics.light(); answer { core.notNowUnopened() }
            }
            val label = if (open) MailUnopenedRules.HIDE else MailUnopenedRules.SHOW
            UnopenedPill(label, quiet = true, spoken = "$label the senders") { haptics.tick(); open = !open }
        }
    }
}

@Composable
private fun UnopenedRow(row: MailUnopenedRow, onKeep: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("unopened-${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            Text(row.name, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        UnopenedPill(MailUnopenedRules.KEEP, quiet = true, spoken = "${MailUnopenedRules.KEEP} ${row.name}, don't offer it again", onClick = onKeep)
    }
}

@Composable
private fun UnopenedPill(label: String, spoken: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = if (quiet) Meka.colors.textSecondary else Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
