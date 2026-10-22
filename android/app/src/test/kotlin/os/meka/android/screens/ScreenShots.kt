package os.meka.android.screens

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import os.meka.core.domain.ScreenSenseRules
import kotlin.test.fail

/**
 * The quality gate's shared pieces (build plan: "a quality gate so Meka stops being the tester"): the Fold's two
 * screens as Robolectric qualifiers, where the images go, and the "no nonsense on screen" read of everything a screen
 * shows (core [ScreenSenseRules]).
 */
object ScreenShots {
    /** The Galaxy Fold's cover screen, ≈ 904 × 2316 px at 420 dpi. */
    const val COVER = "w344dp-h882dp-420dpi"

    /** The Galaxy Fold's inner screen, ≈ 2176 × 1812 px at 420 dpi. */
    const val INNER = "w829dp-h690dp-420dpi"

    /** `build/outputs/roborazzi/<area>/<name>.png` under the app module; CI uploads the folder as `fold-screens`. */
    fun path(area: String, name: String) = "build/outputs/roborazzi/$area/$name.png"

    /** Every piece of text on screen, in tree order (unmerged, so each Text counts once). */
    fun texts(compose: ComposeContentTestRule): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            n.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** Fails the test, naming each one, when the screen shows something a person would read as broken. */
    fun assertSensible(compose: ComposeContentTestRule, name: String) {
        val problems = ScreenSenseRules.problems(texts(compose))
        if (problems.isNotEmpty()) fail("$name shows nonsense:\n" + ScreenSenseRules.report(problems))
    }
}
