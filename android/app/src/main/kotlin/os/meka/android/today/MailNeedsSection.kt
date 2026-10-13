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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.MailNeedsCard
import os.meka.core.domain.MailNeedsRow
import os.meka.core.domain.MailNeedsRules
import os.meka.core.facade.MekaCore

/**
 * Inbox autopilot, slice 6a: "From your mail" in Needs you — the emails the server sorted as needing Meka, still in
 * the inbox, newest first: who, which account (the business one wears a Business chip), when, the subject and the
 * first words, each with **Done** (archived at the server's next check), **Task** and **Waiting for** (slice 6b-1: made
 * from the email, which is archived too) and **Snooze** (back tomorrow 08:00). Each has Undo on the bar. Tapping the header folds the emails in and out (expand spring; reduced motion: cross-fade).
 */
@Composable
internal fun MailNeedsSection(
    card: MailNeedsCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    fun answer(action: suspend () -> String) {
        scope.launch {
            val said = runCatching { action() }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoMailItem() } }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-needs"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = "${card.title}. ${card.headline}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(card.title)
                // The headline cross-fades as emails are done or snoozed.
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-needs-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("mail-needs-headline"))
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            // A done or snoozed email leaves and the list closes up on the expand spring.
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                card.rows.forEach { row ->
                    androidx.compose.runtime.key(row.id) {
                        MailNeedsRowView(
                            row,
                            onDone = { haptics.medium(); answer { core.doneMailItem(row.id) } },
                            onTask = { haptics.medium(); answer { core.taskFromMailItem(row.id) } },
                            onWaiting = { haptics.medium(); answer { core.waitingFromMailItem(row.id) } },
                            onSnooze = { haptics.light(); answer { core.snoozeMailItem(row.id) } },
                        )
                    }
                }
                card.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                card.snoozedLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailNeedsRowView(row: MailNeedsRow, onDone: () -> Unit, onTask: () -> Unit, onWaiting: () -> Unit, onSnooze: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("mail-item-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(row.from, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (row.business) {
                    Text(
                        MailNeedsRules.BUSINESS_CHIP, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                    )
                }
            }
            Text(row.subject, style = MekaType.body, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.preview?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(row.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        // Four answers wrap onto a second line on the narrow cover screen rather than squeezing.
        FlowRow(
            Modifier.padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            MailNeedsPill(MailNeedsRules.DONE, spoken = "${MailNeedsRules.DONE}: archive ${row.subject}", onClick = onDone)
            MailNeedsPill(MailNeedsRules.TASK, spoken = "Make a task from ${row.subject} and archive the email", onClick = onTask)
            MailNeedsPill(MailNeedsRules.WAITING, quiet = true, spoken = "Waiting for ${row.from} on ${row.subject}, chase in 3 days", onClick = onWaiting)
            MailNeedsPill(MailNeedsRules.SNOOZE, quiet = true, spoken = "${MailNeedsRules.SNOOZE} ${row.subject} till tomorrow 8 o'clock", onClick = onSnooze)
        }
    }
}

@Composable
private fun MailNeedsPill(label: String, spoken: String, quiet: Boolean = false, onClick: () -> Unit) {
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
