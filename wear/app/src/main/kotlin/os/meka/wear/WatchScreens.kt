package os.meka.wear

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.core.domain.WatchCaptureRules
import os.meka.core.domain.WatchCaptured
import os.meka.core.domain.WatchHomeRules
import os.meka.core.domain.WatchListenFailure
import os.meka.core.domain.WatchLinkRules
import os.meka.core.domain.WatchVoiceNoteRules
import os.meka.core.domain.WatchVoiceNoted
import os.meka.core.facade.MekaCore
import os.meka.core.sync.SyncStatus
import os.meka.wear.designsystem.MekaChoreography
import os.meka.wear.designsystem.MekaDarkColors
import os.meka.wear.designsystem.MekaMotion
import os.meka.wear.designsystem.MekaRadius
import os.meka.wear.designsystem.MekaSpace
import os.meka.wear.designsystem.MekaType

/** A watch is always dark (true black saves its screen). */
private val C = MekaDarkColors

/** The fast's ring on the wrist (the Fasting design's ring, sized for a round watch). */
private val RING = 96.dp
private val RING_STROKE = 7.dp

@Composable
fun WatchRoot(app: WatchApplication, core: MekaCore?, reduced: Boolean, listen: Int = 0, onListen: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(C.background)) {
        AnimatedContent(
            targetState = core,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "watchRoot",
        ) { c ->
            if (c == null) LinkScreen(app, reduced) else DayScreen(app, c, reduced, listen, onListen)
        }
    }
}

/** A round screen's column: centred, inset so nothing sits under the bezel, turned by the crown. */
@Composable
private fun WatchColumn(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e -> scope.launch { scroll.scrollBy(e.verticalScrollPixels) }; true }
            .focusRequester(focus)
            .focusable()
            .verticalScroll(scroll)
            .padding(horizontal = MekaSpace.gutterWide, vertical = MekaSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) { content() }
}

@Composable
private fun Line(text: String, style: androidx.compose.ui.text.TextStyle, maxLines: Int = 2) {
    BasicText(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = style.copy(textAlign = TextAlign.Center),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Presses in (0.97) on the complete spring while held; the primary one is filled with the accent. */
@Composable
private fun WatchButton(label: String, primary: Boolean, reduced: Boolean, haptic: Int, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) MekaChoreography.pressScale else 1f,
        animationSpec = MekaMotion.complete(reduced),
        label = "press",
    )
    val shape = RoundedCornerShape(MekaRadius.pill)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MekaSpace.touch)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed && reduced) 0.8f else 1f }
            .background(if (primary) C.accent else C.surfaceRaised, shape)
            .border(1.dp, if (primary) C.accent else C.hairline, shape)
            .clickable(interactionSource = source, indication = null) {
                view.performHapticFeedback(haptic)
                onClick()
            }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = MekaType.metaStrong.copy(color = if (primary) C.onAccent else C.textPrimary, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

// ---- linking ----

@Composable
private fun LinkScreen(app: WatchApplication, reduced: Boolean) {
    val state by app.link.collectAsState()
    LifecycleStartEffect(Unit) {
        app.startLinking()
        onStopOrDispose { app.stopLinking() }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    WatchColumn {
        when (val s = state) {
            LinkState.NoServer -> {
                Line(WatchLinkRules.codeScreen("", 0, 0).title, MekaType.captionStrong.copy(color = C.accent))
                Line(WatchHomeRules.NO_SERVER, MekaType.caption.copy(color = C.textSecondary), maxLines = 4)
            }
            LinkState.Asking -> {
                Line(WatchLinkRules.codeScreen("", 0, 0).title, MekaType.captionStrong.copy(color = C.accent))
                Line(WatchHomeRules.ASKING, MekaType.caption.copy(color = C.textSecondary))
            }
            is LinkState.Failed -> {
                Line(WatchLinkRules.codeScreen("", 0, 0).title, MekaType.captionStrong.copy(color = C.accent))
                Line(s.reason, MekaType.caption.copy(color = C.textSecondary), maxLines = 4)
                Spacer(Modifier.height(MekaSpace.xs))
                WatchButton(WatchHomeRules.TRY_AGAIN, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                    app.startLinking(fresh = true)
                }
            }
            is LinkState.Showing -> {
                val expires = if (s.ranOut) minOf(s.pending.expiresAtMs, now) else s.pending.expiresAtMs
                val screen = WatchLinkRules.codeScreen(s.pending.code, expires, now)
                Line(screen.title, MekaType.captionStrong.copy(color = C.accent))
                AnimatedContent(
                    targetState = screen.code,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "code",
                ) { code ->
                    Line(
                        code,
                        MekaType.upNextTitle.copy(
                            color = if (screen.expired) C.textTertiary else C.textPrimary,
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                }
                Line(screen.line, MekaType.caption.copy(color = C.textSecondary))
                if (screen.expired) {
                    Spacer(Modifier.height(MekaSpace.xs))
                    WatchButton(screen.left, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                        app.startLinking(fresh = true)
                    }
                } else {
                    Line(screen.left, MekaType.caption.copy(color = C.textTertiary))
                }
            }
        }
    }
}

// ---- the day ----

@Composable
private fun DayScreen(app: WatchApplication, core: MekaCore, reduced: Boolean, listen: Int, onListen: () -> Unit) {
    LifecycleStartEffect(core) {
        core.startSync()
        onStopOrDispose { core.stopSync() }
    }
    val today by core.today.collectAsState()
    val fasting by core.fastingView.collectAsState()
    val sync by core.syncStatus.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(core) {
        while (true) {
            val n = System.currentTimeMillis()
            delay(WatchHomeRules.nextTickMs(n) - n)
            runCatching { core.tick() }
            tick++
        }
    }
    // Inbox autopilot, slice 7b-2: the inbox count follows the synced emails and the ten-minute inbox's moment.
    val mailNeeds by core.mailNeeds.collectAsState()
    val mailMoment by core.mailMoment.collectAsState()
    // Car and parking, slice 7: the cars' parking timers (synced; set on the phone or the Mac).
    val parkingTimers by core.parkingTimersView.collectAsState()
    // Barça match day, slice 4: the match near now, redrawn when a sync brings a new score.
    val barca by core.barcaNearView.collectAsState()
    // Done for the day, completeness 2: the evening glance follows this watch's own shutdown view.
    val shutdown by core.shutdownView.collectAsState()
    val home = remember(today, fasting, tick, mailNeeds, mailMoment, parkingTimers, barca, shutdown) { core.watchHome() }
    val scope = rememberCoroutineScope()

    if ((sync as? SyncStatus.Failing)?.reason == MekaCore.SIGNED_OUT_MESSAGE) {
        WatchColumn {
            Line(WatchHomeRules.UNLINKED, MekaType.metaStrong.copy(color = C.textPrimary))
            Line(WatchHomeRules.UNLINKED_LINE, MekaType.caption.copy(color = C.textSecondary), maxLines = 4)
            Spacer(Modifier.height(MekaSpace.xs))
            WatchButton(WatchHomeRules.LINK_AGAIN, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                app.forgetAndRelink()
            }
        }
        return
    }

    WatchColumn {
        // The card cross-slides like the cover screen's when what's now changes (old out left, new in from the right).
        AnimatedContent(
            targetState = home,
            contentKey = { it.label + "\u0000" + it.title },
            transitionSpec = {
                if (reduced) {
                    fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
                } else {
                    (slideInHorizontally(MekaMotion.replan(false)) { it / 3 } + fadeIn(MekaMotion.appear(false))) togetherWith
                        (slideOutHorizontally(MekaMotion.replan(false)) { -it / 3 } + fadeOut(MekaMotion.appear(false)))
                }
            },
            label = "now",
        ) { v ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Line(v.label, MekaType.captionStrong.copy(color = if (v.lit) C.accent else C.textSecondary), maxLines = 1)
                Line(v.title, MekaType.itemTitle.copy(color = C.textPrimary), maxLines = 3)
                v.line?.let { Line(it, MekaType.caption.copy(color = C.textSecondary)) }
                if (v.buttons.isNotEmpty()) Spacer(Modifier.height(MekaSpace.xxs))
                for (b in v.buttons) {
                    WatchButton(
                        b.label, b.primary, reduced,
                        haptic = if (b.primary) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK,
                    ) { scope.launch { runCatching { core.watchPress(b) } } }
                }
                v.thenLine?.let { Line(it, MekaType.caption.copy(color = C.textTertiary)) }
            }
        }

        // Fasting rebuild, the watch: a running fast's ring comes up under the now card (as Today's live card does on
        // the phone); resting, Start stays at the foot.
        if (home.fast.running) {
            Spacer(Modifier.height(MekaSpace.s))
            FastRow(home.fast, reduced) { scope.launch { runCatching { core.watchFastButton() } } }
        }

        // Week board, the watch: the day's next three open tasks in his order, each ticked off with one tap.
        TopThreeRows(core, home.top, reduced)

        Spacer(Modifier.height(MekaSpace.xs))
        CaptureBlock(core, reduced, listen, onListen)

        CommuteRow(home.commute, reduced)

        BinsRow(home.bins, reduced)

        PrepRow(home.prep, reduced)

        MatchRow(home.match, reduced)

        ParkingRows(home.parking, reduced) { carId -> scope.launch { runCatching { core.watchParkingExtend(carId) } } }

        DraftRow(core, home.draft, reduced)

        MessageDraftRow(core, home.message, reduced)

        InboxRow(home.inbox, reduced)

        EveningRow(core, home.evening, reduced)

        if (!home.fast.running) {
            Spacer(Modifier.height(MekaSpace.m))
            FastRow(home.fast, reduced) { scope.launch { runCatching { core.watchFastButton() } } }
        }

        // Galaxy Watch completeness slice 1: what waits to sync, cross-fading as it changes.
        // Galaxy Watch completeness slice 2: quiet hours join the foot line ("Quiet hours · until 07:00").
        val syncLine = remember(sync, home) { listOfNotNull(core.watchSyncLine(), core.watchQuietLine()).joinToString("\n").ifEmpty { null } }
        AnimatedContent(
            targetState = syncLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "syncLine",
        ) { line ->
            if (line != null) {
                Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
                    Line(line, MekaType.caption.copy(color = C.textTertiary), maxLines = 4)
                }
            }
        }

        PauseRow(core, reduced)

        UnlinkRow(app, core, sync, reduced)
    }
}

/**
 * "What Navilife may do" completeness: the pause switch on the wrist. Running, the foot offers **Pause Navilife**
 * (secondary); paused, the line "Paused since 21:40 · Navilife asks before everything" in the accent with **Resume
 * Navilife** (filled, a confirm buzz). The same synced setting as the phone's page, so it holds everywhere after the
 * next sync. The states cross-fade.
 */
@Composable
private fun PauseRow(core: MekaCore, reduced: Boolean) {
    val v by core.autonomyView.collectAsState()
    val scope = rememberCoroutineScope()
    AnimatedContent(
        targetState = v.todayLine,
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "pause",
    ) { paused ->
        Column(
            Modifier.fillMaxWidth().padding(top = MekaSpace.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            if (paused != null) {
                Line(paused, MekaType.caption.copy(color = C.accent), maxLines = 3)
                WatchButton("Resume Navilife", primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) {
                    scope.launch { runCatching { core.setAutonomyPaused(false) } }
                }
            } else {
                WatchButton("Pause Navilife", primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                    scope.launch { runCatching { core.setAutonomyPaused(true) } }
                }
            }
        }
    }
}

/**
 * Galaxy Watch completeness slice 1: "Unlink this watch" at the foot of the day. A tap unfolds the question (what it
 * forgets, and that changes not yet synced would be lost) with **Unlink** and **Keep**; offline it says it needs a
 * connection and offers only Keep. Unlink sends what waits, revokes the watch's key on Navilife's server, then the
 * watch forgets its day and key and shows a new code. The row unfolds on the expand spring (reduced motion: a fade).
 */
@Composable
private fun UnlinkRow(app: WatchApplication, core: MekaCore, sync: SyncStatus, reduced: Boolean) {
    val R = os.meka.core.domain.WatchUnlinkRules
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        if (!asking) {
            WatchButton(R.OFFER, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                said = null; asking = true
            }
        }
        AnimatedVisibility(
            visible = asking,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
            label = "unlink",
        ) {
            val ask = remember(sync) { core.watchUnlinkAsk() }
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(ask.title, ask.line, ask.loss, ask.blocked, said).joinToString(". ")
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                Line(ask.title, MekaType.metaStrong.copy(color = C.textPrimary), maxLines = 1)
                Line(ask.line, MekaType.caption.copy(color = C.textSecondary), maxLines = 4)
                ask.loss?.let { Line(it, MekaType.caption.copy(color = C.critical), maxLines = 3) }
                (said ?: ask.blocked)?.let { Line(it, MekaType.caption.copy(color = C.textTertiary), maxLines = 3) }
                Spacer(Modifier.height(MekaSpace.xxs))
                if (ask.blocked == null) {
                    WatchButton(if (working) R.WORKING else R.UNLINK, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) {
                        if (working) return@WatchButton
                        working = true
                        scope.launch {
                            val gone = runCatching { core.unlinkThisWatch() }.getOrDefault(false)
                            working = false
                            if (gone) app.forgetAndRelink() else said = R.failed(core.syncStatus.value is SyncStatus.Offline)
                        }
                    }
                }
                WatchButton(R.KEEP, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                    asking = false; said = null
                }
            }
        }
    }
}

/** What the draft row says after a tap: the line, whether Undo is offered, and for how long. */
private data class DraftSaid(val line: String, val undo: Boolean, val ms: Long, val at: Long = System.nanoTime())

/**
 * Drafted replies, slice 4: Navilife's newest draft on the wrist — "DRAFTED REPLY · 1 OF 2", who asked and what they
 * want, the draft itself, and **Send** (filled, a confirm buzz) and **Not needed**. Nothing goes without his tap. A
 * draft too long to read whole here offers only Not needed ("Long draft · send it from your phone"). After Send the row
 * says "Sending to Sam · Undo stops it" with Undo for 15 s, inside the server's 20-second hold; after Not needed, "Draft
 * put away" with Undo for 5 s. The row unfolds on the expand spring (reduced motion: a fade), its states cross-fade,
 * and it folds away when no draft waits.
 */
@Composable
private fun DraftRow(core: MekaCore, draft: os.meka.core.domain.WatchDraft?, reduced: Boolean) {
    val R = os.meka.core.domain.WatchDraftRules
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<DraftSaid?>(null) }
    LaunchedEffect(said) {
        val s = said ?: return@LaunchedEffect
        delay(s.ms)
        if (said == s) said = null
    }
    // The last draft shown, so the row folds away still showing it (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchDraft>(1) }
    if (draft != null) last[0] = draft
    AnimatedVisibility(
        visible = draft != null || said != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "draft",
    ) {
        AnimatedContent(
            targetState = said ?: draft ?: last[0],
            contentKey = { when (it) { is DraftSaid -> "said" + it.at; is os.meka.core.domain.WatchDraft -> it.mailId; else -> "" } },
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "draftState",
        ) { state ->
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                when (state) {
                    is DraftSaid -> {
                        Line(state.line, MekaType.caption.copy(color = C.textPrimary), maxLines = 3)
                        if (state.undo) {
                            WatchButton(R.UNDO, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                                scope.launch {
                                    runCatching { core.watchDraftUndo() }
                                    said = DraftSaid(R.TAKEN_BACK, undo = false, ms = R.LINE_MS)
                                }
                            }
                        }
                    }
                    is os.meka.core.domain.WatchDraft -> {
                        Column(
                            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = state.spoken },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                        ) {
                            Line(state.label, MekaType.captionStrong.copy(color = C.accent, fontFeatureSettings = "tnum"), maxLines = 1)
                            Line(state.from, MekaType.metaStrong.copy(color = C.textPrimary), maxLines = 2)
                            state.wants?.let { Line(it, MekaType.caption.copy(color = C.textSecondary), maxLines = 3) }
                            Line(state.draft, MekaType.caption.copy(color = C.textPrimary), maxLines = 14)
                            Line(state.note, MekaType.caption.copy(color = C.textTertiary), maxLines = 2)
                        }
                        Spacer(Modifier.height(MekaSpace.xxs))
                        if (state.canSend) {
                            WatchButton(R.SEND, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) {
                                scope.launch {
                                    val (line, sending) = runCatching { core.watchSendDraft(state.mailId) }.getOrElse { R.GONE to false }
                                    said = DraftSaid(line, undo = sending, ms = if (sending) R.UNDO_MS else R.LINE_MS)
                                }
                            }
                        }
                        WatchButton(R.NOT_NEEDED, primary = !state.canSend, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                            scope.launch {
                                val line = runCatching { core.watchDismissDraft(state.mailId) }.getOrElse { R.GONE }
                                said = DraftSaid(line, undo = line == R.PUT_AWAY, ms = R.LINE_MS)
                            }
                        }
                        state.more?.let { Line(it, MekaType.caption.copy(color = C.textTertiary), maxLines = 1) }
                    }
                }
            }
        }
    }
}

/**
 * Galaxy Watch: approve message drafts from the wrist, slice 1. Navilife's newest drafted WhatsApp or text reply —
 * "MESSAGE REPLY · 1 OF 2", who wrote and what they want, the draft itself — with **Send** (filled, a confirm buzz) while
 * the Fold still holds the message: his tap asks the phone, which sends it through the message's own Reply. Otherwise
 * the draft stays and the row says why ("Needs your phone", "Couldn't send…" in the warning colour). Edit and Not now
 * stay on the phone. After Send the row says "Sending from your phone…" until the card leaves. The row unfolds on the
 * expand spring (reduced motion: a fade), its states cross-fade, and it folds away when no message draft waits.
 */
@Composable
private fun MessageDraftRow(core: MekaCore, draft: os.meka.core.domain.WatchMessageDraft?, reduced: Boolean) {
    val R = os.meka.core.domain.WatchMessageDraftRules
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<DraftSaid?>(null) }
    LaunchedEffect(said) {
        val s = said ?: return@LaunchedEffect
        delay(s.ms)
        if (said == s) said = null
    }
    // The last draft shown, so the row folds away still showing it (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchMessageDraft>(1) }
    if (draft != null) last[0] = draft
    AnimatedVisibility(
        visible = draft != null || said != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "messageDraft",
    ) {
        AnimatedContent(
            targetState = said ?: draft ?: last[0],
            contentKey = {
                when (it) {
                    is DraftSaid -> "said" + it.at
                    is os.meka.core.domain.WatchMessageDraft -> it.messageId + it.note
                    else -> ""
                }
            },
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "messageDraftState",
        ) { state ->
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                when (state) {
                    is DraftSaid -> Line(state.line, MekaType.caption.copy(color = C.textPrimary), maxLines = 3)
                    is os.meka.core.domain.WatchMessageDraft -> {
                        Column(
                            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = state.spoken },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                        ) {
                            Line(state.label, MekaType.captionStrong.copy(color = C.accent, fontFeatureSettings = "tnum"), maxLines = 1)
                            Line(state.from, MekaType.metaStrong.copy(color = C.textPrimary), maxLines = 2)
                            state.wants?.let { Line(it, MekaType.caption.copy(color = C.textSecondary), maxLines = 3) }
                            Line(state.draft, MekaType.caption.copy(color = C.textPrimary), maxLines = 14)
                            Line(state.note, MekaType.caption.copy(color = if (state.warn) C.critical else C.textTertiary), maxLines = 3)
                        }
                        if (state.canSend) {
                            Spacer(Modifier.height(MekaSpace.xxs))
                            WatchButton(R.SEND, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) {
                                scope.launch {
                                    val line = runCatching { core.watchSendMessageDraft(state.messageId) }.getOrElse { R.NOT_NOW }
                                    said = DraftSaid(line, undo = false, ms = R.LINE_MS)
                                }
                            }
                        }
                        Line(R.EDIT_HINT, MekaType.caption.copy(color = C.textTertiary), maxLines = 1)
                        state.more?.let { Line(it, MekaType.caption.copy(color = C.textTertiary), maxLines = 1) }
                    }
                }
            }
        }
    }
}

/**
 * Inbox autopilot, slice 7b-2: how many emails need him, and nothing of what they say. It unfolds on the expand spring
 * when the first email arrives and folds away once the inbox is clear (reduced motion: a fade); lit in the accent from
 * the ten-minute inbox's moment, with its streak. Going through them stays on the phone and the Mac.
 */
@Composable
private fun InboxRow(inbox: os.meka.core.domain.WatchInbox?, reduced: Boolean) {
    // The last count shown, so the row folds away still showing it (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchInbox>(1) }
    if (inbox != null) last[0] = inbox
    AnimatedVisibility(
        visible = inbox != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "inbox",
    ) {
        val v = inbox ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = MekaSpace.m)
                .semantics(mergeDescendants = true) { contentDescription = v.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Line(v.label, MekaType.captionStrong.copy(color = if (v.lit) C.accent else C.textSecondary), maxLines = 1)
            AnimatedContent(
                targetState = v.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "inboxCount",
            ) { line -> Line(line, MekaType.caption.copy(color = C.textPrimary, fontFeatureSettings = "tnum")) }
            v.streakLine?.let { Line(it, MekaType.caption.copy(color = C.textTertiary)) }
        }
    }
}

/** How long "Done · Buy milk" with Undo stays after a tick on the wrist. */
private const val TOP_UNDO_MS = 5_000L

/**
 * Week board, the watch ("Watch: today's top three only"): under the now card, "TODAY" with "2 done · 5 to go" and the
 * day's next three open tasks in his Board order (carried ones first, their line in the accent), "+4 more on your
 * phone" under them. A tap on a row's ring fills it with the accent on the complete spring with a confirm buzz and
 * ticks the task off (the same Done as the phone's); the list closes up and "Done · Buy milk" with **Undo** stays for
 * 5 s (Undo reopens it). The section unfolds on the expand spring; rows cross-fade as the list changes (reduced
 * motion: fades, the ring fills at once).
 */
@Composable
private fun TopThreeRows(core: MekaCore, top: os.meka.core.domain.WatchTopThree?, reduced: Boolean) {
    val R = os.meka.core.domain.WatchTopThreeRules
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchTopThree>(1) }
    if (top != null) last[0] = top
    val scope = rememberCoroutineScope()
    var undo by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(TOP_UNDO_MS); undo = null } }
    AnimatedVisibility(
        visible = top != null || undo != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "topThree",
    ) {
        val v = top ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().padding(top = MekaSpace.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = v.spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Line(v.label, MekaType.captionStrong.copy(color = C.textSecondary), maxLines = 1)
                AnimatedContent(
                    targetState = v.countLine,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "topCount",
                ) { line -> Line(line, MekaType.caption.copy(color = C.textTertiary, fontFeatureSettings = "tnum"), maxLines = 1) }
            }
            AnimatedContent(
                targetState = if (top == null) emptyList() else v.rows,
                contentKey = { rows -> rows.joinToString("\u0000") { it.taskId } },
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "topRows",
            ) { rows ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    for (r in rows) {
                        TopRow(r, reduced) {
                            undo = r.taskId to R.doneLine(r.title)
                            scope.launch { runCatching { core.complete(r.taskId) } }
                        }
                    }
                }
            }
            v.moreLine?.let { Line(it, MekaType.caption.copy(color = C.textTertiary), maxLines = 1) }
            AnimatedVisibility(
                visible = undo != null,
                enter = fadeIn(MekaMotion.appear(reduced)),
                exit = fadeOut(MekaMotion.appear(reduced)),
                label = "topUndo",
            ) {
                val u = undo
                Column(
                    Modifier.fillMaxWidth().padding(top = MekaSpace.xxs).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    if (u != null) {
                        Line(u.second, MekaType.caption.copy(color = C.textSecondary))
                        WatchButton(R.UNDO, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                            val id = u.first
                            undo = null
                            scope.launch { runCatching { core.reopen(id) } }
                        }
                    }
                }
            }
        }
    }
}

/** One of the top three: a ring to tick it off (48 dp to touch), its title and line, left aligned beside it. */
@Composable
private fun TopRow(r: os.meka.core.domain.WatchTopRow, reduced: Boolean, onDone: () -> Unit) {
    val view = LocalView.current
    var ticked by remember(r.taskId) { mutableStateOf(false) }
    val fill by animateFloatAsState(
        targetValue = if (ticked) 1f else 0f,
        animationSpec = MekaMotion.complete(reduced),
        label = "tick",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MekaSpace.touch)
            .clickable(enabled = !ticked, onClickLabel = "Done") {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                ticked = true
                onDone()
            }
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(r.title, r.line).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 1.5.dp.toPx()
            drawCircle(color = C.accent.copy(alpha = 0.55f + 0.45f * fill), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            if (fill > 0f) drawCircle(color = C.accent, radius = (size.minDimension / 2 - stroke) * fill)
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                text = r.title,
                style = MekaType.caption.copy(color = if (ticked) C.textTertiary else C.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            r.line?.let {
                BasicText(
                    text = it,
                    style = MekaType.micro.copy(color = if (r.carried) C.accent else C.textTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Done for the day, completeness slice 2: the evening glance — "DONE FOR THE DAY" (accent), "6 done · 2 left for
 * tomorrow", tomorrow's first commitment, and **Shut down** (filled, a confirm buzz) and **Later** while it can be put
 * off; the same taps as the phone's pane, synced to every device. After a tap the row says what it did ("Day shut down ·
 * see you tomorrow", "Shut down the day comes back at 21:15") for a few seconds; once shut down it reads "DAY SHUT DOWN"
 * until midnight. It unfolds on the expand spring (reduced motion: a fade); lines cross-fade.
 */
@Composable
private fun EveningRow(core: MekaCore, evening: os.meka.core.domain.WatchEvening?, reduced: Boolean) {
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchEvening>(1) }
    if (evening != null) last[0] = evening
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(EVENING_SAID_MS); said = null } }
    AnimatedVisibility(
        visible = evening != null || said != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "evening",
    ) {
        val v = evening ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = MekaSpace.m)
                .semantics(mergeDescendants = true) { contentDescription = said ?: v.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Line(v.label, MekaType.captionStrong.copy(color = if (v.lit) C.accent else C.textSecondary), maxLines = 1)
            AnimatedContent(
                targetState = said ?: v.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "eveningLine",
            ) { line -> Line(line, MekaType.caption.copy(color = C.textPrimary, fontFeatureSettings = "tnum")) }
            if (said == null) v.note?.let { Line(it, MekaType.caption.copy(color = C.textTertiary)) }
            if (said == null && v.canShutDown) {
                Spacer(Modifier.height(MekaSpace.xxs))
                WatchButton(os.meka.core.domain.WatchEveningRules.SHUT_DOWN, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) {
                    scope.launch { said = runCatching { core.watchShutDown() }.getOrNull() }
                }
                if (v.canLater) {
                    WatchButton(os.meka.core.domain.WatchEveningRules.LATER, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                        scope.launch { said = runCatching { core.watchShutdownLater() }.getOrNull() }
                    }
                }
            }
        }
    }
}

/** How long the evening row says what a tap did before it shows the glance (or folds away) again. */
private const val EVENING_SAID_MS = 4_000L

/**
 * The commute counts everywhere, completeness: on an office day's commute, when to leave — "LEAVE BY 07:32 · IN 12
 * MIN", "For the 07:42 · on time" and what's wrong on the route, lit in the accent for trouble or the last ten minutes;
 * "ON THE 07:42" once he's on it; "HOME ABOUT 18:50" in the evening. It unfolds on the expand spring (reduced motion: a
 * fade) when the commute starts and folds away when it ends; the label and line cross-fade as the minutes count down.
 */
@Composable
private fun CommuteRow(commute: os.meka.core.domain.WatchCommute?, reduced: Boolean) {
    // The last row shown, so it folds away still showing itself (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchCommute>(1) }
    if (commute != null) last[0] = commute
    AnimatedVisibility(
        visible = commute != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "commute",
    ) {
        val v = commute ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = MekaSpace.m)
                .semantics(mergeDescendants = true) { contentDescription = v.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            AnimatedContent(
                targetState = v.label,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "commuteWhen",
            ) { label ->
                Line(label, MekaType.captionStrong.copy(color = if (v.lit) C.accent else C.textSecondary, fontFeatureSettings = "tnum"), maxLines = 1)
            }
            AnimatedContent(
                targetState = v.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "commuteLine",
            ) { line -> Line(line, MekaType.metaStrong.copy(color = C.textPrimary, fontFeatureSettings = "tnum"), maxLines = 2) }
            v.note?.let { Line(it, MekaType.caption.copy(color = C.accent), maxLines = 3) }
        }
    }
}

/**
 * Bin days, completeness: the evening before a collection, from 16:00 until midnight — "OUT TONIGHT" · "Black bin and
 * food caddy", the label lit in the accent from his reminder's time; a moved or dropped collection says so. It unfolds
 * on the expand spring (reduced motion: a fade) and folds away at midnight; the label cross-fades when it lights.
 */
@Composable
private fun BinsRow(bins: os.meka.core.domain.WatchBins?, reduced: Boolean) {
    // The last row shown, so it folds away still showing itself (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchBins>(1) }
    if (bins != null) last[0] = bins
    AnimatedVisibility(
        visible = bins != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "bins",
    ) {
        val v = bins ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = MekaSpace.m)
                .semantics(mergeDescendants = true) { contentDescription = v.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            AnimatedContent(
                targetState = v.lit,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "binsLit",
            ) { lit -> Line(v.label, MekaType.captionStrong.copy(color = if (lit) C.accent else C.textSecondary), maxLines = 1) }
            Line(v.line, MekaType.metaStrong.copy(color = C.textPrimary), maxLines = 2)
            v.note?.let { Line(it, MekaType.caption.copy(color = C.textSecondary), maxLines = 3) }
        }
    }
}

/**
 * Meeting prep, slice 3: from his lead time before a meeting with other people until ten minutes after its start —
 * "PREP · IN 12 MIN", the meeting and its time, who's coming and the one or two most useful lines (last email, what's
 * open, when you last met). It unfolds on the expand spring (reduced motion: a fade) and folds away after the start;
 * the countdown cross-fades each minute. The heads-up's buzz is the phone's, which Wear OS brings to the wrist.
 */
@Composable
private fun PrepRow(prep: os.meka.core.domain.WatchPrep?, reduced: Boolean) {
    // The last prep shown, so the row folds away still showing it (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchPrep>(1) }
    if (prep != null) last[0] = prep
    AnimatedVisibility(
        visible = prep != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "prep",
    ) {
        val v = prep ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = MekaSpace.m)
                .semantics(mergeDescendants = true) { contentDescription = v.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            AnimatedContent(
                targetState = v.label,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "prepWhen",
            ) { label -> Line(label, MekaType.captionStrong.copy(color = C.accent, fontFeatureSettings = "tnum"), maxLines = 1) }
            Line(v.title, MekaType.metaStrong.copy(color = C.textPrimary), maxLines = 2)
            if (v.who.isNotEmpty()) Line(v.who, MekaType.caption.copy(color = C.textSecondary), maxLines = 2)
            for (l in v.lines) Line(l, MekaType.caption.copy(color = C.textTertiary), maxLines = 2)
        }
    }
}

/**
 * Barça match day, slice 4: the match from the hour before kick-off — "BARÇA · 20:00", "PSG v Barça", where it's on —
 * then the live score with the last goal and full time. It unfolds on the expand spring (reduced motion: a fade) and
 * folds away in the evening; the score cross-fades when a goal comes in (the buzz is the phone's heads-up, which Wear
 * OS brings to the watch). Lit in the accent until full time.
 */
@Composable
private fun MatchRow(match: os.meka.core.domain.WatchMatch?, reduced: Boolean) {
    // The last match shown, so the row folds away still showing it (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<os.meka.core.domain.WatchMatch>(1) }
    if (match != null) last[0] = match
    AnimatedVisibility(
        visible = match != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        label = "match",
    ) {
        val v = match ?: last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = MekaSpace.m)
                .semantics(mergeDescendants = true) { contentDescription = v.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Line(v.label, MekaType.captionStrong.copy(color = if (v.lit) C.accent else C.textSecondary), maxLines = 1)
            AnimatedContent(
                targetState = v.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "matchScore",
            ) { line -> Line(line, MekaType.metaStrong.copy(color = C.textPrimary, fontFeatureSettings = "tnum")) }
            v.note?.let { Line(it, MekaType.caption.copy(color = C.textSecondary)) }
        }
    }
}

/**
 * Car and parking, slice 7: each car's parking timer — "PARKING" (· THE GOLF), "Paid until 15:30 · 40 min left" with
 * the note, and +15 min. A timer unfolds on the expand spring when one is set (reduced motion: a fade) and folds away
 * two hours after it ends; the line cross-fades as the minutes count down; lit in the accent for the last 10 minutes.
 */
@Composable
private fun ParkingRows(rows: List<os.meka.core.domain.WatchParking>, reduced: Boolean, onExtend: (String) -> Unit) {
    // The last rows shown, so a timer folds away still showing itself (not state: nothing reads it to recompose).
    val last = remember { mutableMapOf<String, os.meka.core.domain.WatchParking>() }
    rows.forEach { last[it.carId] = it }
    for (carId in os.meka.core.domain.CarRules.CAR_IDS) {
        val p = rows.firstOrNull { it.carId == carId }
        AnimatedVisibility(
            visible = p != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
            label = "parking",
        ) {
            val v = p ?: last[carId] ?: return@AnimatedVisibility
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = v.spoken },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    Line(v.label, MekaType.captionStrong.copy(color = if (v.lit) C.accent else C.textSecondary), maxLines = 1)
                    AnimatedContent(
                        targetState = v.line,
                        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                        label = "parkingLeft",
                    ) { line -> Line(line, MekaType.caption.copy(color = C.textPrimary, fontFeatureSettings = "tnum")) }
                    v.note?.let { Line(it, MekaType.caption.copy(color = C.textTertiary), maxLines = 1) }
                }
                v.button?.let { label ->
                    WatchButton(label, primary = v.lit, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) { onExtend(v.carId) }
                }
            }
        }
    }
}

/**
 * The fast, in the Fasting design (docs/design/fasting/today-card.dc.html): running, a gold ring sweeping over a dark
 * track towards the goal with its 4h/8h/12h marks (passed ones dark in the gold), a glowing tip that breathes (2.4 s,
 * still under reduced motion) and the time in the middle; under it "FASTING" over the stage it's in, "Ends in … · …"
 * in gold and End. Resting: "Not fasting", the eating window's line and Start. The ring sweeps in when it appears.
 */
@Composable
private fun FastRow(f: os.meka.core.domain.WatchFast, reduced: Boolean, onButton: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = f.progress,
        animationSpec = MekaMotion.appear(reduced),
        label = "fastRing",
    )
    if (f.running) {
        val breath = if (reduced || !f.tip) 0.8f else {
            val t = rememberInfiniteTransition(label = "fastTip")
            val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "fastTipA")
            a
        }
        val gold = if (f.reached) C.success else C.accent
        val light = androidx.compose.ui.graphics.lerp(gold, androidx.compose.ui.graphics.Color.White, 0.4f)
        Box(Modifier.size(RING).semantics { contentDescription = f.spoken }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = RING_STROKE.toPx()
                val r = size.minDimension / 2 - w / 2 - 3.dp.toPx()
                val tl = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r)
                val arc = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                drawArc(C.surfaceRaised, 0f, 360f, useCenter = false, topLeft = tl, size = arc, style = Stroke(w))
                if (progress > 0f) {
                    drawArc(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(light, gold), start = tl, end = androidx.compose.ui.geometry.Offset(tl.x + arc.width, tl.y + arc.height),
                        ),
                        -90f, 360f * progress, useCenter = false, topLeft = tl, size = arc, style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
                fun at(fraction: Float): androidx.compose.ui.geometry.Offset {
                    val a = fraction * 2 * Math.PI - Math.PI / 2
                    return androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(a).toFloat() * r, center.y + kotlin.math.sin(a).toFloat() * r)
                }
                f.marks.forEach { m ->
                    val p = at(m.fraction)
                    drawCircle(if (m.passed) C.onAccent else C.background, 2.5.dp.toPx(), p)
                    drawCircle(if (m.passed) C.onAccent else C.textTertiary.copy(alpha = 0.6f), 1.5.dp.toPx(), p)
                }
                if (f.tip && progress > 0f) {
                    val p = at(progress.coerceAtMost(1f))
                    drawCircle(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(light.copy(alpha = 0.55f * breath), androidx.compose.ui.graphics.Color.Transparent), p, 10.dp.toPx(),
                        ),
                        10.dp.toPx(), p,
                    )
                    drawCircle(androidx.compose.ui.graphics.lerp(light, androidx.compose.ui.graphics.Color.White, 0.5f), 4.5.dp.toPx(), p)
                }
            }
            BasicText(f.clock, style = MekaType.itemTitle.copy(color = C.textPrimary, fontFeatureSettings = "tnum"), maxLines = 1)
        }
        Line(f.title.uppercase(), MekaType.captionStrong.copy(color = C.textSecondary), maxLines = 1)
        if (f.stage.isNotBlank()) Line(f.stage, MekaType.metaStrong.copy(color = C.textPrimary), maxLines = 1)
        if (f.line.isNotBlank()) Line(f.line, MekaType.caption.copy(color = gold), maxLines = 1)
    } else {
        Line(f.title, MekaType.captionStrong.copy(color = C.textPrimary), maxLines = 1)
        if (f.line.isNotBlank()) Line(f.line, MekaType.caption.copy(color = C.textSecondary))
    }
    Spacer(Modifier.height(MekaSpace.xxs))
    WatchButton(
        f.button, primary = !f.running, reduced = reduced,
        haptic = if (f.running) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONFIRM,
        onClick = onButton,
    )
}


// ---- quick capture by voice (Galaxy Watch, slice 4a) ----

private sealed class Capture {
    data object Idle : Capture()
    data class Listening(val heard: String?) : Capture()
    data class Done(val captured: WatchCaptured) : Capture()
    data class Said(val line: String) : Capture()
    /** Voice notes into actions, slice 4: a voice note being written down ([partial]: the stretch being heard now). */
    data class Recording(val segments: List<String>, val partial: String?, val paused: Boolean) : Capture()
    data object Reading : Capture()
    data class Noted(val noted: WatchVoiceNoted) : Capture()
}

/**
 * Capture: tap, say it, and it goes in as if typed into Today's capture bar (a task, or "timer 20 min"). The watch's
 * on-device recogniser only ([WatchListener]); the microphone is asked the first time. What it did shows for five
 * seconds with Undo; the block cross-fades between its states. [listen] is set when the tile's Capture opened MEKA:
 * it starts listening as if Capture was tapped here, and [onListen] clears it.
 *
 * Voice notes into actions, slice 4: **🎙 Voice note** under Capture (or "take a voice note" said to Capture) writes a
 * ramble down with the same on-device recogniser, listening again after each pause until Done (five minutes at most,
 * or a minute with no words); nothing is recorded. The words go to the AI's tidy-up and its cards wait in Needs you on
 * the phone; offline they're kept as one task with Undo ([MekaCore.watchVoiceNote]).
 */
@Composable
private fun CaptureBlock(core: MekaCore, reduced: Boolean, listen: Int, onListen: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Capture>(Capture.Idle) }

    // The voice note's clock: time recorded before the last Resume, when the current stretch started (null: paused),
    // when words were last heard, and when the note started (its "Voice note · 14:02").
    var noteStartedAt by remember { mutableLongStateOf(0L) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    var runningSince by remember { mutableStateOf<Long?>(null) }
    var lastWordsAt by remember { mutableLongStateOf(0L) }
    var clock by remember { mutableLongStateOf(0L) }
    fun elapsed(now: Long = System.currentTimeMillis()): Long = recordedMs + (runningSince?.let { now - it } ?: 0L)

    // Set once the voice note's listener exists (it and Capture's listener refer to each other's actions).
    val voice = remember { arrayOfNulls<WatchListener>(1) }
    val beginNote = remember { arrayOfNulls<() -> Unit>(1) }

    val listener = remember(core) {
        WatchListener(
            context,
            onPartial = { heard -> if (state is Capture.Listening) state = Capture.Listening(heard) },
            onHeard = { heard ->
                if (WatchVoiceNoteRules.asks(heard)) {
                    beginNote[0]?.invoke()
                } else scope.launch {
                    val c = runCatching { core.watchCapture(heard) }.getOrNull()
                    state = if (c == null) {
                        Capture.Said(WatchCaptureRules.failLine(WatchListenFailure.NOT_HEARD))
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        Capture.Done(c)
                    }
                }
            },
            onFail = { f -> state = Capture.Said(WatchCaptureRules.failLine(f)) },
        )
    }

    /** Done (or the five minutes, or a minute of quiet): the words so far go to the tidy-up. */
    fun finishNote() {
        val r = state as? Capture.Recording ?: return
        voice[0]?.stop()
        recordedMs = elapsed(); runningSince = null
        val words = WatchVoiceNoteRules.words(r.segments, r.partial)
        if (words.isEmpty()) { state = Capture.Said(WatchVoiceNoteRules.NOTHING_HEARD); return }
        state = Capture.Reading
        scope.launch {
            val v = runCatching { core.watchVoiceNote(words, noteStartedAt) }.getOrNull()
            if (v != null) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            state = if (v == null) Capture.Said(WatchCaptureRules.failLine(WatchListenFailure.FAILED)) else Capture.Noted(v)
        }
    }

    /** After a stretch ends: listen again, or end the note by itself (five minutes, a minute of quiet). */
    fun nextStretch() {
        val r = state as? Capture.Recording ?: return
        val now = System.currentTimeMillis()
        if (WatchVoiceNoteRules.listenAgain(elapsed(now), r.paused, stopped = false, quietMs = now - lastWordsAt)) {
            voice[0]?.start()
        } else if (!r.paused) finishNote()
    }

    val noteListener = remember(core) {
        WatchListener(
            context,
            onPartial = { heard ->
                (state as? Capture.Recording)?.takeIf { !it.paused }?.let {
                    state = it.copy(partial = heard); lastWordsAt = System.currentTimeMillis()
                }
            },
            onHeard = { heard ->
                (state as? Capture.Recording)?.let {
                    state = it.copy(segments = it.segments + heard, partial = null)
                    lastWordsAt = System.currentTimeMillis()
                    nextStretch()
                }
            },
            onFail = { f ->
                val r = state as? Capture.Recording
                if (r != null) when {
                    f == WatchListenFailure.NOT_HEARD -> nextStretch()
                    WatchVoiceNoteRules.words(r.segments, r.partial).isNotEmpty() -> finishNote()
                    else -> { recordedMs = 0L; runningSince = null; state = Capture.Said(WatchCaptureRules.failLine(f)) }
                }
            },
            quickEnd = false,
        )
    }.also { voice[0] = it }

    DisposableEffect(listener, noteListener) { onDispose { listener.stop(); noteListener.stop() } }
    LifecycleStartEffect(listener, noteListener) {
        onStopOrDispose {
            listener.stop()
            if (state is Capture.Listening) state = Capture.Idle
            // The screen went: what was said so far is read rather than lost.
            if (state is Capture.Recording) finishNote()
        }
    }

    fun startNote() {
        listener.stop()
        val now = System.currentTimeMillis()
        noteStartedAt = now; recordedMs = 0L; runningSince = now; lastWordsAt = now; clock = now
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        state = Capture.Recording(emptyList(), null, paused = false)
        noteListener.start()
    }
    beginNote[0] = { startNote() }

    fun pauseOrResume() {
        val r = state as? Capture.Recording ?: return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (r.paused) {
            val now = System.currentTimeMillis()
            runningSince = now; lastWordsAt = now
            state = r.copy(paused = false)
            noteListener.start()
        } else {
            noteListener.stop()
            recordedMs = elapsed(); runningSince = null
            // The stretch being heard is kept as it stands (the recogniser is let go).
            state = r.copy(segments = r.segments + listOfNotNull(r.partial?.takeIf { it.isNotBlank() }), partial = null, paused = true)
        }
    }

    fun cancelNote() {
        noteListener.stop()
        recordedMs = 0L; runningSince = null
        state = Capture.Said(WatchVoiceNoteRules.CANCELLED)
    }

    fun begin() {
        state = Capture.Listening(null)
        listener.start()
    }
    // Which the microphone was asked for: Capture (false) or a voice note (true).
    var askedForNote by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            !granted -> state = Capture.Said(WatchCaptureRules.failLine(WatchListenFailure.NO_MIC))
            askedForNote -> startNote()
            else -> begin()
        }
    }
    fun micAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun tapCapture() {
        if (micAllowed()) begin() else { askedForNote = false; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }
    fun tapVoiceNote() {
        if (micAllowed()) startNote() else { askedForNote = true; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    LaunchedEffect(listen) { if (listen > 0) { onListen(); tapCapture() } }
    // What it did stays for five seconds with Undo; a failure's line for four; a voice note's result for eight; then
    // the button comes back.
    LaunchedEffect(state) {
        when (state) {
            is Capture.Done -> { delay(WatchCaptureRules.UNDO_MS); state = Capture.Idle }
            is Capture.Said -> { delay(WatchCaptureRules.FAIL_MS); state = Capture.Idle }
            is Capture.Noted -> { delay(WatchVoiceNoteRules.RESULT_MS); state = Capture.Idle }
            else -> Unit
        }
    }
    // While a voice note runs: the clock ticks, it ends by itself at five minutes, and the screen stays on.
    val recording = state is Capture.Recording
    LaunchedEffect(recording) {
        while (recording) {
            val now = System.currentTimeMillis()
            clock = now
            if ((state as? Capture.Recording)?.paused == false && os.meka.core.domain.VoiceNoteRules.atLimit(elapsed(now))) {
                finishNote(); break
            }
            delay(NOTE_TICK_MS)
        }
    }
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "capture",
    ) { s ->
        Column(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            when (s) {
                Capture.Idle -> {
                    WatchButton(
                        WatchCaptureRules.BUTTON, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK,
                    ) { tapCapture() }
                    VoiceNoteButton(reduced) { tapVoiceNote() }
                }
                is Capture.Listening -> {
                    Line(WatchCaptureRules.LISTENING, MekaType.captionStrong.copy(color = C.accent), maxLines = 1)
                    Line(s.heard ?: WatchCaptureRules.HINT, MekaType.caption.copy(color = if (s.heard == null) C.textTertiary else C.textPrimary), maxLines = 3)
                }
                is Capture.Done -> {
                    Box(Modifier.fillMaxWidth().semantics { contentDescription = WatchCaptureRules.spoken(s.captured) }) {
                        Line(s.captured.line, MekaType.caption.copy(color = C.textPrimary), maxLines = 3)
                    }
                    WatchButton(WatchCaptureRules.UNDO, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                        scope.launch {
                            runCatching { core.watchCaptureUndo(s.captured) }
                            state = Capture.Said(WatchCaptureRules.UNDONE)
                        }
                    }
                }
                is Capture.Said -> {
                    Line(s.line, MekaType.caption.copy(color = C.textSecondary), maxLines = 4)
                    WatchButton(
                        WatchCaptureRules.BUTTON, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK,
                    ) { tapCapture() }
                }
                is Capture.Recording -> {
                    val ms = elapsed(clock)
                    Row(
                        Modifier.semantics(mergeDescendants = true) { contentDescription = WatchVoiceNoteRules.recordingLine(ms, s.paused) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    ) {
                        RecordingDot(s.paused, reduced)
                        BasicText(WatchVoiceNoteRules.LABEL, style = MekaType.captionStrong.copy(color = C.accent), maxLines = 1)
                    }
                    Line(
                        WatchVoiceNoteRules.recordingLine(ms, s.paused),
                        MekaType.caption.copy(
                            color = if (WatchVoiceNoteRules.nearEnd(ms)) C.accent else C.textSecondary, fontFeatureSettings = "tnum",
                        ),
                        maxLines = 2,
                    )
                    val live = WatchVoiceNoteRules.live(s.segments, s.partial)
                    Line(live ?: WatchVoiceNoteRules.HINT, MekaType.caption.copy(color = if (live == null) C.textTertiary else C.textPrimary), maxLines = 3)
                    WatchButton(WatchVoiceNoteRules.DONE, primary = true, reduced = reduced, haptic = HapticFeedbackConstants.CONFIRM) { finishNote() }
                    WatchButton(
                        if (s.paused) WatchVoiceNoteRules.RESUME else WatchVoiceNoteRules.PAUSE,
                        primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK,
                    ) { pauseOrResume() }
                    WatchButton(WatchVoiceNoteRules.CANCEL, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) { cancelNote() }
                    Line(WatchVoiceNoteRules.NOTE, MekaType.caption.copy(color = C.textTertiary), maxLines = 2)
                }
                Capture.Reading -> {
                    Line(WatchVoiceNoteRules.LABEL, MekaType.captionStrong.copy(color = C.accent), maxLines = 1)
                    Line(WatchVoiceNoteRules.READING, MekaType.caption.copy(color = C.textSecondary), maxLines = 1)
                }
                is Capture.Noted -> {
                    val v = s.noted
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = WatchVoiceNoteRules.spoken(v) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                    ) {
                        Line(v.line, MekaType.captionStrong.copy(color = if (v.cards > 0) C.accent else C.textPrimary), maxLines = 3)
                        v.tidy?.let { Line(it, MekaType.caption.copy(color = C.textPrimary), maxLines = 4) }
                        v.peopleLine?.let { Line(it, MekaType.caption.copy(color = C.textSecondary), maxLines = 1) }
                        v.whereLine?.let { Line(it, MekaType.caption.copy(color = C.textTertiary), maxLines = 1) }
                    }
                    if (v.taskId != null) {
                        WatchButton(WatchVoiceNoteRules.UNDO, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK) {
                            scope.launch {
                                runCatching { core.watchVoiceNoteUndo(v) }
                                state = Capture.Said(WatchVoiceNoteRules.UNDONE)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val NOTE_TICK_MS = 250L
private val DOT = 8.dp

/** Voice notes into actions, slice 4: the quiet button under Capture that starts a voice note. */
@Composable
private fun VoiceNoteButton(reduced: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().semantics { contentDescription = WatchVoiceNoteRules.SPOKEN }) {
        WatchButton(WatchVoiceNoteRules.BUTTON, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK, onClick = onClick)
    }
}

/** The brass dot: breathes (0.6 → 1, 0.9 s) while a voice note is written down; still and dimmed when paused or reduced. */
@Composable
private fun RecordingDot(paused: Boolean, reduced: Boolean) {
    val t = rememberInfiniteTransition(label = "watch-voice-note-dot")
    val pulse = if (reduced || paused) 1f else t.animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "watch-voice-note-dot-pulse",
    ).value
    Box(
        Modifier.size(DOT).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = if (paused) 0.4f else 1f }
            .background(C.accent, CircleShape),
    )
}
