package os.meka.android.screens

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import android.os.Looper
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.robolectric.Shadows
import os.meka.android.ask.LIFE_TILE_TAG
import os.meka.android.designsystem.MekaSpace
import os.meka.core.domain.BoardFitRules
import os.meka.core.domain.DateGlue
import os.meka.core.domain.DurationGlue
import os.meka.core.domain.LifePageRules
import os.meka.core.domain.LifePageView
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.PhraseGlue
import os.meka.core.domain.ScreenSenseRules
import androidx.compose.ui.test.onAllNodesWithTag
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
     * The same "lanes line up", measured by the card that holds each of [texts] (the node tagged [tag] around it) rather
     * than the text itself: the Week board's planner cards (Option B, slice 1) put a time and duration line above some
     * titles and not others, so the cards' tops are what must be level.
     */
    fun assertLevelBy(compose: ComposeContentTestRule, name: String, texts: List<String>, tag: String) {
        val cards = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) cards += n
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        fun holds(n: SemanticsNode, t: String): Boolean =
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == t || n.children.any { holds(it, t) }
        val tops = texts.associateWith { t ->
            cards.firstOrNull { holds(it, t) }?.boundsInRoot?.top ?: fail("$name: no card holds \"$t\"")
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
    /** True when a node's text or description contains [text] (merged or not). */
    fun shows(compose: ComposeContentTestRule, text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true) or plainText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits, by turns, until [text] is on screen, then asserts it (the flaky screen tests, 2026-10-26–30: the core had
     * changed but the screen hadn't caught up yet). Each turn runs the main looper, gives the core's own thread a little
     * real time and moves the frame clock on; it stops as soon as the text shows, and fails after [turns] turns.
     * Never a skip: a screen that never shows the text still fails.
     */
    fun awaitShows(compose: ComposeContentTestRule, name: String, text: String, turns: Int = 80) {
        var i = 0
        while (!shows(compose, text) && i++ < turns) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
            compose.mainClock.advanceTimeBy(100)
        }
        assertShows(compose, name, text)
    }

    fun assertShows(compose: ComposeContentTestRule, name: String, text: String) {
        val found = compose.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        // A line drawn with no-break spaces (LoneWordGlue, DateGlue) reads the same: the words are compared as plain.
        if (found.isEmpty() && texts(compose).none { DateGlue.plain(it).contains(DateGlue.plain(text)) }) fail("$name: \"$text\" isn't on screen")
    }

    /** A node whose text, read as plain words (no-break spaces as spaces), is [text] (or holds it, with [substring]). */
    fun plainText(text: String, substring: Boolean = false): SemanticsMatcher =
        SemanticsMatcher("plain text \"$text\"") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                val t = DateGlue.plain(it.text)
                if (substring) t.contains(DateGlue.plain(text)) else t == DateGlue.plain(text)
            }
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

    /**
     * A duration and the word it measures stay whole (Shut down's shots, slice 47: the wake line read "1 h 20 /
     * commute"), [DurationGlue.ranges]. Only for screens that draw their lines through [DurationGlue].
     */
    fun assertDurationsWhole(compose: ComposeContentTestRule, name: String) =
        assertPhrasesWhole(compose, name, "a duration") { DurationGlue.ranges(it) }

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

    /**
     * Fails when one of [texts] (matched whole) is drawn cut: wider than its room, or its last line ending before the
     * text does (walk-through 2026-11-08, A3: the review's "£2,125.91" drawn as "£2,125."). Ones not on screen are skipped.
     */
    fun assertNotCut(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val cut = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && text in texts && action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.let { r ->
                    if (r.didOverflowWidth || r.getLineEnd(r.lineCount - 1, visibleEnd = true) < text.trimEnd().length) cut += text
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (cut.isNotEmpty()) fail("$name draws these cut:\n" + cut.joinToString("\n") { "- \"$it\"" })
    }

    /**
     * Walk-through 2026-11-15 A14: fails when any text inside a node tagged [tag] (every decide card, the mail card) is
     * drawn cut, as the cover's decide cards cut "expires i…", "Dentist · … · Las…" and "Put back fro…" with room below.
     */
    fun assertNothingCutUnder(compose: ComposeContentTestRule, name: String, tag: String) {
        val texts = mutableSetOf<String>()
        fun collect(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.let { texts += it }
            n.children.forEach { collect(it) }
        }
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) collect(n) else n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (texts.isEmpty()) fail("$name: nothing under $tag")
        // Only a last line drawn with an ellipsis: [assertNotCut]'s width and visible-end reads flagged every one-line title
        // and pill in these cards under Robolectric while they were drawn whole (slice 36's first CI run).
        val cut = mutableListOf<String>()
        fun check(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && text in texts && action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.let { r ->
                    val last = r.lineCount - 1
                    if (r.isLineEllipsized(last)) cut += "$text (${r.lineCount} lines, ends at ${r.getLineEnd(last, visibleEnd = true)} of ${text.length})"
                }
            }
            n.children.forEach { check(it) }
        }
        check(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (cut.isNotEmpty()) fail("$name draws these cut:\n" + cut.joinToString("\n") { "- $it" })
    }

    /**
     * Walk-through 2026-11-15 A14: fails when one of [texts] (matched whole) is drawn with its last line ellipsized, as
     * Facts' boiler and the Emergency card's loft line were cut on the open Fold with most of the row empty. Returns how
     * many of [texts] were on screen, so a caller can insist one was checked.
     */
    fun assertNotEllipsized(compose: ComposeContentTestRule, name: String, texts: Collection<String>): Int {
        val cut = mutableListOf<String>()
        var seen = 0
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && text in texts && action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.let { r ->
                    seen++
                    if (r.isLineEllipsized(r.lineCount - 1)) cut += text
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (cut.isNotEmpty()) fail("$name cuts these with \u2026:\n" + cut.joinToString("\n") { "- \"$it\"" })
        return seen
    }

    /**
     * Fails when one of [texts] (matched whole) is drawn in less room than its natural one-line width. For a pill whose
     * text node is measured for its intrinsics as well as laid out (A6's "Other" chip on a Board card), the text layout
     * [assertNotCut] reads can be the intrinsic pass's, not the drawn one; the node's laid-out width is what's drawn.
     */
    fun assertDrawnWhole(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val cut = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && text in texts && action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.let { r ->
                    val natural = kotlin.math.ceil(r.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
                    if (n.size.width + 1 < natural) cut += "$text (${n.size.width} px for $natural)"
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (cut.isNotEmpty()) fail("$name draws these in too little room:\n" + cut.joinToString("\n") { "- $it" })
    }

    /**
     * Walk-through 2026-11-15 A11: fails when one of [texts] (matched whole, e.g. a chip's word) is laid out but sliced
     * sideways by a parent's edge (a sliding chip row cutting "Mail" to "M", "Football España" to "Football E") or slid
     * wholly out of sight. Nodes scrolled out of view vertically are skipped.
     */
    fun assertNotSliced(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val sliced = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            if (text != null && text in texts && n.size.width > 0) {
                val seen = n.boundsInRoot
                val tall = seen.bottom - seen.top
                if (tall > 0f && seen.right - seen.left < n.size.width - 1f) {
                    sliced += "$text (${(seen.right - seen.left).toInt().coerceAtLeast(0)} of ${n.size.width} px shown)"
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (sliced.isNotEmpty()) fail("$name cuts these at an edge:\n" + sliced.joinToString("\n") { "- $it" })
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
     * Walk-through 2026-11-15, A15 (g4a 14): fails when the chips of one row group wrap and the last sits alone on its
     * row ("No estimate", "21 Dec · 8 wks", "Select several"). The chips are the nodes tagged with [tagPrefix], or the
     * nodes whose text is one of [labels]; returns how many were on screen.
     */
    fun assertNoChipAlone(
        compose: ComposeContentTestRule, name: String, tagPrefix: String? = null, labels: Collection<String> = emptyList(), underTag: String? = null,
    ): Int {
        val tops = mutableListOf<Pair<Int, Int>>() // top, left
        fun walk(n: SemanticsNode) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val hit = (tagPrefix != null && tag != null && tag.startsWith(tagPrefix)) || (text != null && DateGlue.plain(text) in labels)
            if (hit && n.size.width > 0) tops += n.boundsInRoot.top.toInt() to n.boundsInRoot.left.toInt()
            else n.children.forEach { walk(it) }
        }
        walk(if (underTag != null) compose.onNodeWithTag(underTag, useUnmergedTree = true).fetchSemanticsNode() else compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val rows = tops.groupBy { it.first }.toSortedMap()
        // One a row all the way down is a short list of choices (EvenRows: three where two fit), not one left alone.
        if (rows.size > 1 && rows.values.last().size == 1 && rows.values.any { it.size > 1 }) {
            fail("$name: the last chip sits alone on its row (rows ${rows.values.map { it.size }})")
        }
        return tops.size
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
     * The quality gate's "a dot stays with the words before it" (Health's re-shot, slice 30: a line starting
     * "· last push 16:55"): fails, naming each, when one of [texts] lays out a line that starts with "·".
     */
    fun assertNoDotStartingALine(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
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
                        if (LoneWordGlue.dotStartsLine(words)) bad += "\"${DateGlue.plain(words).trim()}\" in \"${DateGlue.plain(text)}\""
                    }
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (bad.isNotEmpty()) fail("$name starts a line with a dot:\n" + bad.joinToString("\n") { "- $it" })
    }

    /**
     * Walk-through 2026-11-08, A14 ("4 days ·", "Oct", "from AI" alone on the cover screen): on a page under Ask, no card's
     * empty line leaves a word alone or ends or starts a line on a "·", and no tile's label starts a line with a "·".
     */
    fun assertLifePageWraps(compose: ComposeContentTestRule, name: String, page: LifePageView) {
        val empties = page.cards.mapNotNull { it.empty }
        assertNoLoneWords(compose, name, empties)
        assertNoDotEndingALine(compose, name, empties)
        assertNoDotStartingALine(compose, name, empties + page.tiles.map { it.label })
        assertTilesOneHeight(compose, name)
        assertHeaderWhole(compose, name)
    }

    /**
     * Walk-through 2026-11-15 A11: a page's title never pushes its gold + Add off the closed Fold ("Emergency card"
     * left "+ A" over the ⓘ; "What Navilife knows" left no + Add at all). + Add sits inside the screen, its pill as
     * wide as its words plus their padding. (The words' own text layout can be the intrinsic pass's, which reads as cut
     * even when drawn whole, so the pill's laid-out width is what's checked.) Pages without the header pass.
     */
    fun assertHeaderWhole(compose: ComposeContentTestRule, name: String) {
        val add = compose.onAllNodes(androidx.compose.ui.test.hasText(LifePageRules.ADD), useUnmergedTree = true).fetchSemanticsNodes()
        if (add.isEmpty()) {
            if (compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(LifePageRules.INFO_LABEL)).fetchSemanticsNodes().isNotEmpty()) {
                fail("$name: the page's header has no ${LifePageRules.ADD}")
            }
            return
        }
        val width = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().size.width
        val padding = with(compose.density) { (MekaSpace.m * 2).toPx() }
        add.forEach { n ->
            if (n.positionInRoot.x < 0f || n.positionInRoot.x + n.size.width > width + 1) fail("$name: ${LifePageRules.ADD} runs off the screen")
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val natural = results.firstOrNull()?.multiParagraph?.intrinsics?.maxIntrinsicWidth ?: return@forEach
            if (n.size.width + 1 < natural + padding) fail("$name: ${LifePageRules.ADD} drawn in ${n.size.width} px for ${(natural + padding).toInt()}")
        }
    }

    /**
     * Walk-through 2026-11-08 A19: a page's number tiles share one height, so a one-line label's tile ("7 days / to
     * Seville") is never shorter than its two-line neighbours. Pages without the tiles pass.
     */
    fun assertTilesOneHeight(compose: ComposeContentTestRule, name: String) {
        val heights = compose.onAllNodesWithTag(LIFE_TILE_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot.height }
        if (heights.size < 2) return
        if (heights.max() - heights.min() > 1f) fail("$name: the number tiles aren't one height: ${heights.map { it.toInt() }} px")
    }

    /**
     * Walk-through 2026-11-08 A27: a time stepper's + (tagged "[prefix]-step-more-…-glyph") ends on the column's edge,
     * where the On / Off pills and the full-width button ([edgeText], e.g. "Send a test") end; it ended ~16 dp short,
     * centred in its 48 dp target. Unclipped positions, so rows below the fold count. Fails if no stepper is drawn.
     */
    fun assertSteppersEndOnTheEdge(compose: ComposeContentTestRule, name: String, prefix: String, edgeText: String) {
        fun right(n: SemanticsNode) = n.positionInRoot.x + n.size.width
        val glyphs = compose.onAllNodes(SemanticsMatcher("$prefix stepper +") { n ->
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("$prefix-step-more-") && it.endsWith("-glyph") } == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
        if (glyphs.isEmpty()) fail("$name: no time stepper drawn")
        val edgeNode = compose.onAllNodes(androidx.compose.ui.test.hasText(edgeText)).fetchSemanticsNodes().firstOrNull()
            ?: fail("$name: \"$edgeText\" isn't drawn to measure the edge by")
        val edge = right(edgeNode)
        val pills = compose.onAllNodesWithTag("switch-pill", useUnmergedTree = true).fetchSemanticsNodes()
        val slack = 2f * compose.density.density
        (glyphs + pills.filter { kotlin.math.abs(edge - right(it)) <= 24f * compose.density.density }).forEach { g ->
            val short = (edge - right(g)) / compose.density.density
            if (kotlin.math.abs(edge - right(g)) > slack) fail("$name: a stepper's + or a switch ends ${short.toInt()} dp short of the column's edge")
        }
    }

    /** Quality gate slice 36: none of [texts], where drawn on more than one line, has a line ending on a "·". */
    fun assertNoDotEndingALine(compose: ComposeContentTestRule, name: String, texts: Collection<String>) {
        val wanted = texts.map { DateGlue.plain(it) }.toSet()
        if (wanted.isEmpty()) return
        val bad = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null && DateGlue.plain(text) in wanted) {
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.takeIf { it.lineCount > 1 }?.let { r ->
                    for (line in 0 until r.lineCount - 1) {
                        val words = DateGlue.plain(text.substring(r.getLineStart(line), r.getLineEnd(line))).trim()
                        if (words.endsWith("·")) bad += "\"$words\" in \"${DateGlue.plain(text)}\""
                    }
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (bad.isNotEmpty()) fail("$name ends a line with a dot:\n" + bad.joinToString("\n") { "- $it" })
    }

    /**
     * The quality gate's "no first letter shaved by a rounded corner" (walk-through 2026-11-02 A1: Trip lists' "2 days to
     * go" lost its "2" to the head's `clip(RoundedCornerShape(..))`, whose text sat flush in its bottom-left corner; the
     * voice notes' "ιoday" and Money's year row before it): fails when the text containing [text] reaches into a rounded
     * corner of a clip on any of its ancestors, i.e. part of its box lies outside that corner's arc.
     */
    /**
     * Walk-through 2026-11-02, A14: the text [text] sits inside the node tagged [tag] with at least [minDp] either side
     * ("Before we leave" against its tab pill's edge).
     */
    fun assertInset(compose: ComposeContentTestRule, name: String, text: String, tag: String, minDp: Float) {
        val box = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.firstOrNull { box.contains(it.center) } ?: fail("$name: \"$text\" isn't inside $tag")
        val d = compose.density.density
        val left = (label.left - box.left) / d
        val right = (box.right - label.right) / d
        if (left < minDp || right < minDp) fail("$name: \"$text\" sits ${"%.1f".format(minOf(left, right))} dp from its edge in $tag (want $minDp)")
    }

    fun assertNotCornerClipped(compose: ComposeContentTestRule, name: String, text: String) {
        val nodes = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) fail("$name: \"$text\" isn't on screen")
        nodes.forEach { n ->
            val box = n.boundsInRoot
            var info: androidx.compose.ui.layout.LayoutInfo? = n.layoutInfo
            while (info != null) {
                for (m in info.getModifierInfo()) {
                    val shape = roundedClip(m.modifier) ?: continue
                    if (!m.coordinates.isAttached) continue
                    val clip = m.coordinates.boundsInRoot()
                    val size = androidx.compose.ui.geometry.Size(clip.width, clip.height)
                    val d = compose.density
                    val tl = shape.topStart.toPx(size, d); val tr = shape.topEnd.toPx(size, d)
                    val bl = shape.bottomStart.toPx(size, d); val br = shape.bottomEnd.toPx(size, d)
                    fun outside(cx: Float, cy: Float, r: Float, x: Float, y: Float): Boolean {
                        val dx = x - cx; val dy = y - cy
                        return dx * dx + dy * dy > (r + 0.5f) * (r + 0.5f)
                    }
                    val cut = listOf(
                        box.left < clip.left + tl && box.top < clip.top + tl && outside(clip.left + tl, clip.top + tl, tl, maxOf(box.left, clip.left), maxOf(box.top, clip.top)),
                        box.right > clip.right - tr && box.top < clip.top + tr && outside(clip.right - tr, clip.top + tr, tr, minOf(box.right, clip.right), maxOf(box.top, clip.top)),
                        box.left < clip.left + bl && box.bottom > clip.bottom - bl && outside(clip.left + bl, clip.bottom - bl, bl, maxOf(box.left, clip.left), minOf(box.bottom, clip.bottom)),
                        box.right > clip.right - br && box.bottom > clip.bottom - br && outside(clip.right - br, clip.bottom - br, br, minOf(box.right, clip.right), minOf(box.bottom, clip.bottom)),
                    )
                    if (cut.any { it }) fail("$name: \"$text\" sits in a rounded clip's corner, so its edge is shaved")
                }
                info = info.parentInfo
            }
        }
    }

    /** The rounded shape of a `clip(RoundedCornerShape(..))` (a graphics layer with clip on), else null. */
    private fun roundedClip(modifier: androidx.compose.ui.Modifier): androidx.compose.foundation.shape.CornerBasedShape? {
        if (!modifier.javaClass.name.contains("GraphicsLayer")) return null
        fun field(n: String): Any? = runCatching {
            modifier.javaClass.getDeclaredField(n).also { it.isAccessible = true }.get(modifier)
        }.getOrNull()
        if (field("clip") != true) return null
        return field("shape") as? androidx.compose.foundation.shape.CornerBasedShape
    }

    /** Walk-through A32: the glyph button tagged [tag] (Money's ⓘ) is a whole 48 × 48 target, not as wide as its glyph. */
    fun assertSquareTarget(compose: ComposeContentTestRule, name: String, tag: String) {
        val d = compose.density.density
        val n = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: fail("$name: no $tag on screen")
        if (n.size.width / d < 47.5f || n.size.height / d < 47.5f)
            fail("$name: $tag is ${(n.size.width / d).toInt()} × ${(n.size.height / d).toInt()} dp, not 48 × 48")
    }

    /**
     * Walk-through 2026-11-02 A9: every button, switch and checkbox on screen is at least 48 dp tall, a glyph's button
     * ("⋯") 48 × 48 (not as wide as its glyph), and every drawn On/Off pill and setting chip is at least 30 dp tall.
     * Only what is laid out inside the screen counts (a row scrolled half off isn't measured).
     */
    fun assertWholeTargets(compose: ComposeContentTestRule, name: String, narrowOk: Set<String> = emptySet()) {
        val d = compose.density.density
        val roles = setOf(androidx.compose.ui.semantics.Role.Button, androidx.compose.ui.semantics.Role.Switch,
            androidx.compose.ui.semantics.Role.Checkbox)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun inside(n: SemanticsNode) = n.boundsInRoot.let { it.top >= screen.top && it.bottom <= screen.bottom }
        val small = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Role) in roles && inside(it) }
            .filter { n ->
                // A glyph's button ("⋯", "‹", "+") must be 48 wide too; a worded one is as wide as its words.
                val drawn = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty().trim()
                // [narrowOk]: spoken labels of glyph targets allowed under 48 wide (still 48 tall), e.g. Work's day circles.
                val spoken = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
                n.size.height / d < 47.5f || (drawn.length <= 2 && spoken !in narrowOk && n.size.width / d < 47.5f)
            }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?"
                "\"$label\" ${(n.size.width / d).toInt()} × ${(n.size.height / d).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: targets under 48 dp:\n" + small.joinToString("\n") { "- $it" })
        val flat = listOf("switch-pill", "setting-chip").flatMap { tag ->
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
                .filter { inside(it) && it.size.height / d < 30f }
                .map { "$tag ${(it.size.height / d).toInt()} dp tall" }
        }
        if (flat.isNotEmpty()) fail("$name: pills drawn too small:\n" + flat.joinToString("\n") { "- $it" })
    }

    /**
     * Walk-through 2026-11-08 A13: no bare glyph stands in for a fold mark or a ⋯ button ("⌄", "˅", "^", "⋯" as a
     * line's whole text), and the drawn marks that replace them are whole: each `fold-chevron` at least
     * [os.meka.android.designsystem.FoldMarkSpec.CHEVRON_DP] and each `more-disc` at least
     * [os.meka.android.designsystem.FoldMarkSpec.MORE_DISC_DP]. Returns how many drawn marks were on screen.
     */
    fun assertDrawnMarks(compose: ComposeContentTestRule, name: String): Int {
        val d = compose.density.density
        val bare = setOf("⌄", "˅", "^", "⌃", "⋯")
        val glyphs = compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.trim() }
            .filter { it in bare }
        if (glyphs.isNotEmpty()) fail("$name: bare glyphs on screen: ${glyphs.joinToString()}")
        val spec = os.meka.android.designsystem.FoldMarkSpec
        var count = 0
        val small = listOf("fold-chevron" to spec.CHEVRON_DP, "more-disc" to spec.MORE_DISC_DP).flatMap { (tag, min) ->
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
                .also { count += it.size }
                .filter { it.size.height / d < min - 0.5f || it.size.width / d < min - 0.5f }
                .map { "$tag ${(it.size.width / d).toInt()} × ${(it.size.height / d).toInt()} dp" }
        }
        if (small.isNotEmpty()) fail("$name: marks drawn too small:\n" + small.joinToString("\n") { "- $it" })
        return count
    }

    /**
     * Walk-through A15 (2026-11-02): each by-hand state's shot was taken as the screen opened, so it was byte for byte
     * another state's shot and nothing the hand did was ever looked at. Call this at the end of a by-hand walk: it lets
     * the screen settle, shoots it again over the opening shot, and fails if the picture didn't change or matches
     * another shot of the same area.
     */
    fun reshootAfterHand(compose: ComposeContentTestRule, area: String, name: String, settleMs: Long = 1_000) {
        val file = File(path(area, name))
        val opening = file.takeIf { it.exists() }?.readBytes()
        repeat((settleMs / 100).toInt().coerceAtLeast(1)) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(file.path)
        assertNotACopy(name, file, opening, "the opening shot")
    }

    /**
     * Walk-through 2026-11-08 A20: true when the node tagged [tag] already sits wholly on screen, so a shot taken after
     * "scrolling to it" would be byte for byte the shot before (the scroll has nothing to do). A test then takes no shot of
     * its own for that state: the earlier shot already shows it.
     */
    fun wholeOnScreen(compose: ComposeContentTestRule, tag: String): Boolean {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        return compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.boundsInRoot.top >= screen.top && it.boundsInRoot.bottom <= screen.bottom }
    }

    /**
     * Walk-through 2026-11-08 A20 ("one = many above the fold"): true when any piece of [texts] is at least partly on
     * screen. A one-thing state whose only difference from the many-things state sits lower down (Mail's accounts below
     * its switches, Fasting's week below the ring) draws the same top as the many state when none of them is: its top
     * shot is then a copy, so the test keeps only its `-below` shot (when the page fits, there is no -below and the top stays).
     * Text is read as plain words (no-break spaces as spaces).
     */
    fun anyOnScreen(compose: ComposeContentTestRule, texts: List<String>): Boolean {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        return texts.any { t ->
            compose.onAllNodes(plainText(t, substring = true) or hasContentDescription(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                .any { it.boundsInRoot.bottom > screen.top && it.boundsInRoot.top < screen.bottom && it.boundsInRoot.height > 0f }
        }
    }

    /**
     * Walk-through A15: a screen longer than the Fold's showed only its top, so a state whose difference sits lower
     * down (Mail's second and third accounts, AI's last rows) shot the same as another. Swipes [tag]'s node (default the
     * whole screen) up until the node showing [lastText] is wholly on screen, then shoots `<name>-below`; [settle] runs
     * the screen's own clock after each swipe. Fails if [lastText] never comes on screen, or the shot repeats another
     * of the area. Nothing is shot when it was on screen from the start (the opening shot already shows it).
     */
    fun shootBelow(compose: ComposeContentTestRule, area: String, name: String, lastText: String, settle: () -> Unit,
                   tag: String = "screen", maxSwipes: Int = 16): Boolean {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun onScreen() = compose.onAllNodes(hasText(lastText, substring = true) or hasContentDescription(lastText, substring = true),
            useUnmergedTree = true).fetchSemanticsNodes().any { it.boundsInRoot.top >= screen.top && it.boundsInRoot.bottom <= screen.bottom }
        var swipes = 0
        while (!onScreen() && swipes < maxSwipes) {
            compose.onNodeWithTag(tag).performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.25f, durationMillis = 400) }
            settle()
            swipes++
        }
        if (!onScreen()) fail("$name: \"$lastText\" never came on screen after $swipes swipes")
        if (swipes == 0) return false
        val file = File(path(area, "$name-below"))
        compose.onNodeWithTag("screen").captureRoboImage(file.path)
        assertNotACopy("$name-below", file, null, null)
        return true
    }

    private fun assertNotACopy(name: String, file: File, opening: ByteArray?, openingName: String?) {
        if (!file.exists()) return
        val shot = file.readBytes()
        if (opening != null && opening.contentEquals(shot)) fail("$name: the shot is byte for byte $openingName; nothing the hand did shows")
        file.parentFile?.listFiles { f -> f.extension == "png" && f.name != file.name }
            ?.firstOrNull { runCatching { it.readBytes() }.getOrNull()?.contentEquals(shot) == true }
            ?.let { fail("$name: the shot is byte for byte ${it.name}, so this state goes unreviewed") }
    }
}
