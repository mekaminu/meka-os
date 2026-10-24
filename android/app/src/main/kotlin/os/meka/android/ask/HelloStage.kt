package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.LocalMekaColors
import os.meka.android.designsystem.LocalThemeControl
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.ThemeControl
import os.meka.core.domain.AskCard
import os.meka.core.domain.CardRise
import os.meka.core.domain.CardSlot
import os.meka.core.domain.Constellation
import os.meka.core.domain.HelloCard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import os.meka.core.domain.FaceFileRules
import os.meka.core.domain.FaceLook
import os.meka.core.domain.FaceGather
import os.meka.core.domain.FaceGeometry
import os.meka.core.domain.HelloCards
import os.meka.core.domain.HelloOpen
import os.meka.core.domain.EntranceKind
import os.meka.core.domain.HelloDock
import os.meka.core.domain.HelloEntrance
import os.meka.core.domain.HelloOrb
import os.meka.core.domain.StageNext
import os.meka.core.domain.TalkPhase
import os.meka.core.domain.TalkProblem
import os.meka.core.domain.StageClose
import os.meka.core.domain.StageNote
import os.meka.core.domain.StageRules
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import os.meka.android.designsystem.MekaRadius
import androidx.compose.foundation.layout.heightIn
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Hello, Navilife" slice 7, **the entrance** (motion catalogue "Hello, Navilife"): over the whole shell, the docked orb
 * zooms up from its place in the bar to the middle while the screen fades to near-black, its points scatter out to the
 * screen's edges and fly back in, re-forming with one gold shockwave; then it greets him ([onGreet]) and stays as the
 * conversation's stage: the large constellation in the middle, moving with the conversation, and the words under it
 * (what Navilife is saying, or what he's saying as it's heard). A swipe down (or Back) ends the conversation
 * ([onStop]) and it shrinks back into the bar; so does "thanks" ending it, or an answer bringing something only Ask
 * draws (Take me there, Still stuck). Slice 8: as it speaks the matching cards rise in round the orb ([HelloCards]):
 * the greeting's (Needs you, the train, the next event, his partner's asks, due today) each as its sentence starts,
 * an answer's with their buttons one after another; left, right and under it when the screen is wide, in a column
 * under the words on the cover screen. The [EntranceKind.SHORT] version (the first open of the morning) gathers, sends its shockwave and
 * goes back into the bar, saying nothing; a tap skips it. The stage is the night scene in Dark and Light alike.
 * Slice 9: on the stand it starts from the bedside clock's orb ([fromSize]) and, in quiet hours, is the calm cross-fade ([calm]).
 * Reduced motion: a cross-fade, the orb still. The timeline is the core's [HelloEntrance]; this only paints it.
 */
@Composable
fun HelloStage(
    kind: EntranceKind,
    /** The docked orb's centre, in window pixels (where the orb comes from and goes back to). */
    dock: Offset,
    phase: TalkPhase,
    level: Float,
    heard: String,
    said: String,
    needsYou: Int,
    /** Slice 8: the cards rising round the orb (the greeting's, else the latest answer's). */
    cards: StageCards,
    onGreet: () -> Unit,
    onStop: () -> Unit,
    onClosed: () -> Unit,
    /** A greeting card tapped: the stage shrinks back and its screen opens. */
    onOpen: (HelloOpen) -> Unit = {},
    /** An answer card's button tapped: Ask does it, as a tap on its own card. */
    onCard: (AskCard) -> Unit = {},
    /** Slice 9: the size (dp) of the orb it starts from and shrinks back into (the bar's, or the bedside clock's). */
    fromSize: Int = HelloDock.SIZE,
    /** Slice 9: the calm cross-fade whatever the motion setting (the stand in quiet hours, [HelloEntrance.standCalm]). */
    calm: Boolean = false,
    /** Meka 2026-10-24 14:34: why the conversation couldn't listen ([StageRules]); the stage says so instead of closing. */
    problem: TalkProblem? = null,
    /** A tap on the stage's "try again": listen again (no second hello). */
    onRetry: () -> Unit = {},
    /** The stage's "Open settings" (the microphone is off for Navilife). */
    onOpenSettings: () -> Unit = {},
    /** Why it closed, once, as it starts to close (kept on the device for Health → Obi's voice). */
    onWhy: (StageClose) -> Unit = {},
) {
    val reduced = Meka.reducedMotion || calm
    val theme = Meka.theme
    var t by remember { mutableLongStateOf(0L) }
    var closeT by remember { mutableLongStateOf(-1L) } // ms into the close, or -1 while open
    var closeFrom by remember { mutableFloatStateOf(0f) }
    var greeted by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    // The orb speed pass: a tap during the big entrance skips the rest of it (the clock jumps ahead by this much).
    var skipped by remember { mutableLongStateOf(0L) }
    val currentPhase by rememberUpdatedState(phase)
    val greet by rememberUpdatedState(onGreet)
    val closed by rememberUpdatedState(onClosed)
    // Slice 8: when the greeting's voice began (its cards rise as each sentence starts), and when the answer's cards came.
    var speakAt by remember { mutableLongStateOf(-1L) }
    var answerAt by remember { mutableLongStateOf(-1L) }
    val answerCards = cards.answer
    LaunchedEffect(answerCards) { answerAt = if (answerCards.isEmpty()) -1L else t }
    LaunchedEffect(cards.greeting) { speakAt = -1L }
    val greetAt = HelloEntrance.greetAt(kind, reduced)
    val length = HelloEntrance.length(kind, reduced)

    fun dimNow(): Float = if (closeT >= 0) HelloEntrance.closing(closeT, closeFrom, reduced).second else HelloEntrance.dim(kind, t, reduced)
    val why by rememberUpdatedState(onWhy)
    fun close(stop: Boolean, reason: StageClose? = if (stop) StageClose.SWIPED else null) {
        if (closing) return
        if (kind == EntranceKind.BIG && reason != null) why(reason)
        if (stop) onStop()
        closeFrom = dimNow()
        closing = true
    }

    // One clock for the whole entrance and the close; frames only while the stage is up.
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        var closeStart = -1L
        while (true) {
            val now = withFrameMillis { it }
            t = now - start + skipped
            if (greetAt != null && !greeted && t >= greetAt) { greeted = true; greet() }
            if (currentPhase != TalkPhase.ENDED) started = true
            if (currentPhase == TalkPhase.SPEAKING && speakAt < 0 && greeted) speakAt = t
            if (kind == EntranceKind.SHORT && t >= length) { closed(); return@LaunchedEffect }
            if (closing) {
                if (closeStart < 0) closeStart = now
                closeT = now - closeStart
                if (closeT >= (if (reduced) HelloEntrance.FADE_MS else HelloEntrance.CLOSE_MS)) { closed(); return@LaunchedEffect }
            }
        }
    }
    // Meka 2026-10-24 14:34: once he has spoken or it can't listen, the stage stays and says so ([StageRules]).
    var spoke by remember { mutableStateOf(false) }
    if (heard.isNotBlank() && !spoke) spoke = true
    var endedAt by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(phase, started) { endedAt = if (phase == TalkPhase.ENDED && started) t else -1L }
    val call = if (kind == EntranceKind.BIG && greetAt != null && t >= length && !closing)
        StageRules.call(phase, started, spoke, cards.needsAsk, problem, t - greetAt, if (endedAt >= 0) t - endedAt else null)
    else null
    val note = call?.note ?: StageNote.NONE
    // Once settled, the conversation decides: stay up, or shrink back into the bar.
    if (call?.next == StageNext.CLOSE) LaunchedEffect(Unit) { close(stop = false, reason = call?.close) }
    val retry by rememberUpdatedState(onRetry)
    fun tryAgain() { endedAt = -1L; retry() }
    BackHandler { close(stop = true) }

    val dim = dimNow()
    val travel = if (closeT >= 0) HelloEntrance.closing(closeT, closeFrom, reduced).first else HelloEntrance.travel(kind, t, reduced)
    val spread = if (closeT >= 0) 0f else HelloEntrance.spread(kind, t, reduced)
    val shock = if (closeT >= 0) null else HelloEntrance.shock(kind, t, reduced)
    val settled = kind == EntranceKind.BIG && t >= HelloEntrance.GREET_AT.coerceAtMost(length) && !closing
    val state = if (kind == EntranceKind.BIG && greeted) HelloOrb.state(phase, needsYou) else HelloOrb.state(TalkPhase.ENDED, needsYou)
    val words = if (!settled) null else StageRules.line(note, problem) ?: HelloEntrance.words(phase, heard, said)
    val dens = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Obi's face, slice 3: the big stage shows his face instead of the orb once its files are kept and Face is on.
    val faceContext = LocalContext.current
    LaunchedEffect(Unit) { ObiFaceStore.load(faceContext) }
    val faceReady by ObiFaceStore.ready.collectAsState()
    val faceOn by ObiFaceStore.on.collectAsState()
    val face = kind == EntranceKind.BIG && FaceFileRules.shows(faceReady, faceOn)

    // The night scene in both themes: the orb draws its Dark gold, additive.
    CompositionLocalProvider(
        LocalMekaColors provides MekaDarkColors,
        LocalThemeControl provides ThemeControl(theme.choice, true, theme.set),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .background(Color.Black.copy(alpha = dim))
                .pointerInput(kind) {
                    if (kind == EntranceKind.SHORT) detectTapGestures { close(stop = false) }
                    else {
                        var drag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = { if (HelloEntrance.swipeCloses(drag / dens.density)) close(stop = true) },
                        ) { change, dy -> change.consume(); drag += dy }
                    }
                }
                .pointerInput(kind, reduced, note, problem) {
                    // A tap while the orb is still flying up and gathering jumps to the end; the hello carries on.
                    if (kind == EntranceKind.BIG) detectTapGestures {
                        if (closing) return@detectTapGestures
                        if (StageRules.tapRetries(note, problem)) { tryAgain(); return@detectTapGestures }
                        HelloEntrance.tapThrough(kind, t, reduced)?.let { to -> skipped += to - t }
                    }
                }
                .semantics {
                    contentDescription = listOfNotNull(HelloEntrance.LABEL, words).joinToString(" · ")
                    onClick(label = "Close") { close(stop = true); true }
                },
        ) {
            val stagePx = with(dens) { HelloEntrance.STAGE_SIZE.dp.toPx() }
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            // The orb's home on the stage: a little above the middle, room for the words under it.
            val home = Offset(w / 2, h * 0.42f)
            val from = if (dock.isSpecifiedAndSet()) dock - origin else Offset(w / 2, h - stagePx / 4)
            val c = from + (home - from) * travel
            val small = fromSize.toFloat() / HelloEntrance.STAGE_SIZE
            val scale = small + (1f - small) * travel
            // Flung to just past the screen's far corner (in the orb's radii).
            val radius = stagePx / 2 / Constellation.EXTENT
            val reach = (max(w, h) * 0.62f) / radius
            // Obi's face, slice 3: once the stage has settled the constellation dissolves into his face (when its files
            // are on this phone and Face is on), and the face fades back to the orb as the stage closes.
            val faceIn = when {
                !face || greetAt == null -> 0f
                closeT >= 0 -> FaceLook.faceIn(t - greetAt, reduced) * (1f - closeT.toFloat() / HelloEntrance.FADE_MS).coerceIn(0f, 1f)
                else -> FaceLook.faceIn(t - greetAt, reduced)
            }
            // Slice 3d: the points gather into his face as it comes in (and leave it again as the stage closes), staying
            // lit while they gather; the face shows through over them. Reduced motion: the plain cross-fade.
            HelloOrbView(
                state, level, size = HelloEntrance.STAGE_SIZE.dp, spread = spread, reach = reach, entranceShock = shock,
                gather = FaceGather.gather(faceIn, reduced),
                modifier = Modifier
                    .offset { IntOffset((c.x - stagePx / 2).roundToInt(), (c.y - stagePx / 2).roundToInt()) }
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = (if (closeT >= 0 && reduced) travel else 1f) * FaceGather.orbAlpha(faceIn, reduced) },
            )
            if (face) {
                val faceW = stagePx * (FaceGeometry.CROP.w / FaceGeometry.CROP.h).toFloat()
                ObiFaceView(
                    state, level, height = HelloEntrance.STAGE_SIZE.dp, still = calm,
                    modifier = Modifier
                        .offset { IntOffset((c.x - faceW / 2).roundToInt(), (c.y - stagePx / 2).roundToInt()) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = FaceGather.faceAlpha(faceIn, reduced) },
                )
            }
            // Slice 8: the cards rising round the orb — the answer's (with their buttons), else the greeting's as it says them.
            val shownCards: List<Pair<Any, CardRise>> = when {
                !settled -> emptyList()
                answerCards.isNotEmpty() -> answerCards.withIndex().filter { it.index !in cards.done }.take(HelloCards.MAX).map { (i, card) ->
                    card to HelloCards.rise(if (answerAt < 0) -1 else t - answerAt - HelloCards.answerRevealAt(i), reduced)
                }
                else -> cards.greeting?.let { g ->
                    val at = HelloCards.revealAt(g)
                    g.cards.take(HelloCards.MAX).mapIndexed { i, card ->
                        card to HelloCards.rise(if (speakAt < 0) -1 else t - speakAt - (at.getOrNull(i) ?: 0L), reduced)
                    }
                }.orEmpty()
            }
            val wide = HelloCards.wide(w / dens.density)
            val slots = HelloCards.slots(shownCards.size, wide)
            val cardPx = with(dens) { HelloCards.CARD_DP.dp.toPx() }
            val gapPx = with(dens) { MekaSpace.l.toPx() }
            @Composable
            fun StageCard(item: Any, rise: CardRise, m: Modifier) {
                when (item) {
                    is HelloCard -> StageHelloCard(item, rise, dens.density, m) { close(stop = true, reason = StageClose.OPENED_CARD); onOpen(item.open) }
                    is AskCard -> StageAnswerCard(item, rise, dens.density, m) { onCard(item) }
                }
            }
            shownCards.forEachIndexed { i, (item, rise) ->
                val slot = slots.getOrNull(i) ?: return@forEachIndexed
                if (slot == CardSlot.BELOW) return@forEachIndexed
                val x = if (slot == CardSlot.LEFT) home.x - stagePx / 2 - gapPx - cardPx else home.x + stagePx / 2 + gapPx
                StageCard(item, rise, Modifier.offset { IntOffset(x.roundToInt(), (home.y - with(dens) { 40.dp.toPx() }).roundToInt()) })
            }
            // The words under the orb, cross-fading as they change; the cards that sit under it follow them.
            Column(
                Modifier.align(Alignment.TopCenter)
                    .offset { IntOffset(0, (home.y + stagePx / 2).roundToInt()) }
                    .padding(horizontal = MekaSpace.l).widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
            AnimatedContent(
                targetState = words,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "hello-words",
            ) { line ->
                if (line != null) Text(line, style = MekaType.body, color = MekaDarkColors.textPrimary, textAlign = TextAlign.Center,
                    maxLines = if (shownCards.isEmpty()) 6 else 4)
                else Box(Modifier.size(1.dp))
            }
            shownCards.forEachIndexed { i, (item, rise) ->
                if (slots.getOrNull(i) == CardSlot.BELOW) StageCard(item, rise, Modifier)
            }
            StageRules.button(note, problem)?.let { label ->
                // The whole 48 dp answers the tap, not just the pill's words.
                Box(Modifier.clickable(role = Role.Button, onClick = onOpenSettings).heightIn(min = MekaSpace.touch), contentAlignment = Alignment.Center) {
                    Text(
                        label, style = MekaType.itemMeta, color = MekaDarkColors.onAccent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(MekaDarkColors.accent)
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    )
                }
            }
            }
            if (settled) Text(
                HelloEntrance.CLOSE_HINT, style = MekaType.caption, color = MekaDarkColors.textTertiary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = MekaSpace.xl),
            )
        }
    }
}

private fun Offset.isSpecifiedAndSet(): Boolean = this != Offset.Unspecified && this != Offset.Zero
