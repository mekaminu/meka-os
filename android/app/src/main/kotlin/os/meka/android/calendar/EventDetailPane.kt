package os.meka.android.calendar

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.MekaMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.facade.MekaCore

/**
 * Event detail (calendar redesign, slice 3): what, when and how soon, which calendar, where (tap to open Maps), a Join
 * button for Meet/Teams/Zoom links, and the event's notes. Opened from an event in Today or the Calendar tab. Shows
 * only: events are a mirror of your calendars and are changed there.
 *
 * The notes come from whoever made the event, so they are untrusted (ADR-006): plain text only, nothing in them is
 * opened unless you tap Join (an https link to a known call service, or the provider's own link).
 *
 * Motion: the pane springs up from the bottom ([os.meka.android.designsystem.MekaPane]); its sections stagger in 40 ms
 * apart; Join gives a light haptic. Reduced motion: cross-fades only.
 */
@Composable
fun EventDetailPane(core: MekaCore, event: CalendarEvent, onClose: () -> Unit) {
    // "In 25 min" moves on while the pane is open.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(event.id) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val marks by core.eventMarks.collectAsState()
    val d = remember(event, tick, marks) { core.eventDetail(event) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val haptics = rememberMekaHaptics()
    BackHandler(onBack = onClose)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
    ) {
        Text(
            "Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s),
        )

        Column(Modifier.padding(top = MekaSpace.s, bottom = MekaSpace.l).appear(rememberAppearance(0))) {
            Text(d.title, style = MekaType.greeting, color = Meka.colors.textPrimary)
            Text(
                listOfNotNull(d.whenLine, d.duration).joinToString(" · "), style = MekaType.itemMeta,
                color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs),
            )
            d.status?.let {
                Text(
                    it, style = MekaType.itemMeta,
                    color = if (d.statusLit) Meka.colors.accent else Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.xxs),
                )
            }
        }

        // Calendar actions: MEKA-only, the real event is untouched.
        Row(
            Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(1)),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (d.canPrep) {
                ActionChip("Prep task") {
                    haptics.light()
                    scope.launch { runCatching { core.addPrepTask(event) } }
                }
            }
            ActionChip(if (d.hidden) "Show in my day" else "Hide from my day") {
                haptics.light()
                scope.launch { runCatching { if (d.hidden) core.showEvent(event.id) else core.hideEvent(event.id) } }
            }
        }
        val actionNote = listOfNotNull(d.prepLine, if (d.hidden) "Hidden from your day" else null).joinToString(" · ")
        val reduced = Meka.reducedMotion
        AnimatedContent(
            targetState = actionNote,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "event-actions-note",
        ) { note ->
            if (note.isNotEmpty()) {
                Text(note, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.l))
            }
        }

        d.join?.let { j ->
            Text(
                j.label, style = MekaType.itemTitle, color = Meka.colors.onAccent,
                modifier = Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(1))
                    .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button) {
                        haptics.light()
                        runCatching { uriHandler.openUri(j.url) }
                    }
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
            )
        }

        d.location?.let { place ->
            val query = d.mapsQuery
            DetailBlock("Where", 2) {
                Text(place, style = MekaType.body, color = Meka.colors.textPrimary)
                if (query != null) {
                    Text(
                        "Open in Maps", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) {
                            runCatching { uriHandler.openUri("geo:0,0?q=" + Uri.encode(query)) }
                        }.padding(vertical = MekaSpace.xs),
                    )
                }
            }
        }

        d.calendarLine?.let { line ->
            DetailBlock("Calendar", 3) {
                Text(line, style = MekaType.body, color = if (d.isFixture) Meka.colors.accent else Meka.colors.textPrimary)
            }
        }

        d.notes?.let { notes ->
            DetailBlock("Notes", 4) {
                Text(notes, style = MekaType.body, color = Meka.colors.textSecondary)
            }
        }

        Text(
            "Change the event itself in your calendar. Prep tasks and hiding stay in MEKA.", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(5)),
        )
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

/** A quiet outlined pill button for the event's actions. */
@Composable
private fun ActionChip(label: String, onTap: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .border(1.dp, Meka.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button) { onTap() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun DetailBlock(label: String, index: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(index))) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(bottom = MekaSpace.xxs))
        content()
    }
}

/** A tappable event title area used by the timeline and agenda rows. */
internal fun Modifier.opensEvent(event: CalendarEvent?, open: ((CalendarEvent) -> Unit)?): Modifier =
    if (event == null || open == null) this else this.clickable(role = Role.Button) { open(event) }
