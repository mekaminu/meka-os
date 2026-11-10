package os.meka.android.today

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouchSquare
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.NeedsYouGroup
import os.meka.core.domain.NeedsYouGroupHead
import os.meka.core.domain.NeedsYouGroupRules
import os.meka.core.domain.NeedsYouPage

/*
 * Needs you grouped and folding (APPROVED 2026-11-10 21:51, docs/design/needs-you/needs-you-grouped.dc.html), slice 1 on
 * the folded Fold: each section's box head (icon, title, count, one-line peek, the chevron turning as it opens), the lead
 * line with its button at the top of a group, and the pager under a long group.
 */

internal const val NEEDS_YOU_GROUP_TAG = "needs-you-group"
internal const val NEEDS_YOU_PAGER_TAG = "needs-you-pager"

/** What he left open, kept on this phone (display state only, never synced). */
internal object NeedsYouGroupStore {
    private const val PREFS = "needs_you"
    private const val OPEN_KEY = "open_groups"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** To decide and Messages before he has opened or closed anything. */
    fun load(context: Context): Set<NeedsYouGroup> = NeedsYouGroupRules.decode(prefs(context).getString(OPEN_KEY, null))
    fun save(context: Context, open: Set<NeedsYouGroup>) { prefs(context).edit().putString(OPEN_KEY, NeedsYouGroupRules.encode(open)).apply() }
}

/** The open groups as state: read once from [NeedsYouGroupStore], written back on every open or close. */
@Composable
internal fun rememberOpenGroups(): MutableState<Set<NeedsYouGroup>> {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { mutableStateOf(NeedsYouGroupStore.load(context)) }
    return remember(state) {
        object : MutableState<Set<NeedsYouGroup>> by state {
            override var value: Set<NeedsYouGroup>
                get() = state.value
                set(v) { state.value = v; NeedsYouGroupStore.save(context, v) }
        }
    }
}

/** The group's colour pair (icon tile and badge), as the mockup's. */
@Composable
private fun groupInk(group: NeedsYouGroup): Color = when (group) {
    NeedsYouGroup.DECIDE -> Meka.colors.approval
    NeedsYouGroup.MESSAGES -> Meka.colors.calendar1
    NeedsYouGroup.FYI, NeedsYouGroup.CHATS -> Meka.colors.textSecondary
    NeedsYouGroup.MAIL -> Meka.colors.accent
    NeedsYouGroup.RENEWALS -> Meka.colors.approval
    NeedsYouGroup.TIDIED -> Meka.colors.success
}

/**
 * A group's head: the icon tile, the title with its count, the peek under it and the chevron, which turns half a turn on
 * the expand spring as the group opens (at once with reduced motion). A tap (light haptic) opens or closes it; the rows
 * under it arrive with the list's item motion. The head's bottom corners square off while open, so the box reads as one
 * with what it holds.
 */
@Composable
internal fun NeedsYouGroupHeadView(head: NeedsYouGroupHead, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val turn by animateFloatAsState(if (head.open) 180f else 0f, MekaMotion.expand(reduced), label = "ny-group-chev")
    val foot by animateDpAsState(if (head.open) 0.dp else MekaRadius.m, MekaMotion.expand(reduced), label = "ny-group-corner")
    val ink = groupInk(head.group)
    val chevInk = Meka.colors.textSecondary
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = MekaRadius.m, topEnd = MekaRadius.m, bottomStart = foot, bottomEnd = foot))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClickLabel = if (head.open) "Close" else "Open") { haptics.light(); onToggle() }
            .semantics(mergeDescendants = true) {
                contentDescription = head.spoken; stateDescription = if (head.open) "Open" else "Closed"
            }
            .testTag(NEEDS_YOU_GROUP_TAG)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(head.group, ink)
        Spacer(Modifier.width(MekaSpace.xs))
        Column(Modifier.weight(1f).animateContentSize(MekaMotion.expand(reduced))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(head.title, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (head.count.isNotEmpty()) {
                    Text(
                        head.count, style = MekaType.captionStrong, color = ink, maxLines = 1,
                        modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                            .background(Meka.colors.surfaceRaised).padding(horizontal = MekaSpace.xs),
                    )
                }
            }
            if (head.peek.isNotEmpty()) {
                Text(head.peek, style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MekaSpace.xxs))
            }
        }
        Spacer(Modifier.width(MekaSpace.xs))
        Canvas(Modifier.size(18.dp).rotate(turn).clearAndSetSemantics { }) {
            val s = size.width / 24f
            scale(s, s, pivot = Offset.Zero) {
                drawPath(chevron, chevInk, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private val chevron = PathParser().parsePathString("M6 9l6 6 6-6").toPath()

@Composable
private fun GroupIcon(group: NeedsYouGroup, ink: Color) {
    val path = remember(group) { PathParser().parsePathString(group.path).toPath() }
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                drawPath(path, ink, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The quiet line at the top of a group with its button: "Nothing here needs a reply" · Clear all. */
@Composable
internal fun NeedsYouGroupLead(line: String, button: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)
            .padding(start = MekaSpace.m, end = MekaSpace.xs, top = MekaSpace.xxs, bottom = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Box(
            Modifier.padding(start = MekaSpace.xs).heightIn(min = 32.dp).clip(RoundedCornerShape(MekaRadius.pill))
                .background(Meka.colors.accent).clickable(role = Role.Button) { haptics.light(); onClick() }
                .padding(horizontal = MekaSpace.m),
            contentAlignment = Alignment.Center,
        ) { Text(button, style = MekaType.captionStrong, color = Meka.colors.onAccent, maxLines = 1) }
    }
}

/**
 * Under a long group: "1–5 of 14", a dot per page (the current one a wider gold bar, gliding on the expand spring) and
 * ‹ › (tick haptic; dimmed at either end).
 */
@Composable
internal fun NeedsYouPager(page: NeedsYouPage, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().testTag(NEEDS_YOU_PAGER_TAG).padding(start = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(page.label, style = MekaType.micro, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { }) {
            repeat(page.pages) { i ->
                val w by animateDpAsState(if (i == page.page) 14.dp else 5.dp, MekaMotion.expand(reduced), label = "ny-dot")
                Box(Modifier.width(w).height(5.dp).clip(CircleShape)
                    .background(if (i == page.page) Meka.colors.accent else Meka.colors.hairline))
            }
        }
        Spacer(Modifier.width(MekaSpace.xs))
        PagerArrow("‹", "Previous page", page.hasPrev) { haptics.tick(); onPage(page.page - 1) }
        PagerArrow("›", "Next page", page.hasNext) { haptics.tick(); onPage(page.page + 1) }
    }
}

@Composable
private fun PagerArrow(glyph: String, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.minTouchSquare().clip(CircleShape).clickable(enabled = on, role = Role.Button, onClickLabel = label) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Meka.colors.surfaceRaised), contentAlignment = Alignment.Center) {
            Text(glyph, style = MekaType.metaStrong, color = if (on) Meka.colors.accent else Meka.colors.textTertiary)
        }
    }
}
