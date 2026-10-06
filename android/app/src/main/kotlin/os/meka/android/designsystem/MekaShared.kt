@file:OptIn(ExperimentalSharedTransitionApi::class)

package os.meka.android.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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

/** Shared titles travel on the expand spring, like the panes they ride with. */
private val MekaBounds = BoundsTransform { _: Rect, _: Rect -> MekaMotion.expand<Rect>(false) }

/** Sets up shared-element transitions for everything inside. */
@Composable
fun MekaSharedLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedScope provides this) { content() }
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
    val state = shared.rememberSharedContentState(key)
    return with(shared) { this@sharedTitle.sharedElementWithCallerManagedVisibility(state, visible, boundsTransform = MekaBounds) }
}

/** A shared element inside a [MekaPane]: visible while the pane is, so it travels as the pane opens and closes. */
@Composable
fun Modifier.sharedTitleInPane(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val pane = LocalPaneScope.current ?: return this
    if (Meka.reducedMotion) return this
    val state = shared.rememberSharedContentState(key)
    return with(shared) { this@sharedTitleInPane.sharedElement(state, pane, boundsTransform = MekaBounds) }
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
