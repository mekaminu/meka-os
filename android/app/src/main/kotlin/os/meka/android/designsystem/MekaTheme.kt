package os.meka.android.designsystem

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalMekaColors = staticCompositionLocalOf { MekaDarkColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the system "Remove animations" setting (animator duration scale = 0) to honour reduced motion. */
@Composable
fun MekaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val colors = if (isSystemInDarkTheme()) MekaDarkColors else MekaLightColors
    CompositionLocalProvider(LocalMekaColors provides colors, LocalReducedMotion provides reduced, content = content)
}

object Meka {
    val colors: MekaColors @Composable get() = LocalMekaColors.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
}
