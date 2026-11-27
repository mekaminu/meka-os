package os.meka.android.work

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.goals.GoalAddRowView
import os.meka.android.goals.GoalAddSheetFrame
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.BlockedCallerRules
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.SpamProtectionRules
import os.meka.core.domain.SuspectedSpamRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Call assistant → Spam protection (call assistant polish 8b). The block list (synced: the Mac shows and
 * edits the same list), "+ Block a number" opening a sheet with the number (walk-through week 5, A17: no open box on
 * the page; the sheet stays open while the number is checked, a refusal said in it in the critical colour), and "Recognise callers" (contacts and the call log, read on the
 * phone only so contacts and people Meka called are never stopped). Suspected spam (8b c) lists numbers MEKA's AI
 * flagged from their message, with Block (light haptic) and Not spam (tick). Motion: the summary line cross-fades; rows unfold
 * and fold away with the expand spring; Block gives a light haptic, Unblock a tick. Reduced motion: cross-fades.
 */
@Composable
fun SpamProtectionSection(core: MekaCore, index: Int) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val scope = rememberCoroutineScope()
    val blocked by core.blockedCallers.collectAsState()
    val work by core.workMode.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var recognising by remember { mutableStateOf(CallerLookup.allowed(context)) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { recognising = CallerLookup.allowed(context) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recognising = CallerLookup.allowed(context)
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
    val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

    fun block(number: String) {
        if (!BlockedCallerRules.canAdd(number, busy)) return
        busy = true
        scope.launch {
            try {
                if (runCatching { core.blockCaller(number, null) }.getOrDefault(false)) {
                    haptics.light(); refused = false; adding = false
                } else { haptics.tick(); refused = true }
            } finally { busy = false }
        }
    }
    if (adding) BlockAddSheetView(BlockedCallerRules.sheetProblem(refused), busy, onTyped = { refused = false }, onAdd = ::block,
        onClose = { adding = false; refused = false })

    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("SPAM PROTECTION", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(blocked.line, animationSpec = MekaMotion.appear(reduced), label = "blocked-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text(SpamProtectionRules.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text(os.meka.core.domain.NumberLookupRules.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        // Scam call fix, slice 2: the warning over the call screen when a stranger rings through.
        Text(os.meka.core.domain.InCallWarningRules.SETTINGS_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text(SpamProtectionRules.SYSTEM_LIST_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
        // Suspected spam (8b c): numbers MEKA's AI flagged from their message, waiting for Block or Not spam.
        AnimatedVisibility(blocked.suspects.isNotEmpty(), enter = enter, exit = exit) {
            Text(SuspectedSpamRules.TITLE, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
        }
        blocked.suspects.forEach { row ->
            key("suspect:" + row.key) {
                var shown by remember { mutableStateOf(true) }
                AnimatedVisibility(shown, enter = enter, exit = exit) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.number, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                            Text(row.line, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                        Text(SuspectedSpamRules.NOT_SPAM_LABEL, style = MekaType.caption, color = Meka.colors.textSecondary,
                            modifier = Modifier.clickable(role = Role.Button) {
                                haptics.tick(); shown = false
                                scope.launch { core.dismissSuspectedCaller(row.key) }
                            }.minTouch().padding(horizontal = MekaSpace.xs))
                        Text(BlockedCallerRules.BLOCK_LABEL, style = MekaType.caption, color = Meka.colors.critical,
                            modifier = Modifier.clickable(role = Role.Button) {
                                haptics.light(); shown = false
                                scope.launch { core.confirmSuspectedCaller(row.key) }
                            }.minTouch().padding(horizontal = MekaSpace.xs))
                    }
                }
            }
        }
        AnimatedVisibility(blocked.suspects.isNotEmpty(), enter = enter, exit = exit) {
            Text(SuspectedSpamRules.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        blocked.rows.forEach { row ->
            key(row.key) {
                var shown by remember { mutableStateOf(true) }
                AnimatedVisibility(shown, enter = enter, exit = exit) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.number, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                            Text(row.line, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                        Text(BlockedCallerRules.REPORT_LABEL, style = MekaType.caption, color = Meka.colors.textSecondary,
                            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); reportScamCall(context, row.number) }.minTouch().padding(horizontal = MekaSpace.xs))
                        Text("Unblock", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) {
                                haptics.tick(); shown = false
                                scope.launch { core.unblockCaller(row.key) }
                            }.minTouch().padding(horizontal = MekaSpace.xs))
                    }
                }
            }
        }
        // Walk-through week 5, A17: "+ Block a number" is a card row opening the number's sheet, not an open box on the page.
        GoalAddRowView(BlockedCallerRules.addRow, Modifier.testTag(BLOCK_ADD_ROW_TAG)) { refused = false; adding = true }
        Text(CallScreeningRules.ONE_SCREENER_TITLE, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
            modifier = Modifier.padding(top = MekaSpace.xs))
        CallScreeningRules.ONE_SCREENER_LINES.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text(CallScreeningRules.OPEN_DEFAULT_APPS, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                haptics.tick()
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.minTouch())
        // Spam calls still ring, slice 1: spam protection is always on; only Advanced turns it off, after a question.
        var advanced by rememberSaveable { mutableStateOf(!work.spamProtection) }
        var confirming by remember { mutableStateOf(false) }
        Text(SpamProtectionRules.ADVANCED + if (advanced) " ▴" else " ▾", style = MekaType.caption, color = Meka.colors.textSecondary,
            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); advanced = !advanced; confirming = false }.minTouch())
        AnimatedVisibility(advanced, enter = enter, exit = exit) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(work.spamProtection, animationSpec = MekaMotion.appear(reduced), label = "spam-protection") { on ->
                    Text(
                        SpamProtectionRules.SWITCH_LABEL + " · " + if (on) SpamProtectionRules.ON_LINE else SpamProtectionRules.OFF_WARNING,
                        style = MekaType.caption, color = if (on) Meka.colors.textSecondary else Meka.colors.critical,
                    )
                }
                Crossfade(confirming to work.spamProtection, animationSpec = MekaMotion.appear(reduced), label = "spam-confirm") { (asking, on) ->
                    when {
                        !on -> Text("Turn spam protection on", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) {
                                haptics.light(); scope.launch { core.setSpamProtection(true) }
                            }.minTouch())
                        asking -> Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                            Text(SpamProtectionRules.OFF_CONFIRM, style = MekaType.caption, color = Meka.colors.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                                Text("Keep it on", style = MekaType.caption, color = Meka.colors.accent,
                                    modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); confirming = false }.minTouch())
                                Text("Turn off", style = MekaType.caption, color = Meka.colors.critical,
                                    modifier = Modifier.clickable(role = Role.Button) {
                                        haptics.tick(); confirming = false
                                        scope.launch { core.setSpamProtection(false) }
                                    }.minTouch())
                            }
                        }
                        else -> Text("Turn spam protection off…", style = MekaType.caption, color = Meka.colors.textTertiary,
                            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); confirming = true }.minTouch())
                    }
                }
            }
        }
        AnimatedVisibility(!recognising, enter = enter, exit = exit) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text("Recognise callers · let Navilife check your contacts and recent calls, name who left a message, and say how an unknown call ended", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.tick(); runCatching { ask.launch(CallerLookup.PERMISSIONS) }
                    }.minTouch())
                Text(
                    "Read on this phone only, never sent. Without it, only family and always-notify are always let through.",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
            }
        }
    }
}

/** The tag the screen tests find Spam protection's "+ Block a number" row by. */
const val BLOCK_ADD_ROW_TAG = "block-add-row"

/**
 * Spam protection's block sheet (walk-through week 5, A17): the number with an example, what blocking does, Close ·
 * Block (the phone keypad). Block stays dark until something is typed and while it's checked; the sheet stays open
 * until the number is on the list, one it can't take said under it in the critical colour with the number still there.
 */
@Composable
internal fun BlockAddSheetView(problem: String?, busy: Boolean, onTyped: () -> Unit, onAdd: (String) -> Unit, onClose: () -> Unit) {
    val reduced = Meka.reducedMotion
    var typed by rememberSaveable { mutableStateOf("") }
    GoalAddSheetFrame(
        BlockedCallerRules.addSheet, typed, { typed = it; onTyped() },
        add = { onAdd(typed) }, onClose = onClose,
        canAdd = BlockedCallerRules.canAdd(typed, busy),
        maxLength = BlockedCallerRules.MAX_TYPED,
        closeOnAdd = false,
        keyboard = KeyboardType.Phone,
    ) {
        val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
        val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))
        AnimatedVisibility(problem != null, enter = enter, exit = exit) {
            Text(LoneWordGlue.kept(problem.orEmpty()), style = MekaType.caption, color = Meka.colors.critical)
        }
    }
}
