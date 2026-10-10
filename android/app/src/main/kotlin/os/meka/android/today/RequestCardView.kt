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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.search.SearchPane
import os.meka.core.domain.RequestCard
import os.meka.core.facade.MekaCore

/** What Meka pressed on a request card. */
enum class RequestChoice { ADD, CHANGE, DECLINE }

/**
 * A request from someone Meka watches (V1, requests slice 4), in Needs you above the stack: "From Wife · 14:02", the
 * quote, what Add would do ("Add task: Pick up dry cleaning · Tomorrow"), what more it changes (work from home), then
 * Add · Change · Not a task. Each press gives a light haptic (Not a task a tick); the card folds away with the list's
 * item motion and the undo bar rises. Nothing is sent to anyone.
 */
@Composable
internal fun RequestCardView(card: RequestCard, onChoice: (RequestChoice) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("request-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(card.from, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        Text(card.quote, style = MekaType.body, color = Meka.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(card.action, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.xxs))
        card.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            RequestPill(card.addLabel, filled = true, spoken = "${card.addLabel}: ${card.action}") { haptics.light(); onChoice(RequestChoice.ADD) }
            card.changeLabel?.let { label ->
                RequestPill(label, filled = false, spoken = "$label ${card.action}") { haptics.light(); onChoice(RequestChoice.CHANGE) }
            }
            RequestPill(card.declineLabel, filled = false, quiet = true, spoken = card.declineLabel) { haptics.tick(); onChoice(RequestChoice.DECLINE) }
        }
    }
}

@Composable
private fun RequestPill(label: String, filled: Boolean, spoken: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = when { filled -> Meka.colors.onAccent; quiet -> Meka.colors.textSecondary; else -> Meka.colors.accent },
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** The task Change made, opened over the screen as search's detail (its title as the query) so it can be edited. */
data class RequestChange(val title: String, val taskId: String)

/** Hosts the task Change made: Search springs up with its detail over the results; closing drops it away. */
@Composable
internal fun RequestChangePane(core: MekaCore, moves: DecisionMoves) {
    val change = moves.changed.value
    var shown by remember { mutableStateOf<RequestChange?>(null) }
    if (change != null) shown = change
    MekaPane(visible = change != null) {
        shown?.let { c ->
            key(c.taskId) {
                SearchPane(core, onClose = { moves.changed.value = null }, openItem = { moves.changed.value = null },
                    initialQuery = c.title, initialTask = c.taskId)
            }
        }
    }
}
