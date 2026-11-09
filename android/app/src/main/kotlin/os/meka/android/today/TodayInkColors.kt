package os.meka.android.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import os.meka.android.designsystem.Meka
import os.meka.core.domain.TodayInk

/** Today's inks ([os.meka.core.domain.TodayInkRules]): gold only for taps and the now mark. */
@Composable
internal fun TodayInk.color(): Color = when (this) {
    TodayInk.TAP, TodayInk.NOW_MARK -> Meka.colors.accent
    TodayInk.LIVE -> Meka.colors.textSecondary
    TodayInk.ATTENTION -> Meka.colors.calendar3
    TodayInk.QUIET -> Meka.colors.textTertiary
}
