package os.meka.android.fold

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.FoldPostureLog
import os.meka.core.domain.FoldPostureRules
import os.meka.core.domain.LocalCalendar

/**
 * The live fold line (bedside clock slice 1): "Fold: half open, hinge across · hinge 95°", as the phone reports it
 * right now, for Appearance and Health. With [withLog], "Last changes ›" unfolds the last 20 posture changes (newest
 * first). Motion: the line cross-fades as the posture changes; the list unfolds with the row's expand. Reduced motion:
 * cross-fades only.
 */
@Composable
fun FoldPostureLines(modifier: Modifier = Modifier, withLog: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val reading by FoldDiagnostics.reading.collectAsState()
    val reduced = Meka.reducedMotion
    val line = reading?.let { FoldPostureRules.line(it) } ?: "Fold: open MEKA to read the hinge"
    Column(modifier.animateContentSize(MekaMotion.expand(reduced))) {
        AnimatedContent(
            targetState = line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "fold-line",
        ) { t -> Text(t, style = MekaType.caption, color = Meka.colors.textTertiary) }
        if (withLog) {
            var open by rememberSaveable { mutableStateOf(false) }
            // Re-read the stored list each time the posture changes, so a new change shows straight away.
            val log = remember(reading, open) { FoldDiagnostics.log(context) }
            if (log.isNotEmpty()) {
                Text(
                    if (open) "Last changes ⌄" else "Last changes ›", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { open = !open }.minTouch(),
                )
                if (open) {
                    val cal = remember { LocalCalendar.fixedOffset(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()) }
                    log.forEach { c ->
                        Text(FoldPostureLog.line(c, cal), style = MekaType.caption, color = Meka.colors.textTertiary,
                            modifier = Modifier.padding(bottom = MekaSpace.xxs))
                    }
                }
            }
        }
    }
}
