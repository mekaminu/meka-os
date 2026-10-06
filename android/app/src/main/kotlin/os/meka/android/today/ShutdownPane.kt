package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.CountUpText
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.ShutdownItem
import os.meka.core.domain.ShutdownView
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.TomorrowRow
import os.meka.core.facade.MekaCore

/** How long the "Day shut down" check stays before the pane drops away. */
private const val SHUT_DOWN_HOLD_MS = 700L

/**
 * Evening shutdown (build plan M1): what got done, what's left from today (tick it, carry it to tomorrow, skip a
 * repeating one, or send a one-off to Someday) and tomorrow at a glance, then "Shut down". Synced with the Mac.
 *
 * Motion: sections stagger in; a tick completes like a task (ring, check, light haptic) and the row leaves; carried
 * rows leave the list and land in Tomorrow (rows glide into place); Shut down pops a check with a light haptic and
 * the pane drops away. Reduced motion: cross-fades only.
 */
@Composable
fun ShutdownPane(core: MekaCore, onClose: () -> Unit) {
    val v by core.shutdownView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    var closing by remember { mutableStateOf(false) }
    val act: (suspend () -> Unit) -> Unit = { block -> scope.launch { runCatching { block() } } }
    // Ticks reuse Today's complete button, which plays the ring and haptic before calling back.
    val tick: (String) -> Unit = { id -> act { core.complete(id) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        }
        item(key = "title") {
            Column(Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0))) {
                Text("Shut down the day", style = MekaType.greeting, color = Meka.colors.textPrimary)
                if (v.doneCount > 0) {
                    CountUpText(v.doneCount, MekaType.itemMeta, Meka.colors.textSecondary, Modifier.padding(top = MekaSpace.xxs)) { "$it done today" }
                } else {
                    Text(v.doneCountLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
                }
            }
        }

        item(key = "h-left") { SectionLabel("Left from today", Modifier.animateItem().appear(rememberAppearance(1))) }
        if (v.left.isEmpty()) {
            item(key = "none-left") {
                Text("Nothing left from today.", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().appear(rememberAppearance(1)))
            }
        }
        items(v.left, key = { "l-" + it.task.id }) { item ->
            LeftRow(
                item, tick,
                tomorrow = { haptics.tick(); act { core.carryOver(item.task.id) } },
                skip = { haptics.tick(); act { core.skipOccurrence(item.task.id) } },
                someday = { haptics.tick(); act { core.moveToSomeday(item.task.id, SomedayKind.IDEA) } },
                modifier = Modifier.animateItem().appear(rememberAppearance(1)),
            )
        }
        if (v.left.size > 1) {
            item(key = "carry-all") {
                ShutdownButton("Move the rest to tomorrow", filled = false, Modifier.padding(top = MekaSpace.xs).animateItem().appear(rememberAppearance(1))) {
                    haptics.tick(); act { core.carryAllToTomorrow() }
                }
            }
        }

        item(key = "h-tomorrow") {
            TomorrowHeader(v, Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(2)))
        }
        items(v.tomorrow.rows, key = { "r-" + it.id }) { r -> TomorrowLine(r, Modifier.animateItem().appear(rememberAppearance(2))) }

        item(key = "shut") {
            Column(Modifier.padding(top = MekaSpace.xl).animateItem().appear(rememberAppearance(3)), horizontalAlignment = Alignment.CenterHorizontally) {
                ShutDownCheck(visible = closing || v.doneToday, line = v.doneLine ?: "Day shut down")
                Spacer(Modifier.height(MekaSpace.m))
                if (!v.doneToday && !closing) {
                    ShutdownButton("Shut down", filled = true) {
                        closing = true
                        haptics.light()
                        scope.launch {
                            runCatching { core.shutDown() }
                            delay(SHUT_DOWN_HOLD_MS)
                            onClose()
                        }
                    }
                } else {
                    ShutdownButton("Done", filled = false) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun LeftRow(
    item: ShutdownItem,
    tick: (String) -> Unit,
    tomorrow: () -> Unit,
    skip: () -> Unit,
    someday: () -> Unit,
    modifier: Modifier,
) {
    val t = item.task
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.s), verticalAlignment = Alignment.Top) {
        CompleteButton(t, tick)
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            item.line?.let { Text(it, style = MekaType.itemMeta, color = if (item.overdue) Meka.colors.critical else Meka.colors.textSecondary) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                ShutdownChip("Tomorrow", tomorrow)
                if (item.canSkip) ShutdownChip("Skip", skip)
                if (item.canSomeday) ShutdownChip("Someday", someday)
            }
        }
    }
}

@Composable
private fun TomorrowHeader(v: ShutdownView, modifier: Modifier) {
    val p = v.tomorrow
    Column(modifier) {
        SectionLabel(p.label)
        p.workLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        // The summary rolls over to its new value as items are carried in.
        AnimatedContent(
            targetState = p.summary,
            transitionSpec = { fadeIn(MekaMotion.appear(Meka.reducedMotion)) togetherWith fadeOut(MekaMotion.appear(Meka.reducedMotion)) },
            label = "tomorrow-summary",
        ) { s -> Text(s, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs)) }
    }
}

@Composable
private fun TomorrowLine(r: TomorrowRow, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        Text(r.time ?: "", style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.width(92.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.itemTitle, color = if (r.isEvent) Meka.colors.textPrimary else Meka.colors.textSecondary)
            r.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
    }
}

/** The check that pops when the day is shut down (reduced motion: fades in). */
@Composable
private fun ShutDownCheck(visible: Boolean, line: String) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(MekaMotion.appear(true))
        else scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) +
            fadeIn(MekaMotion.appear(false)),
        exit = fadeOut(MekaMotion.appear(reduced)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent).padding(horizontal = MekaSpace.l, vertical = MekaSpace.s)) {
                Text("✓", style = MekaType.upNextTitle, color = Meka.colors.onAccent)
            }
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
        }
    }
}

@Composable
private fun ShutdownChip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun ShutdownButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = if (filled) Meka.colors.onAccent else Meka.colors.accent,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}

/**
 * The evening card in Today: "Shut down the day" with what's left and tomorrow in one line. It rises in when the
 * evening starts (the list animates new items in) and goes once the day is shut down.
 */
@Composable
internal fun ShutdownCard(v: ShutdownView, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }.padding(MekaSpace.l),
    ) {
        Text("Shut down the day", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(v.cardLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}
