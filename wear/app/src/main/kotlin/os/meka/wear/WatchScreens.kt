package os.meka.wear

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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

/** The fast's ring on the wrist. */
private val RING = 64.dp
private val RING_STROKE = 4.dp

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
    val home = remember(today, fasting, tick) { core.watchHome() }
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

        Spacer(Modifier.height(MekaSpace.xs))
        CaptureBlock(core, reduced, listen, onListen)

        Spacer(Modifier.height(MekaSpace.m))
        FastRow(home.fast, reduced) { scope.launch { runCatching { core.watchFastButton() } } }

        if (sync is SyncStatus.Offline) {
            Spacer(Modifier.height(MekaSpace.xs))
            Line(WatchHomeRules.OFFLINE, MekaType.caption.copy(color = C.textTertiary))
        }
    }
}

/** The fast: a brass ring filling towards the goal with the time in it (the ring sweeps in when it appears). */
@Composable
private fun FastRow(f: os.meka.core.domain.WatchFast, reduced: Boolean, onButton: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = f.progress,
        animationSpec = MekaMotion.appear(reduced),
        label = "fastRing",
    )
    if (f.running) {
        Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = RING_STROKE.toPx()
                val inset = w / 2
                val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(C.hairline, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(w))
                drawArc(
                    if (f.reached) C.success else C.accent, -90f, 360f * progress, useCenter = false,
                    topLeft = topLeft, size = arc, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            BasicText(f.clock, style = MekaType.metaStrong.copy(color = C.textPrimary, fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
    Line(f.title, MekaType.captionStrong.copy(color = C.textPrimary), maxLines = 1)
    if (f.line.isNotBlank()) Line(f.line, MekaType.caption.copy(color = C.textSecondary))
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
}

/**
 * Capture: tap, say it, and it goes in as if typed into Today's capture bar (a task, or "timer 20 min"). The watch's
 * on-device recogniser only ([WatchListener]); the microphone is asked the first time. What it did shows for five
 * seconds with Undo; the block cross-fades between its states. [listen] is set when the tile's Capture opened MEKA:
 * it starts listening as if Capture was tapped here, and [onListen] clears it.
 */
@Composable
private fun CaptureBlock(core: MekaCore, reduced: Boolean, listen: Int, onListen: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Capture>(Capture.Idle) }

    val listener = remember(core) {
        WatchListener(
            context,
            onPartial = { heard -> if (state is Capture.Listening) state = Capture.Listening(heard) },
            onHeard = { heard ->
                scope.launch {
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
    DisposableEffect(listener) { onDispose { listener.stop() } }
    LifecycleStartEffect(listener) { onStopOrDispose { listener.stop(); if (state is Capture.Listening) state = Capture.Idle } }

    fun begin() {
        state = Capture.Listening(null)
        listener.start()
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else state = Capture.Said(WatchCaptureRules.failLine(WatchListenFailure.NO_MIC))
    }
    fun tapCapture() {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed) begin() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(listen) { if (listen > 0) { onListen(); tapCapture() } }
    // What it did stays for five seconds with Undo; a failure's line for four; then the button comes back.
    LaunchedEffect(state) {
        when (state) {
            is Capture.Done -> { delay(WatchCaptureRules.UNDO_MS); state = Capture.Idle }
            is Capture.Said -> { delay(WatchCaptureRules.FAIL_MS); state = Capture.Idle }
            else -> Unit
        }
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
                Capture.Idle -> WatchButton(
                    WatchCaptureRules.BUTTON, primary = false, reduced = reduced, haptic = HapticFeedbackConstants.CLOCK_TICK,
                ) { tapCapture() }
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
            }
        }
    }
}
