package os.meka.android.work

import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.RequestWatch
import os.meka.core.domain.RequestWatchRules

/**
 * Work mode → People → "Watch for requests from" (V1, requests slice 3): Family (always watched, listed for
 * clarity), anyone else Meka adds from contacts, the group chats he names, and the honest limits. The list stays on
 * this phone ([CaptureStore]); the listener reads these people's messages all day.
 */
@Composable
internal fun RequestWatchSection(
    lists: PeopleLists,
    watch: RequestWatch,
    listening: Boolean,
    index: Int,
    setWatch: (RequestWatch) -> Unit,
    notifiesNow: (String) -> Boolean = { false },
    setNotifyNow: (String, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    // The contact picker grants this one entry (no contacts permission); WhatsApp names senders as the contact is named.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.data?.let { u ->
            runCatching {
                context.contentResolver.query(u, arrayOf(Phone.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
        if (!name.isNullOrBlank()) setWatch(RequestWatchRules.addPerson(watch, lists, name))
    }
    var addingGroup by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(RequestWatchRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(
            RequestWatchRules.statusLine(lists, watch, listening),
            animationSpec = MekaMotion.appear(reduced), label = "watch-line",
        ) { line -> Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        Text(RequestWatchRules.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)

        val now: (String) -> NotifyNowState = { name ->
            NotifyNowState(notifiesNow(name)) { on -> haptics.tick(); setNotifyNow(name, on) }
        }
        lists.family.sortedBy { it.lowercase() }.forEach { name ->
            WatchRow(name, "Family", action = null, notify = now(name)) {}
        }
        watch.people.sortedBy { it.lowercase() }.forEach { name ->
            WatchRow(name, null, action = "Remove", notify = now(name)) { haptics.tick(); setWatch(RequestWatchRules.removePerson(watch, name)) }
        }
        Text(RequestWatchRules.NOTIFY_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text("Add from contacts", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                runCatching { pick.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
            }.padding(vertical = MekaSpace.xs))

        Crossfade(RequestWatchRules.groupsLine(watch), animationSpec = MekaMotion.appear(reduced), label = "watch-groups") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text(RequestWatchRules.GROUPS_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        watch.groups.sortedBy { it.lowercase() }.forEach { name ->
            WatchRow(name, "Group", action = "Remove") { haptics.tick(); setWatch(RequestWatchRules.removeGroup(watch, name)) }
        }
        AnimatedVisibility(
            addingGroup,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            GroupNameField { typed ->
                haptics.light()
                setWatch(RequestWatchRules.addGroup(watch, typed))
                addingGroup = false
            }
        }
        Text(if (addingGroup) "Cancel" else "Add a group", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); addingGroup = !addingGroup }.padding(vertical = MekaSpace.xs))

        Text(RequestWatchRules.LIMITS, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** "Notify straight away" beside a watched person: whether it's on and how to flip it. */
private class NotifyNowState(val on: Boolean, val set: (Boolean) -> Unit)

@Composable
private fun WatchRow(name: String, tag: String?, action: String?, notify: NotifyNowState? = null, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
            if (tag != null) Text(tag, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (notify != null) NotifyNowPill(name, notify)
        if (action != null) {
            Text(action, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onAction() }
                    .semantics { contentDescription = "$action $name" }.padding(MekaSpace.xs))
        }
    }
}

/**
 * "Straight away" (lit) or "In digest": a request from [name] notifies at once or waits for the next digest. The
 * pill's colour blends across with a tick haptic (motion catalogue: Request cards).
 */
@Composable
private fun NotifyNowPill(name: String, state: NotifyNowState) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (state.on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "now-bg")
    val fg by animateColorAsState(if (state.on) Meka.colors.onAccent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "now-fg")
    Text(
        RequestWatchRules.notifyLabel(state.on), style = MekaType.caption, color = fg,
        modifier = Modifier.padding(end = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.Switch) { state.set(!state.on) }
            .semantics {
                contentDescription = RequestWatchRules.notifySpoken(name, state.on)
                stateDescription = RequestWatchRules.notifyLabel(state.on)
            }
            .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun GroupNameField(onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
    ) {
        if (text.isEmpty()) Text("Group name, as in WhatsApp", style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(RequestWatchRules.MAX_NAME) },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Group name" },
        )
    }
}
