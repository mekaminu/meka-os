package os.meka.android.today

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import os.meka.android.goals.fold
import os.meka.android.goals.unfold
import os.meka.core.domain.CalendarRules
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarAccessAction
import os.meka.core.domain.CalendarAccessRules
import os.meka.core.domain.CalendarChoice
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.ConnectedAccount
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.MoreItem
import os.meka.android.designsystem.sharedTitleInPane

/**
 * Connected calendars. Connecting opens the provider's own sign-in page in the browser; MEKA OS never sees the
 * password, and the server keeps the resulting access encrypted. Each account is read-only until Meka taps Allow
 * editing (calendar editing), which asks the provider for the write permission; Stop editing gives it up at once. The list refreshes whenever the app
 * comes back to the foreground, so it updates as soon as the owner returns from the browser.
 */
@Composable
fun CalendarsPane(core: MekaCore, onClose: () -> Unit) {
    var accounts by remember { mutableStateOf<List<ConnectedAccount>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(reload) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { accounts = core.connectedAccounts() }
    }

    fun connect(provider: String, editing: Boolean = false) = scope.launch {
        message = null
        note = if (editing) CalendarAccessRules.allowNote(provider) else null
        when (val r = core.startConnect(provider, editing)) {
            is ConnectStart.OpenBrowser -> runCatching { uri.openUri(r.url) }.onFailure { message = "Couldn't open the browser." }
            ConnectStart.NotSetUp -> message = "${providerLabel(provider)} isn't set up on your server yet. Finish the registration steps, then try again."
            is ConnectStart.Failed -> message = r.reason
        }
    }

    val onToday by core.calendarsOnToday.collectAsState()
    val haptics = rememberMekaHaptics()

    fun stopEditing(a: ConnectedAccount) = scope.launch {
        haptics.tick()
        message = null
        val now = core.stopCalendarEditing(a.provider, a.email)
        if (now == null) message = "Couldn't reach your server. Editing is still on; try again."
        else { accounts = now; note = CalendarAccessRules.stoppedLine(a.provider) }
    }

    Column(Modifier.fillMaxSize().padding(MekaSpace.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Calendars", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.CALENDARS)))
        Crossfade(CalendarAccessRules.header(accounts.orEmpty().any { it.canEdit }), animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "calendars-header") {
            Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Spacer(Modifier.height(MekaSpace.m))

        when (val list = accounts) {
            null -> SkeletonRows(count = 2, rowHeight = 56.dp)
            else -> {
                if (list.isEmpty()) Text("No calendars connected yet.", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                list.forEachIndexed { i, a ->
                    AccountRow(
                        a, Modifier.appear(rememberAppearance(i)),
                        onReconnect = { connect(a.provider, editing = CalendarAccessRules.reconnectAsksEditing(a.canEdit)) },
                        onEditing = { if (a.canEdit) stopEditing(a) else { haptics.tick(); connect(a.provider, editing = true) } },
                    )
                }
            }
        }
        // On Today (all-day polish): a planning calendar can stay in the Calendar tab but off Today. Synced.
        if (onToday.isNotEmpty()) {
            Spacer(Modifier.height(MekaSpace.m))
            Text("ON TODAY", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
            Text("Turn a calendar off to keep it in the Calendar tab but off Today and your day.",
                style = MekaType.caption, color = Meka.colors.textTertiary)
            onToday.forEachIndexed { i, c ->
                CalendarSwitchRow(
                    c, Modifier.appear(rememberAppearance(i)),
                    // Rename (Fold review 2026-10-09 07:26, item 9): MEKA's name only; blank goes back to the default.
                    onRename = { typed -> TypingSaves.launch { runCatching { core.renameCalendar(c.key, typed) } } },
                ) {
                    haptics.tick()
                    scope.launch {
                        runCatching { if (c.onToday) core.hideCalendarFromToday(c.key, c.label) else core.showCalendarOnToday(c.key) }
                    }
                }
            }
        }
        // Weather place setting: the town the forecast is for (home unless Meka types another). Synced.
        Spacer(Modifier.height(MekaSpace.m))
        WeatherPlaceSection(core, Modifier.appear(rememberAppearance(onToday.size + 1)))
        Spacer(Modifier.height(MekaSpace.l))
        ConnectButton("Connect Google Calendar") { connect("google") }
        ConnectButton("Connect Outlook Calendar") { connect("microsoft") }
        note?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        message?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical) }
        Text("Refresh", style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.padding(top = MekaSpace.m).clickable(role = Role.Button) { reload++ })
    }
}

private val syncedFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AccountRow(a: ConnectedAccount, modifier: Modifier = Modifier, onReconnect: () -> Unit, onEditing: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m)) {
        Column(Modifier.weight(1f)) {
            // Titled like its main calendar ("Personal", "Hotmail", Meka's own name) or the feed's name; the address and
            // "Headlines" (never "news_more") on the line under it (Meka's screenshot 2026-10-09 09:01).
            Text(a.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            val status = a.statusLine(a.lastSyncAtMs?.let { syncedFmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) })
            Text(status, style = MekaType.caption, color = if (a.needsReconnect) Meka.colors.critical else Meka.colors.textTertiary)
            // Whether its events come through: "No events in the next 30 days" says an empty calendar, not a broken one.
            a.eventsLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            // Calendar editing: read-only or editing allowed; the line cross-fades as it changes.
            a.editingLine?.let { line ->
                Crossfade(line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "editing-line") {
                    Text(it, style = MekaType.caption, color = if (a.canEdit) Meka.colors.accent else Meka.colors.textTertiary)
                }
            }
        }
        if (a.needsReconnect) {
            Text("Reconnect", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onReconnect() }.padding(start = MekaSpace.m))
        }
        a.editingAction?.let { action ->
            Crossfade(action, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "editing-action") {
                Text(it.label, style = MekaType.itemMeta,
                    color = if (it == CalendarAccessAction.ALLOW_EDITING) Meka.colors.accent else Meka.colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { onEditing() }
                        .padding(start = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xxs))
            }
        }
    }
}

/**
 * One calendar with its On/Off pill; the pill's colour blends as it changes. "Rename" (a calendar with events, not the
 * fixtures) unfolds a field under the row in place; Done saves MEKA's own name (blank: back to the default, which the
 * field shows as its hint), the label cross-fades to it and the field folds away.
 */
@Composable
internal fun CalendarSwitchRow(c: CalendarChoice, modifier: Modifier = Modifier, onRename: (String) -> Unit = {}, onToggle: () -> Unit) {
    val pill by animateColorAsState(if (c.onToday) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "on-today")
    var renaming by rememberSaveable(c.key) { mutableStateOf(false) }
    val haptics = rememberMekaHaptics()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onToggle() }
                .semantics { contentDescription = "${c.label} on Today"; selected = c.onToday }
                .padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Crossfade(c.label, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "calendar-name") {
                    Text(it, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                }
                c.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
            if (c.canRename) {
                Text(if (renaming) "Cancel" else "Rename", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button) { haptics.tick(); renaming = !renaming }
                        .semantics { contentDescription = if (renaming) "Cancel renaming ${c.label}" else "Rename ${c.label}" }
                        .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs))
            }
            Text(if (c.onToday) "On" else "Off", style = MekaType.caption, color = Meka.colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(pill).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs))
        }
        AnimatedVisibility(renaming, enter = unfold(), exit = fold()) {
            CalendarNameField(c) { typed ->
                onRename(typed)
                haptics.light()
                renaming = false
            }
        }
    }
}

/** The rename field: the current name to edit, the default as its hint once cleared; Done saves. */
@Composable
private fun CalendarNameField(c: CalendarChoice, onDone: (String) -> Unit) {
    var text by rememberSaveable(c.key, c.label) { mutableStateOf(if (c.renamed) c.label else "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
        ) {
            if (text.isEmpty()) Text(c.defaultLabel, style = MekaType.body, color = Meka.colors.textTertiary)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(CalendarRules.MAX_NAME) },
                singleLine = true,
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Name for ${c.defaultLabel}" },
            )
        }
        Text("Only in MEKA: the calendar keeps its name in ${c.detail?.substringBefore(" · ") ?: "its app"}. Empty goes back to ${c.defaultLabel}.",
            style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun ConnectButton(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = Meka.colors.onAccent,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}
