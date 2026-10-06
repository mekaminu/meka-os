package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BriefLine
import os.meka.core.domain.DueState
import os.meka.core.domain.MorningBriefView
import os.meka.core.domain.WaitingItem
import os.meka.core.facade.MekaCore

/** How long the "Got it" check stays before the pane drops away. */
private const val GOT_IT_HOLD_MS = 600L

/**
 * Morning brief (build plan M1): today at a glance (work hours, events and what's planned or due, in time order),
 * what you're waiting on, what needs you on your lists, habits and a running fast. "Got it" puts the card away here
 * and on the Mac until tomorrow morning. Read-only: nothing in the brief changes anything.
 *
 * Motion: sections stagger in 40 ms apart; chases due today are lit in the accent colour; Got it pops a check (spring)
 * with a light haptic and the pane drops away. Reduced motion: cross-fades only.
 */
@Composable
fun BriefPane(core: MekaCore, onClose: () -> Unit) {
    val v by core.briefView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    var closing by remember { mutableStateOf(false) }

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
                Text("${v.greeting}, Meka", style = MekaType.greeting, color = Meka.colors.textPrimary)
                Text(listOfNotNull(v.dateLabel, v.workLine).joinToString(" · "), style = MekaType.itemMeta,
                    color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
            }
        }

        item(key = "h-day") {
            Column(Modifier.animateItem().appear(rememberAppearance(1))) {
                SectionLabel("Today")
                Text(v.daySummary, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs))
            }
        }
        items(v.day, key = { "d-" + it.id }) { r -> TomorrowLine(r, Modifier.animateItem().appear(rememberAppearance(1))) }

        val waitingLine = v.waitingLine
        if (waitingLine != null) {
            item(key = "h-waiting") {
                Column(Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(2))) {
                    SectionLabel("Waiting on")
                    Text(waitingLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs))
                }
            }
            items(v.waiting, key = { "w-" + it.id }) { w -> WaitingLine(w, Modifier.animateItem().appear(rememberAppearance(2))) }
            if (v.waitingTotal > v.waiting.size) {
                item(key = "w-more") {
                    Text("+${v.waitingTotal - v.waiting.size} more in Lists", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(2)))
                }
            }
        }

        if (v.attention.isNotEmpty()) {
            item(key = "h-lists") {
                SectionLabel("On your lists", Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(3)))
            }
            items(v.attention, key = { "a-" + it.id }) { a -> AttentionLine(a, Modifier.animateItem().appear(rememberAppearance(3))) }
        }

        val extras = listOfNotNull(v.habitsLine, v.fastingLine)
        if (extras.isNotEmpty()) {
            item(key = "h-you") {
                Column(Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(4))) {
                    SectionLabel("You")
                    extras.forEach { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(vertical = MekaSpace.xxs)) }
                }
            }
        }

        item(key = "got-it") {
            Column(Modifier.padding(top = MekaSpace.xl).animateItem().appear(rememberAppearance(5)), horizontalAlignment = Alignment.CenterHorizontally) {
                GotItCheck(visible = closing || v.seenToday)
                Spacer(Modifier.height(MekaSpace.m))
                if (!v.seenToday && !closing) {
                    BriefButton("Got it", filled = true) {
                        closing = true
                        haptics.light()
                        scope.launch {
                            runCatching { core.briefSeen() }
                            delay(GOT_IT_HOLD_MS)
                            onClose()
                        }
                    }
                } else {
                    BriefButton("Done", filled = false) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun WaitingLine(w: WaitingItem, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs)) {
        Text(w.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(w.meta, style = MekaType.caption, color = if (w.state == DueState.DUE) Meka.colors.accent else Meka.colors.textTertiary)
    }
}

@Composable
private fun AttentionLine(a: BriefLine, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs)) {
        Text(a.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        a.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
    }
}

/** The check that pops when the brief is read (reduced motion: fades in). */
@Composable
private fun GotItCheck(visible: Boolean) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(MekaMotion.appear(true))
        else scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) +
            fadeIn(MekaMotion.appear(false)),
        exit = fadeOut(MekaMotion.appear(reduced)),
    ) {
        Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent).padding(horizontal = MekaSpace.l, vertical = MekaSpace.s)) {
            Text("✓", style = MekaType.upNextTitle, color = Meka.colors.onAccent)
        }
    }
}

@Composable
private fun BriefButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = if (filled) Meka.colors.onAccent else Meka.colors.accent,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}

/**
 * The morning card in Today: "Morning brief" with the day in one line. It rises in when the morning starts (the end
 * of quiet hours) and goes at noon or once read.
 */
@Composable
internal fun BriefCard(v: MorningBriefView, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }.padding(MekaSpace.l),
    ) {
        Text("Morning brief", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(v.cardLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}
