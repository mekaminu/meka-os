package os.meka.android.designsystem

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Calm Today's spacing rhythm (ADR-012 addendum 2026-10-10): a tappable row or chip insets only xs (8) around its
 * content and is never shorter than [MekaSpace.touch] (48); its content sits centred in that height. Put it after
 * `clickable` so the whole height takes the tap.
 */
fun Modifier.minTouch(): Modifier = heightIn(min = MekaSpace.touch).wrapContentHeight(Alignment.CenterVertically)
