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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BlockedCallerRules
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.SuspectedSpamRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Call assistant → Spam protection (call assistant polish 8b). The block list (synced: the Mac shows and
 * edits the same list), a field to block a number, and "Recognise callers" (contacts and the call log, read on the
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var recognising by remember { mutableStateOf(CallerLookup.allowed(context)) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { recognising = CallerLookup.allowed(context) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recognising = CallerLookup.allowed(context)
    }
    var typed by rememberSaveable { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
    val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

    fun block() {
        val number = typed
        scope.launch {
            if (core.blockCaller(number, null)) { haptics.light(); typed = ""; refused = false } else { haptics.tick(); refused = true }
        }
    }

    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("SPAM PROTECTION", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(blocked.line, animationSpec = MekaMotion.appear(reduced), label = "blocked-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text(
            "Blocked numbers never ring, any time of day. With the call assistant on, numbers your network can't verify " +
                "(often spoofed) and withheld numbers in quiet hours go to the assistant instead. Family, your " +
                "always-notify list, contacts and anyone you called in the last 90 days always get through.",
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )
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
                            }.padding(MekaSpace.xs))
                        Text(BlockedCallerRules.BLOCK_LABEL, style = MekaType.caption, color = Meka.colors.critical,
                            modifier = Modifier.clickable(role = Role.Button) {
                                haptics.light(); shown = false
                                scope.launch { core.confirmSuspectedCaller(row.key) }
                            }.padding(MekaSpace.xs))
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
                            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); reportScamCall(context, row.number) }.padding(MekaSpace.xs))
                        Text("Unblock", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) {
                                haptics.tick(); shown = false
                                scope.launch { core.unblockCaller(row.key) }
                            }.padding(MekaSpace.xs))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
            ) {
                if (typed.isEmpty()) Text("Number to block", style = MekaType.body, color = Meka.colors.textTertiary)
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it.take(40); refused = false },
                    singleLine = true,
                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                    cursorBrush = SolidColor(Meka.colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { block() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Number to block" },
                )
            }
            Text("Block", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button, enabled = typed.isNotBlank()) { block() }.padding(MekaSpace.xs))
        }
        AnimatedVisibility(refused, enter = enter, exit = exit) {
            Text("That isn't a phone number", style = MekaType.caption, color = Meka.colors.critical)
        }
        Text(CallScreeningRules.ONE_SCREENER_TITLE, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
            modifier = Modifier.padding(top = MekaSpace.xs))
        CallScreeningRules.ONE_SCREENER_LINES.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text(CallScreeningRules.OPEN_DEFAULT_APPS, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                haptics.tick()
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.padding(vertical = MekaSpace.xxs))
        AnimatedVisibility(!recognising, enter = enter, exit = exit) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text("Recognise callers · let MEKA check your contacts and recent calls, name who left a message, and say how an unknown call ended", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.tick(); runCatching { ask.launch(CallerLookup.PERMISSIONS) }
                    }.padding(vertical = MekaSpace.xxs))
                Text(
                    "Read on this phone only, never sent. Without it, only family and always-notify are always let through.",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
            }
        }
    }
}
