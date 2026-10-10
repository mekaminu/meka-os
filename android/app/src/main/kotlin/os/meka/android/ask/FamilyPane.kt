package os.meka.android.ask

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
import os.meka.core.domain.FamilyLink
import os.meka.core.domain.FamilyRow
import os.meka.core.domain.FamilyRules
import os.meka.core.domain.FamilyState
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Family (family sharing with Jeanette, slice 4): make Jeanette's private link to the shopping list and
 * send it with the phone's share sheet, see whether she has opened it ("Joined Sat 10 Oct · seen today") and turn it off
 * in one tap. The links live on MEKA's server; the one just made is held only while this pane is open (it carries the
 * link's secret), never saved.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a shimmer until the server answers; the
 * summary and each line cross-fade; rows stagger in 40 ms apart; a waiting link's line is lit in the accent; Make a
 * link presses in with a light haptic and the "Link made" block unfolds (expand spring); Turn off gives a tick haptic and
 * the row's line cross-fades to "Turned off". Reduced motion: cross-fades.
 */
@Composable
fun FamilyPane(core: MekaCore, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val view by core.familyView.collectAsState()
    var busy by remember { mutableStateOf(false) }
    // The link just made: shown once, while the pane is open, and never saved (it carries the secret).
    var made by remember { mutableStateOf<FamilyLink?>(null) }
    LaunchedEffect(Unit) { runCatching { core.refreshFamily() } }
    val v = view

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Family", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.FAMILY)).appear(rememberAppearance(0)))
        Crossfade(v?.summary ?: "Checking…", animationSpec = MekaMotion.appear(reduced), label = "family-summary") { s ->
            Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
        }
        Text(FamilyRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(1)))
        val problem = v?.problem
        AnimatedVisibility(
            problem != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Text(problem.orEmpty(), style = MekaType.caption, color = Meka.colors.accent)
        }
        if (v == null) {
            SkeletonRows(2, Modifier.padding(top = MekaSpace.s))
        } else {
            v.rows.forEachIndexed { i, row ->
                FamilyRowView(row, enabled = !busy, modifier = Modifier.appear(rememberAppearance(i + 2))) {
                    haptics.tick()
                    busy = true
                    scope.launch {
                        try {
                            if (core.turnOffFamily(row.id) && made?.id == row.id) made = null
                        } finally { busy = false }
                    }
                }
            }
            if (v.canInvite && made == null) {
                PillButton("Make a link for ${FamilyRules.DEFAULT_NAME}", enabled = !busy,
                    modifier = Modifier.appear(rememberAppearance(v.rows.size + 2))) {
                    haptics.light()
                    busy = true
                    scope.launch {
                        try {
                            val link = core.inviteFamily(FamilyRules.DEFAULT_NAME)
                            if (link != null) { made = link; shareFamilyLink(context, link) }
                        } finally { busy = false }
                    }
                }
            }
        }
        AnimatedVisibility(
            made != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            val link = made
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text("Link made for ${link?.name.orEmpty()}", style = MekaType.body, color = Meka.colors.textPrimary)
                Text(FamilyRules.NOTE, style = MekaType.caption, color = Meka.colors.textSecondary)
                PillButton("Share the link again", enabled = link != null) {
                    haptics.light()
                    link?.let { shareFamilyLink(context, it) }
                }
            }
        }
    }
}

@Composable
private fun FamilyRowView(row: FamilyRow, enabled: Boolean, modifier: Modifier, onTurnOff: () -> Unit) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(
        if (row.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "family-line",
    )
    val dot by animateColorAsState(
        when (row.state) {
            FamilyState.JOINED -> Meka.colors.success
            FamilyState.WAITING -> Meka.colors.accent
            FamilyState.OFF -> Meka.colors.textTertiary
        },
        MekaMotion.appear(reduced), label = "family-dot",
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s)
            .semantics(mergeDescendants = true) { contentDescription = "${row.title}. ${row.line}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "family-row-line") { l ->
                Text(l, style = MekaType.caption, color = lineColor)
            }
        }
        if (row.canTurnOff) {
            Box(
                Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onTurnOff)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                Text("Turn off", style = MekaType.itemMeta, color = Meka.colors.critical)
            }
        }
    }
}

@Composable
private fun PillButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
    ) {
        Text(label, style = MekaType.itemMeta, color = Meka.colors.accent)
    }
}

/** The phone's share sheet with the link and a line for her; MEKA itself is left out (it would file the link as a task). */
fun shareFamilyLink(context: Context, link: FamilyLink) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link.shareText)
    }
    val chooser = Intent.createChooser(send, "Send ${link.name} the shopping list").apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, os.meka.android.capture.CaptureActivity::class.java)))
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
