@file:OptIn(ExperimentalSharedTransitionApi::class)

package os.meka.android.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import os.meka.android.shell.SharedMotion

/*
 * Shared-element transitions (build plan M1, App shell): a task's title travels between the list, the detail pane and
 * the plan. Screens wrap themselves in [MekaSharedLayout]; panes opened with [MekaPane] expose their visibility scope.
 * Reduced motion: no travel at all; panes cross-fade as before.
 */

/** The shared-transition scope of the current screen, if it set one up. */
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The enter/exit scope of the [MekaPane] this content sits in, if any. */
val LocalPaneScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Keys inside one shell destination are prefixed with its name, so the same task open on Today and on Needs you never
 * flies between the two while the shell slides from one to the other ([sharedPlace] keys are not prefixed).
 */
val LocalSharedKeyPrefix = compositionLocalOf { "" }

/** The shell's enter/exit scope for the destination this content sits in (the shell's sliding content). */
val LocalShellContent = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The key this place's title wears for [sharedPlace], set by the shell from how the place was opened; null: none. */
val LocalPlaceTitleKey = compositionLocalOf<String?> { null }

/** Shared titles travel on the expand spring, like the panes they ride with. */
private val MekaBounds = BoundsTransform { _: Rect, _: Rect -> MekaMotion.expand<Rect>(false) }

/**
 * Sets up shared-element transitions for everything inside. Inside the app shell, which already set one up, it reuses
 * the shell's, so there is only ever one layout (titles can then travel across the shell too).
 */
@Composable
fun MekaSharedLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalSharedScope.current != null) {
        Box(modifier) { content() }
    } else {
        SharedTransitionLayout(modifier) {
            CompositionLocalProvider(LocalSharedScope provides this) { content() }
        }
    }
}

/**
 * A shared element whose visibility the caller decides (a list row's title that steps aside while its twin is on
 * screen). Does nothing without a [MekaSharedLayout] or under reduced motion.
 */
@Composable
fun Modifier.sharedTitle(key: String, visible: Boolean): Modifier {
    val shared = LocalSharedScope.current ?: return this
    if (Meka.reducedMotion) return this
    val state = shared.rememberSharedContentState(LocalSharedKeyPrefix.current + key)
    return with(shared) { this@sharedTitle.sharedElementWithCallerManagedVisibility(state, visible, boundsTransform = MekaBounds) }
}

/** A shared element inside a [MekaPane]: visible while the pane is, so it travels as the pane opens and closes. */
@Composable
fun Modifier.sharedTitleInPane(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val pane = LocalPaneScope.current ?: return this
    if (Meka.reducedMotion) return this
    val state = shared.rememberSharedContentState(LocalSharedKeyPrefix.current + key)
    return with(shared) { this@sharedTitleInPane.sharedElement(state, pane, boundsTransform = MekaBounds) }
}

/**
 * A title that travels across the shell (Four tabs, slice 3): a More row's label or a Today card's title into the
 * place it opens, riding the shell's slide, and back again. Null [key], no shell, or reduced motion: nothing.
 */
@Composable
fun Modifier.sharedPlace(key: String?): Modifier {
    if (key == null) return this
    val shared = LocalSharedScope.current ?: return this
    val content = LocalShellContent.current ?: return this
    if (Meka.reducedMotion) return this
    val state = shared.rememberSharedContentState(key)
    return with(shared) { this@sharedPlace.sharedElement(state, content, boundsTransform = MekaBounds) }
}

/**
 * The unfold morph: the detail pane's share of the width. Opening the Fold grows it out beside the list; closing it
 * shrinks it away. Survives the shell rebuilding the screen on fold/unfold because the last layout is saved.
 */
@Composable
fun rememberPaneMorph(twoPane: Boolean): State<Float> {
    val reduced = Meka.reducedMotion
    var last by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val fraction = remember { Animatable(SharedMotion.startFraction(last, twoPane)) }
    LaunchedEffect(twoPane, reduced) {
        fraction.animateTo(SharedMotion.detailFraction(twoPane), MekaMotion.expand(reduced))
        last = twoPane
    }
    return fraction.asState()
}

/**
 * List → detail container transform (motion pass 2): where each task row on screen sits (root pixels), so the detail
 * pane can grow out of the row that was tapped. A plain map, written on layout and read when a pane opens; a row
 * that leaves the screen (scrolled away, completed) takes its entry with it.
 */
@Stable
class ContainerOrigins {
    private val rows = HashMap<String, Bounds>()
    internal fun put(id: String, b: Bounds) { rows[id] = b }
    internal fun remove(id: String, b: Bounds?) { if (b == null || rows[id] == b) rows.remove(id) }
    /** The row of task [id] as last laid out, or null when it isn't on screen. */
    operator fun get(id: String?): Bounds? = id?.let { rows[it] }
}

/** The screen's [ContainerOrigins], if it keeps one. */
val LocalContainerOrigins = compositionLocalOf<ContainerOrigins?> { null }

/** Records this row as task [id]'s container, so its detail can grow out of it. Nothing without [LocalContainerOrigins]. */
@Composable
fun Modifier.containerOrigin(id: String): Modifier {
    val origins = LocalContainerOrigins.current ?: return this
    val last = remember(id) { arrayOfNulls<Bounds>(1) }
    DisposableEffect(origins, id) { onDispose { origins.remove(id, last[0]) } }
    return onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        val b = Bounds(r.left, r.top, r.right, r.bottom)
        last[0] = b
        origins.put(id, b)
    }
}
