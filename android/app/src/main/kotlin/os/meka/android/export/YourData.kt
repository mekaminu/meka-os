package os.meka.android.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.ExportSummary
import os.meka.core.facade.DataExportFile
import os.meka.core.facade.MekaCore

/** Where an export is: nothing yet, building the file, waiting on the save picker, saved, or failed. */
private sealed interface ExportPhase {
    data object Idle : ExportPhase
    data object Preparing : ExportPhase
    data class Saved(val fileName: String, val line: String) : ExportPhase
    data class Failed(val message: String) : ExportPhase
}

/**
 * Your data (build plan M1, export and backup): everything on this phone as one JSON file, saved wherever Meka picks
 * (Downloads, Drive, a USB stick) with Android's own save screen. MEKA writes only to the file chosen there and sends
 * nothing anywhere. Shown in a pane from Today's header and as the Vault screen on the open Fold.
 *
 * Motion: sections stagger in; a shimmer runs while the file is built; the check pops (spring) with a light haptic
 * once it's saved. Reduced motion: cross-fades.
 */
@Composable
fun YourData(core: MekaCore, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, vaultLine: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    var summary by remember { mutableStateOf<ExportSummary?>(null) }
    var phase by remember { mutableStateOf<ExportPhase>(ExportPhase.Idle) }
    // The file waiting for the save screen to come back (kept in memory only).
    var pending by remember { mutableStateOf<DataExportFile?>(null) }
    LaunchedEffect(Unit) { summary = runCatching { core.exportSummary() }.getOrNull() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val file = pending
        pending = null
        if (uri == null || file == null) { phase = ExportPhase.Idle; return@rememberLauncherForActivityResult }
        scope.launch {
            phase = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.json.encodeToByteArray()) }
                        ?: error("no stream")
                }
                ExportPhase.Saved(file.fileName, file.summary.totalLine)
            }.getOrElse { ExportPhase.Failed("Couldn't save the file there. Try another place.") }
            if (phase is ExportPhase.Saved) haptics.light()
        }
    }

    fun export() {
        phase = ExportPhase.Preparing
        scope.launch {
            val file = runCatching { core.exportAll() }.getOrNull()
            if (file == null) { phase = ExportPhase.Failed("Couldn't put the export together. Try again."); return@launch }
            summary = file.summary
            pending = file
            runCatching { save.launch(file.fileName) }.onFailure {
                pending = null
                phase = ExportPhase.Failed("This phone has no app to save files with.")
            }
        }
    }

    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        if (onClose != null) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        }
        Text(if (vaultLine != null) "Vault" else "Your data", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.appear(rememberAppearance(0)))
        vaultLine?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0))) }

        Spacer(Modifier.height(MekaSpace.m))
        Text("EXPORT EVERYTHING", style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(1)))
        Text(
            "Everything on this phone as one file you can keep: tasks, steps, lists, renewals, goals, habits, fasts, " +
                "calendar events and settings. Documents join it when the Vault lands.",
            style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)),
        )
        Column(Modifier.appear(rememberAppearance(2))) {
            val s = summary
            if (s == null) SkeletonRows(count = 1, rowHeight = 36.dp)
            else {
                Text(s.totalLine, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                if (s.partsLine.isNotEmpty()) Text(s.partsLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(MekaSpace.s))
        Column(Modifier.fillMaxWidth().appear(rememberAppearance(3)), horizontalAlignment = Alignment.CenterHorizontally) {
            SavedCheck(phase as? ExportPhase.Saved)
            Crossfade(phase, animationSpec = MekaMotion.appear(reduced), label = "export-phase") { p ->
                when (p) {
                    ExportPhase.Preparing -> SkeletonRows(count = 1, rowHeight = 52.dp)
                    is ExportPhase.Failed -> Column {
                        Text(p.message, style = MekaType.itemMeta, color = Meka.colors.critical, modifier = Modifier.padding(bottom = MekaSpace.xs))
                        ExportButton("Try again") { export() }
                    }
                    is ExportPhase.Saved -> ExportButton("Export again", filled = false) { export() }
                    ExportPhase.Idle -> ExportButton("Export everything") { if (summary?.total != 0) export() }
                }
            }
        }
        Text(
            "The file isn't encrypted: keep it somewhere only you can open. It's JSON, readable without MEKA.",
            style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(3)),
        )
    }
}

/** The check that pops once the file is saved, with its name (reduced motion: fades in). */
@Composable
private fun SavedCheck(saved: ExportPhase.Saved?) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = saved != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true))
        else scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) +
            fadeIn(MekaMotion.appear(false)),
        exit = fadeOut(MekaMotion.appear(reduced)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = MekaSpace.m)) {
            Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent).padding(horizontal = MekaSpace.l, vertical = MekaSpace.s)) {
                Text("✓", style = MekaType.upNextTitle, color = Meka.colors.onAccent)
            }
            saved?.let {
                Text("Saved ${it.fileName} · ${it.line}", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(top = MekaSpace.xs))
            }
        }
    }
}

@Composable
private fun ExportButton(label: String, filled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        Text(label, style = MekaType.itemTitle, color = if (filled) Meka.colors.onAccent else Meka.colors.accent)
    }
}
