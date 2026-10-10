package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.CalendarView
import os.meka.core.domain.ComingUpDay
import os.meka.core.domain.CommandCentreRules
import os.meka.core.domain.CommandColumn
import os.meka.core.domain.NeedsYouStack
import os.meka.core.domain.NewsGlance
import os.meka.core.domain.NewsPlace
import os.meka.core.facade.MekaCore

/**
 * The open Fold as a command centre (build plan M1, Fold modes slice 2; [CommandCentreRules]): the columns beside
 * Today. Two columns: Needs you above Coming up, or the open task's detail. Three: Needs you (or the detail) and
 * Coming up side by side. Unfolding grows them out beside Today (the pane morph); they stagger in 40 ms apart; opening
 * a task cross-fades its detail into Needs you's place and closing it cross-fades back. Reduced motion: cross-fades.
 */
@Composable
internal fun CommandSide(
    columns: List<CommandColumn>,
    modifier: Modifier,
    detail: @Composable (Modifier) -> Unit,
    needsYou: @Composable (Modifier) -> Unit,
    comingUp: @Composable (Modifier, Boolean) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val first = columns.firstOrNull { it != CommandColumn.TODAY } ?: return
    val withComingUp = CommandColumn.COMING_UP in columns
    Row(modifier) {
        AnimatedContent(
            targetState = first,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "command-side",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { c ->
            when (c) {
                CommandColumn.DETAIL -> detail(Modifier.fillMaxSize())
                CommandColumn.NEEDS_YOU -> needsYou(Modifier.fillMaxSize())
                CommandColumn.NEEDS_YOU_AND_COMING_UP -> Column(Modifier.fillMaxSize()) {
                    needsYou(Modifier.weight(0.55f).fillMaxWidth())
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.hairline))
                    comingUp(Modifier.weight(0.45f).fillMaxWidth(), true)
                }
                else -> Box(Modifier.fillMaxSize())
            }
        }
        if (withComingUp) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Meka.colors.hairline))
            comingUp(Modifier.weight(1f).fillMaxHeight(), false)
        }
    }
}

/**
 * Coming up: the days after today with something on them, a few lines each ("+2 more"), then "Open Calendar ›".
 * An event opens its detail; a day's heading opens the Calendar tab. Shared with Needs you, it shows fewer days.
 * Below it, News (news ticker, slice 2): today's match in Barça's colour, the top story of each lane, "Open News ›";
 * a story opens the News place on that story ([openNews] with its id), the match opens its detail. Left out when
 * there's nothing to show.
 */
@Composable
internal fun ComingUpColumn(
    view: CalendarView,
    shared: Boolean,
    modifier: Modifier,
    openEvent: (CalendarEvent) -> Unit,
    openCalendar: () -> Unit,
    news: NewsPlace = NewsPlace.EMPTY,
    openNews: (String?) -> Unit = {},
) {
    val c = remember(view, shared) {
        if (shared) CommandCentreRules.comingUp(view, CommandCentreRules.DAYS_SHARED, CommandCentreRules.LINES_SHARED)
        else CommandCentreRules.comingUp(view, CommandCentreRules.DAYS_ALONE, CommandCentreRules.LINES_ALONE)
    }
    val glance = remember(news, shared) {
        CommandCentreRules.newsGlance(news, if (shared) CommandCentreRules.NEWS_SHARED else CommandCentreRules.NEWS_ALONE)
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        item(key = "title") { SectionLabel("Coming up", Modifier.animateItem().appear(rememberAppearance(0))) }
        c.emptyLine?.let { line ->
            item(key = "empty") {
                Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                    modifier = Modifier.animateItem().appear(rememberAppearance(1)))
            }
        }
        c.days.forEachIndexed { i, day ->
            item(key = day.id) { ComingUpDayView(day, Modifier.animateItem().appear(rememberAppearance(1 + i)), openEvent, openCalendar) }
        }
        item(key = "open") {
            Text(
                "Open Calendar ›", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClick = openCalendar)
                    .padding(vertical = MekaSpace.xxs).animateItem().appear(rememberAppearance(1 + c.days.size)),
            )
        }
        glance?.let { g ->
            item(key = "news") {
                NewsGlanceView(g, Modifier.padding(top = MekaSpace.xl).animateItem().appear(rememberAppearance(2 + c.days.size)), openEvent, openNews)
            }
        }
    }
}

@Composable
private fun NewsGlanceView(g: NewsGlance, modifier: Modifier, openEvent: (CalendarEvent) -> Unit, openNews: (String?) -> Unit) {
    val reducedLine = Meka.reducedMotion
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        SectionLabel("News")
        g.matchday?.let { md ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClickLabel = "Open the match") { openEvent(md.event) }
                    .padding(vertical = MekaSpace.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.padding(end = MekaSpace.xs).size(6.dp).clip(CircleShape).background(Meka.colors.barca))
                AnimatedContent(
                    targetState = md.line,
                    transitionSpec = { fadeIn(MekaMotion.appear(reducedLine)) togetherWith fadeOut(MekaMotion.appear(reducedLine)) },
                    label = "glance-matchday",
                ) { line -> Text(line, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        g.items.forEach { n ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClickLabel = "Open story") { openNews(n.id) }
                    .padding(vertical = MekaSpace.xxs),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 7.dp, end = MekaSpace.xs) // rhythm: ok (7 dp centres the dot on the title's first line)
                    .size(6.dp).clip(CircleShape)
                    .background(if (n.topic == "barca") Meka.colors.barca else Meka.colors.textTertiary))
                Column(Modifier.weight(1f)) {
                    // News is context: the regular weight.
                    Text(n.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(n.meta, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1)
                }
            }
        }
        Text(
            "Open News ›", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button) { openNews(null) }.padding(vertical = MekaSpace.xxs),
        )
    }
}

@Composable
private fun ComingUpDayView(day: ComingUpDay, modifier: Modifier, openEvent: (CalendarEvent) -> Unit, openCalendar: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = MekaSpace.m)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = openCalendar)
                .padding(vertical = MekaSpace.xxs),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Text(day.title, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
            day.subtitle?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        day.lines.forEach { line ->
            val fixture = line.event?.isFixture == true
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .then(line.event?.let { e -> Modifier.clickable(role = Role.Button) { openEvent(e) } } ?: Modifier)
                    .padding(vertical = MekaSpace.xxs),
                verticalAlignment = Alignment.Top,
            ) {
                Text(line.time, style = MekaType.caption, color = if (fixture) Meka.colors.accent else Meka.colors.textTertiary,
                    maxLines = 1, modifier = Modifier.width(COMING_UP_TIME))
                // Events are context (body weight); planned tasks are things you act on, but here they're only shown.
                Text(line.title, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        day.moreLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(start = COMING_UP_TIME)) }
    }
}

/** Wide enough for "09:30–10:00" in the caption style. */
private val COMING_UP_TIME = 84.dp

/** The compact Needs you column beside Today, with its count in the heading. */
@Composable
internal fun CommandNeedsYou(core: MekaCore, stack: NeedsYouStack, moves: DecisionMoves, modifier: Modifier, openAfterWork: () -> Unit) {
    NeedsYouColumn(core, stack, moves, modifier, openAfterWork, compact = true)
}
