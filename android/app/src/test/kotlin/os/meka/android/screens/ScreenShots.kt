package os.meka.android.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import os.meka.core.domain.BoardFitRules
import os.meka.core.domain.DateGlue
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.PhraseGlue
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

    /** The bottom (px) of every piece of text on screen, for a test that checks the gap under what's above. */
    fun laidOutBounds(compose: ComposeContentTestRule): List<Float> {
        val out = mutableListOf<Float>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.Text) != null) out += n.boundsInRoot.bottom
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
                val shown = laid.filter { it.text == t || DateGlue.plain(it.text) == t }
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

    /**
     * The quality gate's "one row" (Today's re-shot, 2026-10-22: on the cover screen Up next's Open wrapped alone under
     * Done and Tomorrow): fails when the nodes tagged [tag] don't all start at the same height.
     */
    fun assertOneRow(compose: ComposeContentTestRule, name: String, tag: String) {
        val tops = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.top }
        if (tops.isEmpty()) return
        val highest = tops.min()
        if (tops.any { it - highest > 1f }) fail("$name: the $tag row wraps (tops ${tops.joinToString()} px)")
    }

    /** Fails unless [text] is on screen (as words or as what TalkBack reads), e.g. the greeting for the core's time. */
    fun assertShows(compose: ComposeContentTestRule, name: String, text: String) {
        val found = compose.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (found.isEmpty()) fail("$name: \"$text\" isn't on screen")
    }

    /**
     * Fails unless the text containing [text] is laid out on one line (Today's re-shot, 2026-10-22: "Good afternoon"
     * wrapped to two lines beside the cover screen's watch face).
     */
    fun assertOneLine(compose: ComposeContentTestRule, name: String, text: String) {
        val nodes = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) fail("$name: \"$text\" isn't on screen")
        nodes.forEach { n ->
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val lines = results.firstOrNull()?.lineCount ?: return@forEach
            if (lines != 1) fail("$name: \"$text\" takes $lines lines")
        }
    }

    /**
     * The quality gate's "a box hugs its words" (Needs you's re-shot, 2026-10-22: on the cover screen the top card kept
     * a tall empty band under its hint line): fails when the node tagged [tag] reaches more than [maxGapDp] below the
     * bottom of [lastText] inside it (its padding and a little more).
     */
    fun assertHugs(compose: ComposeContentTestRule, name: String, tag: String, lastText: String, maxGapDp: Float) {
        val box = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            ?: fail("$name: nothing tagged $tag")
        val bottom = compose.onAllNodes(hasText(lastText, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.top >= box.top - 1f && it.bottom <= box.bottom + 1f }
            .maxOfOrNull { it.bottom } ?: fail("$name: \"$lastText\" isn't inside $tag")
        val gapDp = (box.bottom - bottom) / compose.density.density
        if (gapDp > maxGapDp) fail("$name: $tag has ${gapDp.toInt()} dp of empty space under \"$lastText\" (at most ${maxGapDp.toInt()} dp)")
    }

    /**
     * The quality gate's "no word broken in two" (Ask's shots, 2026-10-22: a tile's name may wrap to two lines, which
     * must never split one word such as "Appointments" across them): fails when a one-word text takes more than a line.
     */
    fun assertNoBrokenWords(compose: ComposeContentTestRule, name: String) {
        val broken = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null && text.isNotBlank() && text.trim().none { it.isWhitespace() }) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                if ((results.firstOrNull()?.lineCount ?: 1) > 1) broken += text
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (broken.isNotEmpty()) fail("$name breaks a word across lines:\n" + broken.joinToString("\n") { "- \"$it\"" })
    }

    /**
     * The quality gate's "an icon on its name's first line" (Ask's re-shot, 2026-10-22: the Goals tile's icon sat centred
     * beside its two-line name "Goals and / habits"): fails unless the icon tagged [tag] just left of [text] has its
     * middle within the text's first line.
     */
    fun assertIconOnFirstLine(compose: ComposeContentTestRule, name: String, tag: String, text: String) {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: fail("$name: \"$text\" isn't on screen")
        val t = node.boundsInRoot
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val firstLineBottom = t.top + (results.firstOrNull()?.getLineBottom(0) ?: t.height)
        val icon = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            .filter { it.right <= t.left + 1f && it.bottom >= t.top && it.top <= t.bottom }
            .maxByOrNull { it.right } ?: fail("$name: no $tag beside \"$text\"")
        val middle = (icon.top + icon.bottom) / 2
        if (middle < t.top - 1f || middle > firstLineBottom + 1f) {
            fail("$name: the icon beside \"$text\" sits at $middle px, outside its first line (${t.top}–$firstLineBottom px)")
        }
    }

    /**
     * The quality gate's "a date stays whole" (Appointments' shots, 2026-10-22: on the cover screen a row read "last
     * went 1 / Mar 2025"): fails, naming each one, when a line break falls inside a date phrase ([DateGlue.ranges]).
     */
    fun assertDatesWhole(compose: ComposeContentTestRule, name: String) =
        assertPhrasesWhole(compose, name, "a date") { DateGlue.ranges(it) }

    /**
     * A phone number, a count with its unit and "4 of 5" stay whole (the re-shot of slice 23: "07700 / 900123",
     * "5 work / days"), with dates ([PhraseGlue.ranges]).
     */
    fun assertNumbersWhole(compose: ComposeContentTestRule, name: String) =
        assertPhrasesWhole(compose, name, "a number") { PhraseGlue.ranges(it) }

    /** Trips' clocks phrase ("1 h ahead of UK time"), on one line wherever it wraps (quality gate slice 20). */
    private val CLOCKS = Regex("\\d+ (?:h|min)(?: \\d+)? (?:ahead of|behind) UK time")

    fun assertClocksWhole(compose: ComposeContentTestRule, name: String) =
        assertPhrasesWhole(compose, name, "a time difference") { t -> CLOCKS.findAll(DateGlue.plain(t)).map { it.range }.toList() }

    private fun assertPhrasesWhole(compose: ComposeContentTestRule, name: String, what: String, ranges: (String) -> List<IntRange>) {
        val split = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.takeIf { it.lineCount > 1 }?.let { r ->
                    ranges(text).filter { r.getLineForOffset(it.first) != r.getLineForOffset(it.last) }
                        .forEach { split += "\"${text.substring(it.first, it.last + 1)}\" in \"$text\"" }
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (split.isNotEmpty()) fail("$name splits $what across lines:\n" + split.joinToString("\n") { "- $it" })
    }

    /** Fails when one of [texts] (matched whole, e.g. a field's hint) takes more than a line; ones not on screen are skipped. */
    fun assertEachOneLine(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val wrapped = texts.filter { t ->
            compose.onAllNodes(hasText(t), useUnmergedTree = true).fetchSemanticsNodes().any { n ->
                val results = mutableListOf<TextLayoutResult>()
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                (results.firstOrNull()?.lineCount ?: 1) > 1
            }
        }
        if (wrapped.isNotEmpty()) fail("$name wraps what should be one line:\n" + wrapped.joinToString("\n") { "- \"$it\"" })
    }

    /**
     * The quality gate's "no word left alone" (Settings' shots, slice 25: "…out loud on her / own"): fails, naming
     * each, when one of [texts] (plain words; drawn plain or kept) wraps and its last line holds a single word.
     */
    fun assertNoLoneWords(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val wanted = texts.map { DateGlue.plain(it) }.toSet()
        val alone = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null && DateGlue.plain(text) in wanted) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.takeIf { it.lineCount > 1 }?.let { r ->
                    val last = r.lineCount - 1
                    val words = text.substring(r.getLineStart(last), r.getLineEnd(last))
                    if (LoneWordGlue.alone(words)) alone += "\"${words.trim()}\" alone under \"${DateGlue.plain(text)}\""
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (alone.isNotEmpty()) fail("$name leaves a word alone on a line:\n" + alone.joinToString("\n") { "- $it" })
    }

    /**
     * The quality gate's "a dot stays with its words" (Health's re-shot, slice 30: a line starting "· last push 16:55"
     * or ending "sign-in expired ·"): fails, naming each, when one of [texts] lays out a line that starts or ends on "·".
     */
    fun assertNoDotAtLineEdge(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val wanted = texts.map { DateGlue.plain(it) }.toSet()
        val bad = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null && DateGlue.plain(text) in wanted) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.takeIf { it.lineCount > 1 }?.let { r ->
                    for (line in 0 until r.lineCount) {
                        val words = text.substring(r.getLineStart(line), r.getLineEnd(line))
                        if (LoneWordGlue.dotAtEdge(words)) bad += "\"${DateGlue.plain(words).trim()}\" in \"${DateGlue.plain(text)}\""
                    }
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (bad.isNotEmpty()) fail("$name starts or ends a line on a dot:\n" + bad.joinToString("\n") { "- $it" })
    }
}
