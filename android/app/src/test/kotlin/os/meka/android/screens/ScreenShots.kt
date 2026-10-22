package os.meka.android.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import os.meka.core.domain.BoardFitRules
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

    /** One piece of text as laid out: [cut] when it ends in "…", [wholeLines] the characters on its lines before that. */
    data class Laid(val text: String, val cut: Boolean, val wholeLines: Int)

    /** Every piece of text on screen as laid out (unmerged, each Text once). */
    fun laidOut(compose: ComposeContentTestRule): List<Laid> {
        val out = mutableListOf<Laid>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.let { r ->
                    val cutLine = (0 until r.lineCount).firstOrNull { r.isLineEllipsized(it) }
                    out += Laid(text, cutLine != null, if (cutLine == null) text.length else r.getLineStart(cutLine))
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /**
     * The quality gate's "cut to a word or two" (2026-10-22, the Week board's first shots): fails, naming each one, when
     * one of [titles] is cut before it can be read ([BoardFitRules.titleReadable]) or any short line (a header, a
     * capacity or free-time line, up to [BoardFitRules.WHOLE_TITLE] characters) ends in "…". [mustShow] false for a
     * scrolling list (Today), where a title below the fold isn't drawn yet: only the titles on screen are checked.
     */
    fun assertReadable(compose: ComposeContentTestRule, name: String, titles: List<String>, mustShow: Boolean = true) {
        val laid = laidOut(compose)
        val problems = buildList {
            titles.forEach { t ->
                val shown = laid.filter { it.text == t }
                if (shown.isEmpty() && mustShow) add("\"$t\" isn't on screen")
                shown.filterNot { BoardFitRules.titleReadable(t, it.cut, it.wholeLines) }
                    .forEach { add("\"$t\" is cut after ${it.wholeLines} characters") }
            }
            laid.filter { it.cut && it.text !in titles && it.text.length <= BoardFitRules.WHOLE_TITLE }
                .forEach { add("\"${it.text}\" is cut short") }
        }
        if (problems.isNotEmpty()) fail("$name has words cut off:\n" + problems.joinToString("\n") { "- $it" })
    }

    /**
     * The quality gate's "lanes line up" (Week board slice 9, 2026-10-22: Sat and Sun started their cards a line higher
     * than Thu and Fri): fails when the tops of [texts] (one per lane, e.g. each lane's first card) differ by more than
     * a pixel.
     */
    fun assertLevel(compose: ComposeContentTestRule, name: String, texts: List<String>) {
        val tops = texts.associateWith { t ->
            compose.onAllNodes(hasText(t), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
                ?: fail("$name: \"$t\" isn't on screen")
        }
        val highest = tops.values.min()
        val off = tops.filterValues { it - highest > 1f }
        if (off.isNotEmpty()) fail("$name: the lanes' first cards don't line up:\n" + tops.entries.joinToString("\n") { "- \"${it.key}\" at ${it.value} px" })
    }
}
