package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.MailMomentCard
import os.meka.core.domain.MailMomentRules
import os.meka.core.domain.MailMomentSession
import os.meka.core.domain.MailNeedsRules
import os.meka.core.facade.MekaCore

/** How far a sideways swipe must go to skip the email (dp). */
private const val SKIP_SWIPE_DP = 72f

/**
 * Inbox autopilot, slice 7: **the ten-minute inbox** in Needs you. From the moment (morning or evening, Mail pane) while
 * emails need him: "TEN-MINUTE INBOX" · "6 need you · 2 min each" · the streak, with **Start** and **Not today** (Undo on
 * the bar). Start turns the card into the session: one email at a time ("2 of 6", "1 done · 5 to go") with the card's own
 * answers (Reply, Done, Task, Waiting for, Snooze — each with Undo on the bar) and **Skip** (or swipe it aside: it follows
 * the finger and goes to the back); each answer slides the next email in from the right (an eighth of the width on the
 * expand spring with a fade; reduced motion: cross-fade). The end says "Inbox clear" with the streak, and Close.
 */
@Composable
internal fun MailMomentSection(
    card: MailMomentCard?, session: MailMomentSession?, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).animateContentSize(MekaMotion.expand(reduced)).testTag("mail-moment"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        SectionLabel(MailMomentRules.TITLE)
        if (session == null && card != null) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = card.spoken }) {
                Text(card.headline, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("mail-moment-headline"))
                card.streakLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent) }
            }
            Row(Modifier.padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                MailCardPill(MailMomentRules.START, spoken = "Start the ten-minute inbox: ${card.headline}", onClick = {
                    haptics.medium(); scope.launch { runCatching { core.startMailMoment() } }
                })
                MailCardPill(MailMomentRules.NOT_TODAY, quiet = true, spoken = "Not today: hide the ten-minute inbox until tomorrow", onClick = {
                    haptics.tick()
                    scope.launch {
                        val said = runCatching { core.notTodayMailMoment() }.getOrNull() ?: return@launch
                        undo(said) { runCatching { core.undoMailMoment() } }
                    }
                })
            }
        } else if (session != null) {
            MomentSession(session, core, undo)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentSession(session: MailMomentSession, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    fun answer(action: suspend () -> String) {
        scope.launch {
            val said = runCatching { action() }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoMailItem() } }
        }
    }
    // Where he is, cross-fading as he goes ("2 of 6 · 1 done · 5 to go"; the end's "Inbox clear").
    AnimatedContent(listOfNotNull(session.position, session.line).joinToString(" · "),
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-moment-line") {
        Text(it, style = MekaType.body, color = Meka.colors.textPrimary,
            modifier = Modifier.testTag("mail-moment-line").semantics { liveRegion = LiveRegionMode.Polite })
    }
    AnimatedContent(
        targetState = session.row,
        transitionSpec = {
            if (reduced) fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
            else (slideInHorizontally(MekaMotion.expand(false)) { w -> w / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                (slideOutHorizontally(MekaMotion.expand(false)) { w -> -w / 8 } + fadeOut(MekaMotion.appear(false))) using
                SizeTransform(clip = false)
        },
        contentKey = { it?.id },
        label = "mail-moment-row",
    ) { row ->
        if (row == null) {
            Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                session.streakLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.testTag("mail-moment-streak")) }
                MailCardPill(MailMomentRules.CLOSE, spoken = "Close the ten-minute inbox", onClick = {
                    haptics.light(); scope.launch { runCatching { core.endMailMoment() } }
                })
            }
        } else {
            // The email follows a sideways drag; past the threshold it is skipped (tick haptic), else it springs back.
            var dragged by remember(row.id) { mutableFloatStateOf(0f) }
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.xs)
                    .graphicsLayer { if (!reduced) { translationX = dragged; alpha = 1f - (kotlin.math.abs(dragged) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 0.5f) } }
                    .pointerInput(row.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragCancel = { dragged = 0f },
                            onDragEnd = {
                                if (kotlin.math.abs(dragged) > SKIP_SWIPE_DP * density) {
                                    haptics.tick(); scope.launch { runCatching { core.skipMailMoment(row.id) } }
                                }
                                dragged = 0f
                            },
                        ) { _, amount -> dragged += amount }
                    },
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                MailNeedsRowView(
                    row,
                    onDone = { haptics.medium(); answer { core.doneMailItem(row.id) } },
                    onTask = { haptics.medium(); answer { core.taskFromMailItem(row.id) } },
                    onToDay = { day -> haptics.medium(); answer { core.mailToDay(row.id, day) } },
                    toDays = { core.boardFeedDays() },
                    onWaiting = { haptics.medium(); answer { core.waitingFromMailItem(row.id) } },
                    onSnooze = { haptics.light(); answer { core.snoozeMailItem(row.id) } },
                    onDraft = { core.draftMailReply(row.id) },
                    onSuggestTimes = { core.suggestMeetingTimes(row.id) },
                    onBook = { haptics.medium(); answer { core.bookMeetingPick(row.id) } },
                    onBlock = { haptics.medium(); answer { core.blockMailItem(row.id) } },
                    onUnsubscribe = { haptics.medium(); answer { core.unsubscribeMailItem(row.id) } },
                    onRead = { images -> core.readMailItem(row.id, images) },
                    onAttachment = { v, i -> core.openMailAttachment(v, i) },
                    onSend = { text, sent ->
                        haptics.medium()
                        scope.launch {
                            val said = runCatching { core.replyToMailItem(row.id, text) }.getOrNull() ?: return@launch
                            if (MailNeedsRules.replyProblem(text) != null) return@launch
                            sent()
                            undo(said) { runCatching { core.undoMailItem() } }
                        }
                    },
                )
            }
        }
    }
    session.row?.let { up ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            if (session.left > 1) {
                MailCardPill(MailMomentRules.SKIP, quiet = true, spoken = "Skip this email for now; it comes round again", onClick = {
                    haptics.tick(); scope.launch { runCatching { core.skipMailMoment(up.id) } }
                })
            }
            MailCardPill(MailMomentRules.FINISH, quiet = true, spoken = "Finish for now; the rest stay in From your mail", onClick = {
                haptics.light(); scope.launch { runCatching { core.endMailMoment() } }
            })
        }
    }
}
