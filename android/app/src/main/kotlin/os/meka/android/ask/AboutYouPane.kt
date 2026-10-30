package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.AboutField
import os.meka.core.domain.AboutRow
import os.meka.core.domain.AboutSaved
import os.meka.core.domain.AboutYouRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val ABOUT_YOU_LIST_TAG = "about-you-list"

/**
 * Ask → Settings → About you (rule 13, slice 1): who's who, home and work, typed once and kept in Meka's own synced data
 * rather than MEKA's code. Each line is a field holding what's there; Done saves it (blank clears it, with Undo). The
 * partner's "Watch for requests" switch, then the children (one row each with Remove, and a field to add one:
 * "Alex, U7, Oakfield School"). The Mac's About you sheet edits the same profile.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and each line under a field
 * cross-fade (a refused line blends to the accent with a tick haptic; a save gives a light haptic); sections and rows
 * stagger in 40 ms apart; children arrive and leave with the list's item motion; the switch's pill blends its colour
 * with a tick haptic. Reduced motion: cross-fades.
 */
@Composable
fun AboutYouPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val v by core.aboutYouView.collectAsState()
    // What the last save on each field said ("children" for the add-a-child field).
    val said = remember { mutableStateMapOf<String, AboutSaved>() }
    val childText = remember { mutableStateMapOf<String, String>() }

    fun react(key: String, r: AboutSaved) {
        said[key] = r
        if (r.refused) haptics.tick() else haptics.light()
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag(ABOUT_YOU_LIST_TAG),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text(AboutYouRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ABOUT_YOU)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "about-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "about-${r.field.key}" }) { i, row ->
            // A field's line belongs to its field: 16 dp more before the next label (24 dp in all), 32 dp more before a
            // new group (Your name, Home, Your mobile), so the fields no longer run together (quality gate slice 26).
            Column(Modifier.animateItem().padding(top = if (row.field == AboutField.SHOWN_NAME || row.field == AboutField.HOME || row.field == AboutField.MOBILE) MekaSpace.xl else MekaSpace.m)
                .appear(rememberAppearance(2 + i)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                AboutLine(row, said[row.field.key],
                    onSave = { typed -> scope.launch { runCatching { core.setAboutYou(row.field, typed) }.getOrNull()?.let { react(row.field.key, it) } } },
                    onUndo = { back -> scope.launch { runCatching { core.setAboutYou(row.field, back) }.getOrNull()?.let { react(row.field.key, it) } } })
                if (row.field == AboutField.PARTNER_EMAIL) v.watchLine?.let { line ->
                    WatchRow(v.partnerWatch, line) {
                        haptics.tick()
                        scope.launch { runCatching { core.setAboutYouPartnerWatch(!v.partnerWatch) } }
                    }
                }
            }
        }
        item(key = "children-label") {
            SectionLabel("Children", Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(2 + v.rows.size)))
        }
        v.childrenEmpty?.let { empty ->
            item(key = "children-empty") { Text(empty, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem()) }
        }
        itemsIndexed(v.children, key = { _, c -> "child-${c.index}-${c.name}" }) { i, c ->
            Row(
                Modifier.animateItem().appear(rememberAppearance(3 + v.rows.size + i)).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .background(Meka.colors.surface).minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = c.spoken },
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(c.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { scope.launch { runCatching { core.removeAboutYouChild(c.index) }.getOrNull()?.let { react("children", it) } } }
                        .semantics { contentDescription = "Remove ${c.name}" }
                        // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                        .minTouch().padding(horizontal = MekaSpace.xs))
            }
        }
        item(key = "children-add") {
            val line = said["children"]
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(v.childHint, value = childText["add"] ?: "", onValue = { childText["add"] = it }, description = v.childDescription) { typed ->
                    scope.launch {
                        val r = runCatching { core.addAboutYouChild(typed) }.getOrNull() ?: return@launch
                        react("children", r)
                        if (r.refused) childText["add"] = typed // keep the words to fix
                    }
                }
                SaidLine(line) { back -> scope.launch { runCatching { core.addAboutYouChild(back) }.getOrNull()?.let { react("children", it) } } }
            }
        }
    }
}

/** One About you line: its label, a field holding what's there (Done saves; blank clears) and the line under it. */
@Composable
private fun AboutLine(row: AboutRow, said: AboutSaved?, onSave: (String) -> Unit, onUndo: (String) -> Unit) {
    var text by remember(row.value) { mutableStateOf(row.value ?: "") }
    Text(row.label, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (text.isEmpty()) Text(row.hint, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = when (row.field) {
                AboutField.PARTNER_EMAIL -> KeyboardType.Email
                AboutField.SCHOOL_WEBSITE -> KeyboardType.Uri
                AboutField.PARTNER_PHONE, AboutField.MOBILE, AboutField.ASSISTANT_NUMBER, AboutField.VOICEMAIL_NUMBER -> KeyboardType.Phone
                else -> KeyboardType.Text
            }),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = row.spokenHint },
        )
    }
    if (said != null) SaidLine(said, onUndo)
    else Text(os.meka.core.domain.LoneWordGlue.kept(row.line), style = MekaType.caption, color = Meka.colors.textTertiary)
}

/** What the last save said, lit in the accent when refused, with Undo after a clear or a removal. */
@Composable
private fun SaidLine(said: AboutSaved?, onUndo: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    val color by animateColorAsState(if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "about-said")
    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "about-line") { s ->
        if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            Text(s.line, style = MekaType.caption, color = color, modifier = Modifier.weight(1f, fill = false))
            s.undo?.let { back ->
                Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onUndo(back) }.minTouch())
            }
        }
    }
}

/** "Watch for requests": On/Off in a pill that blends to the accent, with what it means under it. */
@Composable
private fun WatchRow(on: Boolean, line: String, onToggle: () -> Unit) {
    val dot by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "about-watch")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onToggle() }
            .semantics { contentDescription = "Watch for requests"; selected = on }.minTouch().padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Watch for requests", style = MekaType.itemMeta, color = Meka.colors.textPrimary)
            Text(os.meka.core.domain.LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Text(if (on) "On" else "Off", style = MekaType.caption, color = Meka.colors.onAccent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(dot).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs))
    }
}
