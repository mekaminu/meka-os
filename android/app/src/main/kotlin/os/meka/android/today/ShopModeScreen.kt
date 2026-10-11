package os.meka.android.today

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaChoreography
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.ShopModeRules
import os.meka.core.domain.ShopModeView
import os.meka.core.domain.ShopRow

/**
 * Shop mode (Checklists in tasks, slice 2): a checklist or the shared Shopping list full screen, big rows (64 dp, the
 * `nowTitle` size), still to get first, the screen kept awake while it's open. It reads the device's own synced copy,
 * so it works offline. Motion (catalogue "Shop mode"): the screen rises from below and fades in on the appear spring;
 * a tap draws the check with a light haptic and a beat later the row glides down under Ticked/Got (the list's item
 * motion); tapping a done row puts it back (tick haptic) and it glides up. Done (or back) sinks the screen away. Reduced
 * motion: cross-fades, the check shown at once.
 */
@Composable
internal fun ShopModeScreen(view: ShopModeView, onTick: (ShopRow) -> Unit, onClose: () -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, MekaMotion.appear(reduced)) }
    val close: () -> Unit = {
        scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose() }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep the screen awake while shopping.
        val host = LocalView.current
        DisposableEffect(host) { host.keepScreenOn = true; onDispose { host.keepScreenOn = false } }

        // The order lags a beat behind a tick so the check draws in place first (as in the task detail).
        val target = view.toGet + view.done
        var shown by remember { mutableStateOf(target) }
        LaunchedEffect(target) {
            val ticks = shown.map { it.id to it.done }.toSet() != target.map { it.id to it.done }.toSet()
            if (ticks && !reduced && shown.size == target.size) delay(MekaChoreography.checkDrawMs.toLong())
            shown = target
        }
        val live = target.associateBy { it.id }
        val rows = shown.mapNotNull { live[it.id] } + target.filter { t -> shown.none { it.id == t.id } }
        var tail = rows.size
        while (tail > 0 && rows[tail - 1].done) tail--

        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = rise.value
                    if (!reduced) translationY = (1f - rise.value) * 48.dp.toPx()
                }
                .background(Meka.colors.background)
                .safeDrawingPadding()
                .padding(horizontal = MekaSpace.l),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(view.title, style = MekaType.greeting, color = Meka.colors.textPrimary, maxLines = 2)
                    Text(view.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                }
                Text(
                    ShopModeRules.CLOSE, style = MekaType.metaStrong, color = Meka.colors.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .clickable(role = Role.Button) { haptics.light(); close() }
                        .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
                )
            }
            Spacer(Modifier.size(MekaSpace.m))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (rows.isEmpty()) item(key = "empty") {
                    Text(ShopModeRules.EMPTY, style = MekaType.body, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                }
                items(rows.subList(0, tail), key = { it.id }) { r -> ShopModeRow(r, Modifier.animateItem()) { tick(r, haptics, onTick) } }
                if (tail < rows.size) {
                    item(key = "done-label") {
                        SectionLabel(view.doneLabel, Modifier.animateItem().padding(top = MekaSpace.l, bottom = MekaSpace.xs))
                    }
                    items(rows.subList(tail, rows.size), key = { it.id }) { r -> ShopModeRow(r, Modifier.animateItem()) { tick(r, haptics, onTick) } }
                }
            }
            Text(
                ShopModeRules.OFFLINE_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(vertical = MekaSpace.m),
            )
        }
    }
}

private fun tick(r: ShopRow, haptics: os.meka.android.designsystem.MekaHaptics, onTick: (ShopRow) -> Unit) {
    if (r.done) haptics.tick() else haptics.light()
    onTick(r)
}

@Composable
private fun ShopModeRow(r: ShopRow, modifier: Modifier, onTap: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Checkbox, onClick = onTap)
            .semantics { contentDescription = (if (r.done) "Put back " else "Got ") + r.text }
            .padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickRing(r.done, Modifier.size(32.dp).clip(CircleShape))
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(r.text, style = MekaType.nowTitle, color = if (r.done) Meka.colors.textTertiary else Meka.colors.textPrimary)
            r.meta?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        }
    }
}
