package os.meka.android.ask

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.BatteryCare
import os.meka.android.work.MekaCallScreeningService
import os.meka.android.work.WorkAlerts
import os.meka.android.work.openListenerSettings
import os.meka.core.domain.SetupDevice
import os.meka.core.domain.SetupFix
import os.meka.core.domain.SetupRules
import os.meka.core.domain.SetupState
import os.meka.core.domain.SetupStep
import os.meka.core.facade.MekaCore

/**
 * What only the Fold can tell Setup (Setup checklist, Meka approved 2026-10-09): notifications allowed, the battery
 * exemption, notification access, the call screening role, and the family and request-watch lists (which stay on
 * the phone; only the checklist's words are made from them). Read fresh each time.
 */
object SetupDeviceFacts {
    private const val PREFS = "meka-setup"
    private const val HIDDEN_DAY = "hidden-day"

    fun facts(context: Context): SetupDevice {
        val app = context.applicationContext as? MekaApplication
        val captures = app?.let { runCatching { it.captures }.getOrNull() }
        return SetupDevice(
            mac = false,
            notificationsAllowed = WorkAlerts.canPost(context),
            batteryExempt = BatteryCare.facts(context).exempt,
            notificationAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            callRoleHeld = MekaCallScreeningService.roleHeld(context),
            people = captures?.lists?.value,
            watch = captures?.watch?.value,
        )
    }

    /** The day Meka said "Not today" to Today's setup card (local epoch day), if any. */
    fun hiddenDay(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(HIDDEN_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    fun hideToday(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(HIDDEN_DAY, today()).apply()
    }

    fun today(): Long = java.time.LocalDate.now().toEpochDay()
}

/** Does a step's button: the system's screens, or [openPane] for the steps done in a MEKA pane. Then [after]. */
@Composable
fun rememberSetupFixer(openPane: (SetupFix) -> Unit, after: () -> Unit): (SetupStep) -> Unit {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { after() }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { after() }
    return remember(openPane) {
        { step: SetupStep ->
            haptics.light()
            when (val f = step.fix) {
                SetupFix.CALENDARS, SetupFix.WORK, SetupFix.VOICE -> f?.let(openPane)
                SetupFix.NOTIFICATION_ACCESS -> openListenerSettings(context)
                SetupFix.CALL_ROLE -> MekaCallScreeningService.roleRequest(context)?.let { runCatching { askRole.launch(it) } }
                SetupFix.BATTERY -> if (BatteryCare.facts(context).exempt) BatteryCare.openSamsung(context) else BatteryCare.allowInAndroid(context)
                SetupFix.NOTIFICATIONS -> {
                    val asked = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (asked) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
                null -> Unit
            }
        }
    }
}

/**
 * Ask → More → Setup (Setup checklist, Meka approved 2026-10-09): every capability MEKA has, section by section, with
 * a tick or the next step and a button where it can be done from here. Checked on open and each time MEKA comes back
 * (after allowing something in Settings); a shimmer until the first answer.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary cross-fades and blends to the
 * accent while something is left; sections and their steps stagger in 40 ms apart; each dot's colour blends and its
 * line cross-fades as a step is done; a button presses in with a light haptic and hands over to the system's screen
 * or MEKA's pane. Reduced motion: cross-fades.
 */
@Composable
fun SetupPane(core: MekaCore, onClose: () -> Unit, openPane: (SetupFix) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view by core.setupView.collectAsState()
    var checking by rememberSaveable { mutableStateOf(false) }
    fun check() = scope.launch {
        checking = true
        try { core.refreshSetup(SetupDeviceFacts.facts(context)) } finally { checking = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { check() }
    val fix = rememberSetupFixer(openPane) { check() }
    val reduced = Meka.reducedMotion
    val v = view
    val summaryColor by animateColorAsState(
        if (v != null && v.toDo > 0) Meka.colors.accent else Meka.colors.textSecondary,
        MekaMotion.appear(reduced), label = "setup-summary",
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Setup", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.SETUP)).appear(rememberAppearance(0)))
        Crossfade(v?.summary ?: (if (checking) "Checking…" else ""), animationSpec = MekaMotion.appear(reduced), label = "setup-summary-text") { s ->
            Text(s, style = MekaType.body, color = summaryColor, modifier = Modifier.appear(rememberAppearance(0)))
        }
        if (v != null) {
            var step = 1
            v.sections.forEach { section ->
                Text(section.title.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.s).semantics { heading() }.appear(rememberAppearance(step++)))
                section.steps.forEach { s ->
                    SetupStepView(s, Modifier.appear(rememberAppearance(step++))) { fix(s) }
                }
            }
        } else {
            SkeletonRows(8, Modifier.padding(top = MekaSpace.s))
        }
    }
}

@Composable
private fun setupColor(state: SetupState): Color = when (state) {
    SetupState.DONE -> Meka.colors.success
    SetupState.TODO -> Meka.colors.accent
    SetupState.ELSEWHERE, SetupState.LATER, SetupState.UNKNOWN -> Meka.colors.textTertiary
}

private fun spoken(state: SetupState) = when (state) {
    SetupState.DONE -> "done"
    SetupState.TODO -> "to do"
    SetupState.ELSEWHERE -> "on the other device"
    SetupState.LATER -> "comes later"
    SetupState.UNKNOWN -> "couldn't check"
}

@Composable
private fun SetupStepView(step: SetupStep, modifier: Modifier, onFix: () -> Unit) {
    val reduced = Meka.reducedMotion
    val dot by animateColorAsState(setupColor(step.state), MekaMotion.appear(reduced), label = "setup-dot")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s)
            .semantics(mergeDescendants = true) { contentDescription = "${step.title}, ${spoken(step.state)}. ${step.line}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(step.line, animationSpec = MekaMotion.appear(reduced), label = "setup-line") { l ->
                Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        val label = step.fixLabel
        if (step.toDo && step.fix != null && label != null) {
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
 * Today's setup card (Setup checklist): while something is left, "SET UP MEKA · 3 steps left · Outlook calendar,
 * Calendar editing, Pick a voice" with Open Setup (light haptic; slides to Ask with the Setup pane springing up) and
 * Not today (tick haptic; folds away until tomorrow). Checked on open, the server asked at most every quarter hour.
 * Motion: rises with Today's stagger; the line cross-fades; folds away on the expand spring. Reduced motion: fades.
 */
@Composable
fun SetupTodayCard(core: MekaCore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val view by core.setupView.collectAsState()
    var hidden by remember { mutableLongStateOf(SetupDeviceFacts.hiddenDay(context) ?: Long.MIN_VALUE) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { runCatching { core.refreshSetup(SetupDeviceFacts.facts(context), force = false) } }
    }
    val reduced = Meka.reducedMotion
    val shown = SetupRules.cardShown(view, hidden.takeIf { it != Long.MIN_VALUE }, SetupDeviceFacts.today())
    AnimatedVisibility(
        shown, modifier.fillMaxWidth(),
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text("SET UP MEKA", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
            Crossfade(view?.todayLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "setup-card") { t ->
                Text(t, style = MekaType.body, color = Meka.colors.textPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                Text("Open Setup", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.light()
                        (context.applicationContext as? MekaApplication)?.let { app ->
                            app.openSetup.value = true
                            app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
                        }
                    }.padding(vertical = MekaSpace.xs))
                Text("Not today", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.tick()
                        SetupDeviceFacts.hideToday(context)
                        hidden = SetupDeviceFacts.today()
                    }.padding(vertical = MekaSpace.xs))
            }
        }
    }
}
