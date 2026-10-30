package os.meka.android.ask

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.BatteryCare
import os.meka.android.update.UpdateState
import os.meka.android.work.MekaCallScreeningService
import os.meka.android.work.openListenerSettings
import os.meka.core.domain.DeviceHealth
import os.meka.core.domain.HealthFix
import os.meka.core.domain.HealthRow
import os.meka.core.domain.HealthState
import os.meka.core.domain.HealthView
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.MekaCore

/**
 * What only the Fold can tell the Health screen (Reliability first, item 3): the battery exemption (and a stop during
 * the day), notification access and when the listener last read something, the call screening role, this build and
 * whether a newer one is ready. Read fresh each time; nothing leaves the phone except as part of the screen.
 */
object HealthDevice {
    private const val PREFS = "meka-health"
    private const val LAST_NOTIFICATION = "last-notification"
    @Volatile private var lastNoted = 0L

    /** The notification listener read something (at most one write a minute). */
    fun noteNotification(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNoted < 60_000L) return
        lastNoted = now
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_NOTIFICATION, now).apply() }
    }

    fun facts(context: Context): DeviceHealth {
        val app = context.applicationContext as? MekaApplication
        val watching = app?.let { runCatching { it.identity.deviceSecret() != null }.getOrDefault(false) } ?: false
        val battery = BatteryCare.view(context, watching)
        val updater = app?.updater
        return DeviceHealth(
            mac = false,
            batteryExempt = BatteryCare.facts(context).exempt,
            batteryStopped = battery.critical,
            notificationAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            lastNotificationMs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_NOTIFICATION, 0L).takeIf { it > 0 },
            callRoleHeld = MekaCallScreeningService.roleHeld(context),
            version = updater?.let { runCatching { it.installedName }.getOrNull() },
            updateReady = updater?.state?.value is UpdateState.Ready || updater?.newest?.value != null,
            updateHidden = updater?.newestHidden == true,
            installedCode = updater?.let { runCatching { it.installedCode }.getOrNull() },
            updateFailure = updater?.lastFailure?.value,
            sizeBytes = updater?.let { runCatching { it.installedSizeBytes }.getOrNull() },
            previousSizeBytes = updater?.previousSizeBytes,
            notifications = os.meka.android.notify.NotifyTraceStore.health(context),
            callerLookups = os.meka.android.work.CallerLookup.allowed(context),
            face = runCatching { ObiFaceStore.health(context) }.getOrNull(),
            stageClosed = StageCloseLog.last(context),
            voiceInUse = app?.let { a -> runCatching { a.core.speechVoiceInUse() }.getOrNull() },
        )
    }
}

/** Does a row's fix: the system screens, Reconnect, Sync now, Install. [openPane] handles the fixes that open a MEKA pane. */
@Composable
fun rememberHealthFixer(core: MekaCore, openPane: ((HealthFix) -> Unit)? = null, after: () -> Unit = {}): (HealthRow) -> Unit {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { after() }
    // Notifications never seen: Turn on asks for the permission once; after a "don't ask again" it opens MEKA's page.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openMekaNotificationSettings(context)
        after()
    }
    // Spam calls still ring, slice 5: Allow asks for contacts and the call log; once Android won't ask again
    // ("don't allow" twice), it opens Navilife's app page, where Permissions has both.
    val askLookups = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!os.meka.android.work.CallerLookup.allowed(context)) os.meka.android.work.CallerLookup.openAppSettingsIfRefused(context)
        after()
    }
    return remember(core, openPane) {
        { row: HealthRow ->
            haptics.light()
            when (val f = row.fix) {
                HealthFix.SYNC_NOW -> { scope.launch { core.syncNow(); after() } }
                HealthFix.RECONNECT -> {
                    val p = row.provider
                    if (p != null) scope.launch {
                        (core.startConnect(p, row.editing) as? ConnectStart.OpenBrowser)?.let { runCatching { uri.openUri(it.url) } }
                    }
                }
                HealthFix.BATTERY -> if (BatteryCare.facts(context).exempt) BatteryCare.openSamsung(context) else BatteryCare.allowInAndroid(context)
                HealthFix.NOTIFICATION_ACCESS -> openListenerSettings(context)
                HealthFix.CALL_ROLE -> {
                    val intent = MekaCallScreeningService.roleRequest(context)
                    if (intent != null) runCatching { askRole.launch(intent) }
                }
                HealthFix.INSTALL_UPDATE -> {
                    val app = context.applicationContext as? MekaApplication
                    // Ready, Try again after a failure, or the newest build even when Later hid it on Today: installs in
                    // the app's scope (leaving Health doesn't stop it).
                    app?.updater?.installFromHealth()
                }
                HealthFix.NOTIFICATIONS_ON -> {
                    val needsPermission = android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (needsPermission) runCatching { askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                    else openMekaNotificationSettings(context)
                }
                HealthFix.CALLER_LOOKUPS -> runCatching { askLookups.launch(os.meka.android.work.CallerLookup.PERMISSIONS) }
                // Obi's face: look at the server's list now, then check again so the face line says what it found.
                HealthFix.FACE_LOOK_AGAIN -> scope.launch { ObiFaceStore.refresh(context, core, force = true); after() }
                HealthFix.CALENDARS, HealthFix.WORK -> f?.let { openPane?.invoke(it) }
                null -> Unit
            }
        }
    }
}

/** Android's notification page for MEKA (its switch and every category). */
fun openMekaNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A row's fix shows only where it can be done here (the panes open from Ask only). */
fun HealthRow.fixHere(panes: Boolean): Boolean = fix != null && (panes || (fix != HealthFix.CALENDARS && fix != HealthFix.WORK))

/**
 * Ask → More → Health (Reliability first, item 3): every capability MEKA depends on with a tick or the next step.
 * Checked on open and each time MEKA comes back (after fixing something in Settings); a shimmer until the first answer.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary cross-fades and blends between
 * the secondary, accent and critical colours; rows stagger in 40 ms apart, each status dot's colour blends as it
 * changes; a fix presses in with a light haptic and hands over to the system's screen. Reduced motion: cross-fades.
 */
@Composable
fun HealthPane(core: MekaCore, onClose: () -> Unit, openPane: (HealthFix) -> Unit, openPlace: (os.meka.core.domain.HealthPlace) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view by core.healthView.collectAsState()
    var checking by rememberSaveable { mutableStateOf(false) }
    fun check() = scope.launch {
        checking = true
        try {
            // Updates: the newest build is known before the row is read (a build hidden with Later still shows Install).
            runCatching { (context.applicationContext as? MekaApplication)?.updater?.check() }
            core.refreshHealth(HealthDevice.facts(context))
        } finally { checking = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { check() }
    val fix = rememberHealthFixer(core, openPane) { check() }
    val reduced = Meka.reducedMotion
    val v = view
    val summaryColor by animateColorAsState(
        when {
            v == null -> Meka.colors.textSecondary
            v.critical -> Meka.colors.critical
            v.attention > 0 -> Meka.colors.accent
            else -> Meka.colors.textSecondary
        },
        MekaMotion.appear(reduced), label = "health-summary",
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Health", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.HEALTH)).appear(rememberAppearance(0)))
        Crossfade(v?.summary ?: "Checking…", animationSpec = MekaMotion.appear(reduced), label = "health-summary-text") { s ->
            Text(os.meka.core.domain.LoneWordGlue.kept(s), style = MekaType.body, color = summaryColor, modifier = Modifier.appear(rememberAppearance(0)))
        }
        if (v != null) {
            // The core's zone, like every other time on screen (the quality gate's Health shots).
            val line = os.meka.core.domain.HealthRules.checkedShown(v, checking)
            Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "health-checked") { t ->
                Text(t, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
            val haptics = rememberMekaHaptics()
            // Meka 2026-10-24 13:12: a tap on a row opens its screen; a row with nowhere to go isn't tappable.
            val go: (os.meka.core.domain.HealthPlace) -> Unit = { p ->
                haptics.light()
                when (p) {
                    os.meka.core.domain.HealthPlace.SYSTEM_BATTERY ->
                        if (BatteryCare.facts(context).exempt) BatteryCare.openSamsung(context) else BatteryCare.allowInAndroid(context)
                    os.meka.core.domain.HealthPlace.SYSTEM_LISTENER -> openListenerSettings(context)
                    else -> openPlace(p)
                }
            }
            v.rows.forEachIndexed { i, row ->
                HealthRowView(row, row.fixHere(panes = true), Modifier.appear(rememberAppearance(i + 1)), row.place?.let { p -> { go(p) } }) { fix(row) }
            }
        } else {
            SkeletonRows(6, Modifier.padding(top = MekaSpace.xs))
        }
        // Notifications never seen (slice 1): each category as Android has it, then what MEKA's notification paths did.
        Text("Notifications", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        NotificationTraceLines(v?.checkedAtMs)
        // Bedside clock slice 1: what the hinge reports right now and its last 20 changes.
        Text("Fold", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        os.meka.android.fold.FoldPostureLines(withLog = true)
    }
}

/** The categories (on/off) and the last [shown] trace lines, newest first; read again on each check. */
@Composable
private fun NotificationTraceLines(checkedAtMs: Long?, shown: Int = 12) {
    val context = LocalContext.current
    val facts = remember(checkedAtMs) { os.meka.android.notify.NotifyTraceStore.health(context) }
    val cal = remember(checkedAtMs) { os.meka.android.notify.NotifyTraceStore.calendar() }
    Text(
        (if (facts.allowed) "Allowed" else "Off for Navilife") + if (facts.channels.isEmpty()) " · no categories yet" else "",
        style = MekaType.caption, color = Meka.colors.textSecondary,
    )
    // Slice 3: each category on its own line with when MEKA last posted on it, the newest first.
    val now = remember(checkedAtMs) { System.currentTimeMillis() }
    facts.channels.sortedWith(compareByDescending<os.meka.core.domain.NotifyChannelState> { it.lastPostMs ?: 0L }.thenBy { it.name })
        .zip(os.meka.core.domain.NotifyChannelPosts.lines(facts.channels, now, cal)).forEach { (c, line) ->
            Text(line, style = MekaType.caption, color = if (c.on) Meka.colors.textSecondary else Meka.colors.critical)
        }
    if (facts.trace.isEmpty()) {
        Text("Nothing recorded yet", style = MekaType.caption, color = Meka.colors.textTertiary)
    }
    facts.trace.take(shown).forEach { e ->
        Text(
            os.meka.core.domain.NotifyTrace.line(e, cal), style = MekaType.caption,
            color = if (e.kind == os.meka.core.domain.NotifyTraceKind.FAILED || e.kind == os.meka.core.domain.NotifyTraceKind.BLOCKED) Meka.colors.critical else Meka.colors.textTertiary,
        )
    }
}

@Composable
fun healthColor(state: HealthState): Color = when (state) {
    HealthState.OK -> Meka.colors.success
    HealthState.WARN -> Meka.colors.accent
    HealthState.BAD -> Meka.colors.critical
    HealthState.UNKNOWN -> Meka.colors.textTertiary
    // Off by his choice (the walk-through's A7): neither the working green nor a fault's colour.
    HealthState.OFF -> Meka.colors.textTertiary
}

private fun spokenState(state: HealthState) = when (state) {
    HealthState.OK -> "working"
    HealthState.WARN -> "needs a look"
    HealthState.BAD -> "not working"
    HealthState.UNKNOWN -> "couldn't check"
    HealthState.OFF -> "off"
}

@Composable
private fun HealthRowView(row: HealthRow, showFix: Boolean, modifier: Modifier, onOpen: (() -> Unit)?, onFix: () -> Unit) {
    val reduced = Meka.reducedMotion
    val dot by animateColorAsState(healthColor(row.state), MekaMotion.appear(reduced), label = "health-dot")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "${row.title}, ${spokenState(row.state)}. ${row.line}" + (row.detail?.let { " $it" } ?: "") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "health-line") { l ->
                Text(os.meka.core.domain.LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            val detail = row.detail
            if (detail != null) {
                Crossfade(detail, animationSpec = MekaMotion.appear(reduced), label = "health-detail") { d ->
                    Text(os.meka.core.domain.LoneWordGlue.kept(d), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        if (onOpen != null && !(showFix && row.fixLabel != null)) {
            Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
        }
        val label = row.fixLabel
        if (showFix && label != null) {
            Box(
                Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, onClick = onFix)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                Text(label, style = MekaType.itemMeta, color = Meka.colors.accent)
            }
        }
    }
}

/**
 * Today's health line (Reliability first, item 3): "Messages capture · MEKA can't see notifications…" or "MEKA health ·
 * 2 things need a look", under the sign-in line; the critical colour when something is broken, else the accent.
 * Tapping it unfolds what needs a look in place (expand spring, tick haptic) with each fix that can be done from here
 * and "Open Health", which goes to Ask → More → Health. Checked on open at most every quarter hour.
 */
@Composable
fun HealthTodayLine(core: MekaCore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val view by core.healthView.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { runCatching { core.refreshHealth(HealthDevice.facts(context), force = false) } }
    }
    val fix = rememberHealthFixer(core) { scope.launch { runCatching { core.refreshHealth(HealthDevice.facts(context)) } } }
    val v: HealthView = view ?: return
    val text = v.todayLine ?: return
    val reduced = Meka.reducedMotion
    var open by rememberSaveable { mutableStateOf(false) }
    val color by animateColorAsState(if (v.critical) Meka.colors.critical else Meka.colors.accent, MekaMotion.appear(reduced), label = "health-today")
    Column(modifier.fillMaxWidth()) {
        Crossfade(text, Modifier.fillMaxWidth(), MekaMotion.appear(reduced), label = "health-today-text") { t ->
            Text(
                t, style = MekaType.caption, color = color,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); open = !open }.padding(vertical = MekaSpace.xs),
            )
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                v.rows.filter { it.needsLook && it.key != "battery" && !it.key.startsWith("calendar:") }.forEach { row ->
                    Text("${row.title} · ${row.line}", style = MekaType.caption, color = Meka.colors.textSecondary)
                    val label = row.fixLabel
                    if (row.fixHere(panes = false) && label != null) {
                        Text(label, style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) { fix(row) }.padding(vertical = MekaSpace.xxs))
                    }
                }
                Text("Open Health", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.light()
                        (context.applicationContext as? MekaApplication)?.let { app ->
                            app.openHealth.value = true
                            app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
                        }
                    }.padding(vertical = MekaSpace.xxs))
            }
        }
    }
}
