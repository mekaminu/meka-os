package os.meka.android.work

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.DiscordWatchView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.DiscordRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Discord (Discord channel watch, slice 1): every channel Discord's notifications have named, each with
 * Follow / Stop (synced, so the Mac's list matches); how to set Discord up so the channel's posts reach the phone; and
 * the limits. Posts from channels he doesn't follow are dropped where they arrive.
 */
@Composable
internal fun DiscordSetupSection(core: MekaCore, index: Int) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    val view by core.discordWatch.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(DiscordRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(said ?: view.statusLine, animationSpec = MekaMotion.appear(reduced), label = "discord-line") { line ->
            Text(line, style = MekaType.itemMeta, color = if (view.rows.any { it.watched }) Meka.colors.accent else Meka.colors.textSecondary)
        }
        Text(DiscordRules.SETUP_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text("Open Discord's notification settings", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                haptics.tick()
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, DiscordRules.PACKAGES.first())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.padding(vertical = MekaSpace.xs))
        view.emptyLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        view.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(row.line, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                SettingChip(if (row.watched) "Following" else "Follow", row.watched,
                    spoken = if (row.watched) "Stop following ${row.label}" else "Follow ${row.label}") {
                    haptics.tick()
                    scope.launch { said = runCatching { core.setDiscordWatched(row.key, !row.watched) }.getOrNull() }
                }
            }
        }
        DiscordAlertsBlock(core, view, onSaid = { said = it })
        Text("What Navilife can't see", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        DiscordRules.LIMITS.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
}

/**
 * Slice 2: "Alert me straight away" — the people and words whose posts alert at once (synced, so the Mac's list
 * matches): each a chip with ×, the channel's recent posters offered as "+ Sam", and Add a person / Add a word, each
 * unfolding a field on the expand spring (reduced motion: cross-fade). Everything else waits on the channel's card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscordAlertsBlock(core: MekaCore, view: DiscordWatchView, onSaid: (String?) -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    var adding by rememberSaveable { mutableStateOf<Boolean?>(null) } // true = a person, false = a word
    fun set(text: String, person: Boolean, add: Boolean) {
        haptics.tick()
        scope.launch { onSaid(runCatching { core.setDiscordAlert(text, person, add) }.getOrNull()) }
    }
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("Alert me straight away", style = MekaType.itemMeta, color = Meka.colors.textPrimary)
        Crossfade(view.alertLine, animationSpec = MekaMotion.appear(reduced), label = "discord-alerts") { line ->
            Text(line, style = MekaType.itemMeta, color = if (line == DiscordRules.NO_ALERTS) Meka.colors.textSecondary else Meka.colors.accent)
        }
        Text(DiscordRules.ALERTS_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        if (view.people.isNotEmpty() || view.words.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                view.people.forEach { p -> AlertChip(p, "Stop alerting for posts from $p") { set(p, person = true, add = false) } }
                view.words.forEach { w -> AlertChip("“$w”", "Stop alerting for posts saying $w") { set(w, person = false, add = false) } }
            }
        }
        if (view.suggestedPeople.isNotEmpty()) {
            Text("Posting lately", style = MekaType.caption, color = Meka.colors.textTertiary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                view.suggestedPeople.forEach { p ->
                    SettingChip("+ $p", false, spoken = "Alert for posts from $p") { set(p, person = true, add = true) }
                }
            }
        }
        AnimatedVisibility(
            adding != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            val person = adding ?: true
            androidx.compose.runtime.key(person) {
                AlertField(if (person) "Name as Discord shows it" else "A word or ticker, e.g. stop") { typed ->
                    set(typed, person, add = true)
                    adding = null
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
            Text(if (adding == true) "Cancel" else "Add a person", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); adding = if (adding == true) null else true }
                    .padding(vertical = MekaSpace.xs))
            Text(if (adding == false) "Cancel" else "Add a word", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); adding = if (adding == false) null else false }
                    .padding(vertical = MekaSpace.xs))
        }
    }
}

@Composable
private fun AlertChip(label: String, spoken: String, onRemove: () -> Unit) {
    Text(
        "$label  ×", style = MekaType.caption, color = Meka.colors.onAccent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button) { onRemove() }
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun AlertField(hint: String, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
    ) {
        if (text.isEmpty()) Text(hint, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(DiscordRules.MAX_ENTRY) },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = hint },
        )
    }
}
