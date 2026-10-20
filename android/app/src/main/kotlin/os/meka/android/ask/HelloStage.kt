package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import os.meka.core.domain.Constellation
import os.meka.core.domain.EntranceKind
import os.meka.core.domain.HelloDock
import os.meka.core.domain.HelloEntrance
import os.meka.core.domain.HelloOrb
import os.meka.core.domain.StageNext
import os.meka.core.domain.TalkPhase
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Hello, Navilife" slice 7, **the entrance** (motion catalogue "Hello, Navilife"): over the whole shell, the docked orb
 * zooms up from its place in the bar to the middle while the screen fades to near-black, its points scatter out to the
 * screen's edges and fly back in, re-forming with one gold shockwave; then it greets him ([onGreet]) and stays as the
 * conversation's stage: the large constellation in the middle, moving with the conversation, and the words under it
 * (what Navilife is saying, or what he's saying as it's heard). A swipe down (or Back) ends the conversation
 * ([onStop]) and it shrinks back into the bar; so does "thanks" ending it, or an answer bringing cards to tap (they're on
 * Ask behind it). The [EntranceKind.SHORT] version (the first open of the morning) gathers, sends its shockwave and
 * goes back into the bar, saying nothing; a tap skips it. The stage is the night scene in Dark and Light alike.
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
    cards: Int,
    needsYou: Int,
    onGreet: () -> Unit,
    onStop: () -> Unit,
    onClosed: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val theme = Meka.theme
    var t by remember { mutableLongStateOf(0L) }
    var closeT by remember { mutableLongStateOf(-1L) } // ms into the close, or -1 while open
    var closeFrom by remember { mutableFloatStateOf(0f) }
    var greeted by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val currentPhase by rememberUpdatedState(phase)
    val greet by rememberUpdatedState(onGreet)
    val closed by rememberUpdatedState(onClosed)
    val greetAt = HelloEntrance.greetAt(kind, reduced)
    val length = HelloEntrance.length(kind, reduced)

    fun dimNow(): Float = if (closeT >= 0) HelloEntrance.closing(closeT, closeFrom, reduced).second else HelloEntrance.dim(kind, t, reduced)
    fun close(stop: Boolean) {
        if (closing) return
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
            t = now - start
            if (greetAt != null && !greeted && t >= greetAt) { greeted = true; greet() }
            if (currentPhase != TalkPhase.ENDED) started = true
            if (kind == EntranceKind.SHORT && t >= length) { closed(); return@LaunchedEffect }
            if (closing) {
                if (closeStart < 0) closeStart = now
                closeT = now - closeStart
                if (closeT >= (if (reduced) HelloEntrance.FADE_MS else HelloEntrance.CLOSE_MS)) { closed(); return@LaunchedEffect }
            }
        }
    }
    // Once settled, the conversation decides: stay up, or shrink back into the bar.
    if (kind == EntranceKind.BIG && greetAt != null && t >= length && !closing &&
        HelloEntrance.next(phase, started, cards, t - greetAt) == StageNext.CLOSE
    ) LaunchedEffect(Unit) { close(stop = false) }
    BackHandler { close(stop = true) }

    val dim = dimNow()
    val travel = if (closeT >= 0) HelloEntrance.closing(closeT, closeFrom, reduced).first else HelloEntrance.travel(kind, t, reduced)
    val spread = if (closeT >= 0) 0f else HelloEntrance.spread(kind, t, reduced)
    val shock = if (closeT >= 0) null else HelloEntrance.shock(kind, t, reduced)
    val settled = kind == EntranceKind.BIG && t >= HelloEntrance.GREET_AT.coerceAtMost(length) && !closing
    val state = if (kind == EntranceKind.BIG && greeted) HelloOrb.state(phase, needsYou) else HelloOrb.state(TalkPhase.ENDED, needsYou)
    val words = if (settled) HelloEntrance.words(phase, heard, said) else null
    val dens = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }

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
            val small = HelloDock.SIZE.toFloat() / HelloEntrance.STAGE_SIZE
            val scale = small + (1f - small) * travel
            // Flung to just past the screen's far corner (in the orb's radii).
            val radius = stagePx / 2 / Constellation.EXTENT
            val reach = (max(w, h) * 0.62f) / radius
            HelloOrbView(
                state, level, size = HelloEntrance.STAGE_SIZE.dp, spread = spread, reach = reach, entranceShock = shock,
                modifier = Modifier
                    .offset { IntOffset((c.x - stagePx / 2).roundToInt(), (c.y - stagePx / 2).roundToInt()) }
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (closeT >= 0 && reduced) travel else 1f },
            )
            // The words under the orb, cross-fading as they change.
            AnimatedContent(
                targetState = words,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "hello-words",
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset { IntOffset(0, (home.y + stagePx / 2).roundToInt()) }
                    .padding(horizontal = MekaSpace.l).widthIn(max = 520.dp),
            ) { line ->
                if (line != null) Text(line, style = MekaType.body, color = MekaDarkColors.textPrimary, textAlign = TextAlign.Center, maxLines = 6)
                else Box(Modifier.size(1.dp))
            }
            if (settled) Text(
                HelloEntrance.CLOSE_HINT, style = MekaType.caption, color = MekaDarkColors.textTertiary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = MekaSpace.xl),
            )
        }
    }
}

private fun Offset.isSpecifiedAndSet(): Boolean = this != Offset.Unspecified && this != Offset.Zero
