package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.WeekAheadDay
import os.meka.core.domain.WeekAheadKind
import os.meka.core.domain.WeekAheadLine
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Week ahead (Sunday week ahead, slice 1), and the pane Sunday's 18:00 heads-up and Today's "Your week
 * ahead · …" line open: the coming week day by day — what kind of day (office or from home with the hours, annual
 * leave, a bank holiday), the weather while the forecast reaches it, then events, fixtures, school, date night,
 * appointments and renewals in time order — and FAMILY SYNC, the things that need both Meka and Jeanette. It only
 * reads; nothing here changes anything.
 *
 * Type weight (Meka, 2026-10-06): everything here is context, so the `body` style; a day's label is a section label.
 *
 * Motion: the pane springs up (MekaPane) with the More row's title travelling in; the summary cross-fades as it changes;
 * the days and the family sync stagger in 40 ms apart (the list's item motion as a day's lines change). Reduced motion:
 * cross-fades.
 */
@Composable
fun WeekAheadPane(core: MekaCore, onClose: () -> Unit, closeLabel: String = "Close") {
    val v by core.weekAheadView.collectAsState()
    val reduced = Meka.reducedMotion
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Column(Modifier.appear(rememberAppearance(0))) {
                Text("Week ahead", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WEEK_AHEAD)))
                Text("${v.title} · ${v.rangeLabel}", style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "week-ahead-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        itemsIndexed(v.days, key = { _, d -> "day-${d.epochDay}" }) { i, d ->
            DayBlock(d, Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(2 + i)))
        }
        if (v.sync.isNotEmpty()) {
            item(key = "sync") {
                Column(Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(2 + v.days.size)),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    SectionLabel("Family sync", Modifier.semantics { heading() })
                    Text("To sort out with Jeanette", style = MekaType.caption, color = Meka.colors.textTertiary)
                    v.sync.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = MekaSpace.xs).size(6.dp).background(Meka.colors.accent, CircleShape))
                            Text(line, style = MekaType.body, color = Meka.colors.textPrimary,
                                modifier = Modifier.padding(start = MekaSpace.xs).weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayBlock(d: WeekAheadDay, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        SectionLabel(d.label, Modifier.semantics { heading() })
        val meta = listOfNotNull(d.dayLine, d.weather).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MekaType.caption, color = Meka.colors.textSecondary)
        if (d.lines.isEmpty() && d.dayLine == null) {
            Text("Nothing in the diary", style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        d.lines.forEach { WeekLine(it) }
        if (d.more > 0) Text("and ${d.more} more", style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun WeekLine(l: WeekAheadLine) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).semantics(mergeDescendants = true) { contentDescription = l.spoken },
        verticalAlignment = Alignment.Top,
    ) {
        Text(l.time ?: mark(l.kind), style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(56.dp))
        Text(l.text, style = MekaType.body, color = if (l.lit) Meka.colors.accent else Meka.colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** The lead mark for a line with no time: school, money due, the rest a dot. */
private fun mark(kind: WeekAheadKind): String = when (kind) {
    WeekAheadKind.SCHOOL -> "School"
    WeekAheadKind.DUE -> "Due"
    else -> "·"
}
