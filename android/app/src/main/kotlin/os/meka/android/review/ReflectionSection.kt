package os.meka.android.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.goals.fold
import os.meka.android.goals.unfold
import os.meka.android.today.SectionLabel
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ReflectionEntry
import os.meka.core.domain.ReflectionHint
import os.meka.core.domain.ReflectionRules
import os.meka.core.domain.ReflectionSaved
import os.meka.core.domain.ReflectionView

/** What the reflection section can do; the route turns each into a core call with its haptic. */
internal class ReflectionActions(
    val save: (wentWell: String, drop: String, matters: String, goalId: String?, keepWins: Boolean) -> Unit,
    val skip: (Boolean) -> Unit,
    val remove: (ReflectionEntry) -> Unit,
    val undoRemove: (ReflectionEntry) -> Unit,
    val tick: () -> Unit,
    /** Slice 3: when on Sunday the review and reflection come (minutes after midnight). */
    val setTime: (Int) -> Unit = {},
)

/**
 * Weekly reflection in the review (V1, slice 1): "Reflect on your week" — what went well, what to drop, one thing
 * that matters next week — each a multi-line field (the keyboard's mic dictates) with suggestions from the week's
 * facts a tap adds; Save keeps it as the week's dated entry, can keep the went-well lines in Wins and make the one
 * thing's goal one of next week's goals. Open and lit from Sunday 18:00 through Monday; otherwise folded behind
 * "Reflect now". Saved weeks show their three answers with Edit; earlier weeks are in "Your reflections".
 *
 * Motion: the section staggers in with the review's others; the form unfolds/folds on the expand spring (the chevron
 * turns, tick haptic); a suggestion chip lights as it is added; the lines cross-fade; Save gives a light haptic and
 * the form folds into the saved answers; the journal unfolds its weeks in place, staggered. Reduced motion: cross-fades.
 */
internal fun LazyListScope.reflectionItems(
    r: ReflectionView,
    said: ReflectionSaved?,
    removed: ReflectionEntry?,
    index: Int,
    act: ReflectionActions,
) {
    item(key = "reflect-" + r.weekStart) {
        ReflectionCard(r, said, Modifier.animateItem().appear(rememberAppearance(index)), act)
    }
    if (r.journal.isNotEmpty() || removed != null) item(key = "reflect-journal") {
        Journal(r, removed, Modifier.animateItem().appear(rememberAppearance(index)), act)
    }
}

@Composable
private fun ReflectionCard(r: ReflectionView, said: ReflectionSaved?, modifier: Modifier, act: ReflectionActions) {
    val reduced = Meka.reducedMotion
    // Open while asking; a saved week shows its answers until Edit; otherwise folded behind "Reflect now".
    var open by rememberSaveable(r.weekStart) { mutableStateOf(r.asking) }
    var editing by rememberSaveable(r.weekStart) { mutableStateOf(false) }
    val formShown = (open || r.asking) && (!r.saved || editing) && !r.skipped
    val titleColor by animateColorAsState(if (r.asking) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "reflect-title")
    Column(
        modifier.fillMaxWidth().padding(top = MekaSpace.l).clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        SectionLabel("Reflect")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                .then(if (!r.saved && !r.skipped && !r.asking) Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Fold the reflection" else "Reflect now") {
                    act.tick(); open = !open
                } else Modifier)
                .minTouch(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(r.title, style = MekaType.itemTitle, color = titleColor, modifier = Modifier.weight(1f))
            if (!r.saved && !r.skipped && !r.asking) {
                val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "reflect-chevron")
                Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.rotate(turn))
            }
        }
        Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "reflect-line") { l ->
            Text(LoneWordGlue.kept(l), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text(LoneWordGlue.kept(r.facts), style = MekaType.caption, color = Meka.colors.textTertiary)
        // Week board item (8), the Friday review: planned against done on the Board, and what kept slipping.
        if (r.board.planned > 0) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(LoneWordGlue.kept(r.board.line), style = MekaType.caption, color = Meka.colors.textSecondary)
                r.board.slippingLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }

        if (r.skipped) {
            TextAction("Reflect now") { act.tick(); act.skip(false); open = true }
        }
        AnimatedVisibility(r.saved && !editing, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Answer(r.wentWellPrompt, r.wentWell)
                Answer(r.dropPrompt, r.drop)
                Answer(r.mattersPrompt, r.matters)
                TextAction("Edit") { act.tick(); editing = true }
            }
        }
        AnimatedVisibility(formShown, enter = unfold(), exit = fold()) {
            Form(r, act) { editing = false }
        }
        Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "reflect-said") { l ->
            l?.let { Text(LoneWordGlue.kept(it), style = MekaType.caption, color = if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary) }
        }
        WhenRow(r, act)
    }
}

/**
 * Slice 3: "Comes on Sundays at 18:00 · through Monday" with Change, which unfolds the times as chips (the set one lit);
 * a tap sets it for the review card, its heads-up and the reflection on both apps, and the chips fold away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenRow(r: ReflectionView, act: ReflectionActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(r.whenLine, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "reflect-when", modifier = Modifier.weight(1f)) { l ->
                Text(LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textTertiary)
            }
            Text(
                if (open) "Done" else "Change", style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClickLabel = if (open) "Close the times" else "Change when it comes") { act.tick(); open = !open }
                    .minTouch().padding(horizontal = MekaSpace.xs),
            )
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                r.timeChoices.forEachIndexed { i, m ->
                    val on = m == r.startMinute
                    ReflectChip(r.timeLabels.getOrElse(i) { "" }, on) { if (!on) { act.setTime(m); open = false } }
                }
            }
        }
    }
}

@Composable
private fun Form(r: ReflectionView, act: ReflectionActions, onSaved: () -> Unit) {
    var wentWell by rememberSaveable(r.weekStart) { mutableStateOf(r.wentWell) }
    var drop by rememberSaveable(r.weekStart) { mutableStateOf(r.drop) }
    var matters by rememberSaveable(r.weekStart) { mutableStateOf(r.matters) }
    var goalId by rememberSaveable(r.weekStart) { mutableStateOf<String?>(null) }
    var keepWins by rememberSaveable(r.weekStart) { mutableStateOf(true) }
    Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
        Prompt(r.wentWellPrompt, wentWell, { wentWell = it }, r.wentWellHints) { h ->
            act.tick(); wentWell = ReflectionRules.withHint(wentWell, h.text)
        }
        Prompt(r.dropPrompt, drop, { drop = it }, r.dropHints) { h ->
            act.tick(); drop = ReflectionRules.withHint(drop, h.text)
        }
        Prompt(r.mattersPrompt, matters, { matters = it }, r.mattersHints) { h ->
            act.tick(); matters = ReflectionRules.withHint(matters, h.text); goalId = h.goalId
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            ReflectChip("Also keep in Wins", keepWins) { act.tick(); keepWins = !keepWins }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Save", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                // Quality gate slice 62: a 48 dp touch around the same pill.
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button) {
                        act.save(wentWell, drop, matters, goalId, keepWins)
                        if (wentWell.isNotBlank() || drop.isNotBlank() || matters.isNotBlank()) onSaved()
                    }
                    .minTouch()
                    .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
            )
            if (!r.saved) TextAction("Skip this week") { act.tick(); act.skip(true) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Prompt(label: String, value: String, onValue: (String) -> Unit, hints: List<ReflectionHint>, onHint: (ReflectionHint) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(label, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        ) {
            if (value.isEmpty()) Text("A line or two…", style = MekaType.body, color = Meka.colors.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= ReflectionRules.MAX_FIELD) onValue(it) },
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = label },
            )
        }
        if (hints.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                hints.forEach { h ->
                    val added = value.lines().any { os.meka.core.domain.WinRules.key(it) == os.meka.core.domain.WinRules.key(h.text) }
                    ReflectChip((if (added) "✓ " else "+ ") + h.text, added) { if (!added) onHint(h) }
                }
            }
        }
    }
}

@Composable
private fun Answer(label: String, text: String) {
    if (text.isEmpty()) return
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(label, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text(text, style = MekaType.body, color = Meka.colors.textPrimary)
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClick)
            .minTouch().padding(vertical = MekaSpace.xs),
    )
}

/** "Your reflections · 4 weeks", unfolding earlier weeks (newest first) in place, each with Remove (and Undo). */
@Composable
private fun Journal(r: ReflectionView, removed: ReflectionEntry?, modifier: Modifier, act: ReflectionActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val reduced = Meka.reducedMotion
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "journal-chevron")
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide your reflections" else "Show your reflections") { act.tick(); open = !open }
                .minTouch().padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(r.journalTitle, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.weight(1f))
            Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.rotate(turn))
        }
        removed?.let { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Removed ${e.label}", style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                TextAction("Undo") { act.undoRemove(e) }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                r.journal.forEachIndexed { i, e ->
                    Column(
                        Modifier.appear(rememberAppearance(i)).fillMaxWidth().padding(top = MekaSpace.xs),
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
                            Text(
                                "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { act.remove(e) }
                                    .semantics { contentDescription = "Remove ${e.label}'s reflection" }
                                    .minTouch().padding(horizontal = MekaSpace.xs),
                            )
                        }
                        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = e.spoken }) {
                            Answer(ReflectionRules.WENT_WELL, e.wentWell)
                            Answer(ReflectionRules.DROP_PROMPT, e.drop)
                            Answer("The one thing", e.matters)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A choice chip in the reflection (the suggestions, Also keep in Wins, the times): the pill drawn as Lists' chips,
 * answering a 48 dp touch (quality gate slice 62; Lists' Chip is ~32 dp to tap); a long suggestion takes two lines.
 */
@Composable
private fun ReflectChip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "reflect-chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 2,
        modifier = Modifier.semantics { selected = lit }.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
