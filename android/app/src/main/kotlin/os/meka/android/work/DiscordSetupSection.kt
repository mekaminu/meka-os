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
import androidx.compose.ui.platform.testTag
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.core.domain.DiscordChoiceRules
import os.meka.core.domain.DiscordRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Discord (Discord channel watch, slice 1): every channel Discord's notifications have named, each with
 * Follow / Stop (synced, so the Mac's list matches); how to set Discord up so the channel's posts reach the phone; and
 * the limits. Posts from channels he doesn't follow are dropped where they arrive.
 *
 * Walk-through week 5, A17: each channel's Follow pill, each alert chip with × and each "+ Sam" under Posting lately is
 * a card row saying what's chosen ("#signals · Example Traders" · "Following" ›, "Sam" · "Alerts at once" ›) opening a
 * sheet of the two choices, the one in use lit, each saying what it does ([DiscordChoiceRules]). Motion: the sheet rises
 * (reduced motion: a cross-fade), a choice gives a tick haptic and the status line cross-fades. Add a person and Add a
 * word stay links whose field unfolds only when asked for.
 */
@Composable
internal fun DiscordSetupSection(core: MekaCore, index: Int) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    val view by core.discordWatch.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<DiscordSheet?>(null) }

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
            }.minTouch())
        view.emptyLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        view.rows.forEach { row ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                NotifyChoiceRowView(DiscordChoiceRules.channelRow(row), Modifier.testTag(DISCORD_CHANNEL_TAG)) { sheet = DiscordSheet.Channel(row.key) }
                Crossfade(DiscordChoiceRules.channelLine(row), animationSpec = MekaMotion.appear(reduced), label = "discord-row-line") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        DiscordAlertsBlock(core, view, onSaid = { said = it }, onOpen = { sheet = it })
        Text("What Navilife can't see", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        DiscordRules.LIMITS.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
    // The open sheet reads the list as it is now, so a choice made on the Mac is the one lit.
    when (val s = sheet) {
        is DiscordSheet.Channel -> {
            val row = view.rows.firstOrNull { it.key == s.key }
            if (row != null) NotifyChoiceSheetView(DiscordChoiceRules.channelSheet(row),
                onChoose = { name ->
                    DiscordChoiceRules.followNamed(row, name)?.let { on ->
                        scope.launch { said = runCatching { core.setDiscordWatched(row.key, on) }.getOrNull() }
                    }
                },
                onClose = { sheet = null })
        }
        is DiscordSheet.Alert -> {
            val alerting = s.text in (if (s.person) view.people else view.words)
            NotifyChoiceSheetView(DiscordChoiceRules.alertSheet(s.text, s.person, alerting),
                onChoose = { name ->
                    DiscordChoiceRules.alertNamed(alerting, name)?.let { add ->
                        scope.launch { said = runCatching { core.setDiscordAlert(s.text, s.person, add) }.getOrNull() }
                    }
                },
                onClose = { sheet = null })
        }
        null -> {}
    }
}

/** Work mode → Discord's rows (walk-through week 5, A17), for the screen tests. */
const val DISCORD_CHANNEL_TAG = "discord-row:channel"
const val DISCORD_ALERT_TAG = "discord-row:alert"

/** Which of Discord's sheets is open. */
internal sealed interface DiscordSheet {
    data class Channel(val key: String) : DiscordSheet
    data class Alert(val text: String, val person: Boolean) : DiscordSheet
}

/**
 * Slice 2: "Alert me straight away" — the people and words whose posts alert at once (synced, so the Mac's list
 * matches): each a row opening Alerts at once · Waits on the card, the channel's recent posters as rows under Posting
 * lately (walk-through week 5, A17; once chips with × and "+ Sam"), and Add a person / Add a word, each
 * unfolding a field on the expand spring (reduced motion: cross-fade). Everything else waits on the channel's card.
 */
@Composable
private fun DiscordAlertsBlock(core: MekaCore, view: DiscordWatchView, onSaid: (String?) -> Unit, onOpen: (DiscordSheet) -> Unit) {
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
        view.people.forEach { p -> AlertRow(p, person = true, alerting = true, onOpen = onOpen) }
        view.words.forEach { w -> AlertRow(w, person = false, alerting = true, onOpen = onOpen) }
        if (view.suggestedPeople.isNotEmpty()) {
            Text("Posting lately", style = MekaType.caption, color = Meka.colors.textTertiary)
            view.suggestedPeople.forEach { p -> AlertRow(p, person = true, alerting = false, onOpen = onOpen) }
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
                    .minTouch())
            Text(if (adding == false) "Cancel" else "Add a word", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); adding = if (adding == false) null else false }
                    .minTouch())
        }
    }
}

/** One person or word: a card row ("Sam" · "Alerts at once" ›) opening Alerts at once · Waits on the card. */
@Composable
private fun AlertRow(text: String, person: Boolean, alerting: Boolean, onOpen: (DiscordSheet) -> Unit) {
    NotifyChoiceRowView(DiscordChoiceRules.alertRow(text, person, alerting), Modifier.testTag(DISCORD_ALERT_TAG)) {
        onOpen(DiscordSheet.Alert(text, person))
    }
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
