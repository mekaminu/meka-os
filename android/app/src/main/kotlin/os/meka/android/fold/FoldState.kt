package os.meka.android.fold

import android.graphics.Rect
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import os.meka.core.domain.FoldPosture

/** How the Fold is held right now, and where its hinge is (window coordinates, px) when it is half open. */
data class FoldState(val posture: FoldPosture, val hinge: Rect?) {
    companion object {
        val FLAT = FoldState(FoldPosture.FLAT, null)

        /**
         * Half open with the hinge across the screen is a table stand ("Flex mode"); half open with it down the middle is
         * a book. Closed or fully open, there is no half-open hinge: flat.
         */
        fun from(info: WindowLayoutInfo?): FoldState {
            val f = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return FLAT
            if (f.state != FoldingFeature.State.HALF_OPENED) return FLAT
            val posture = if (f.orientation == FoldingFeature.Orientation.HORIZONTAL) FoldPosture.TABLETOP else FoldPosture.BOOK
            return FoldState(posture, f.bounds)
        }
    }
}

/** The Fold's posture from Jetpack WindowManager (official API; no special permission). Flat outside an activity. */
@Composable
fun rememberFoldState(): FoldState {
    val activity = LocalActivity.current ?: return FoldState.FLAT
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(initial = null)
    return remember(info) { FoldState.from(info) }
}
