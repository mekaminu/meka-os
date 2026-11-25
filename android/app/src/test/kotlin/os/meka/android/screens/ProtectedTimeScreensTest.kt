package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.ProtectedTimePane
import os.meka.android.notify.NOTIFY_CHOICE_ROW_TAG
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ProtectedChoiceRules
import os.meka.core.domain.ProtectedMailRules
import os.meka.core.domain.ProtectedRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 49 (build plan: "the rest of Ask's places' shots", Protected time first): Ask → More →
 * Protected time off, with evenings from 19:00, and with evenings and weekends protected and tonight lifted just this
 * once, each on the Fold's cover and inner screens, Dark (and the full one in Light). Saved under `protected/` and read
 * for nonsense, cut lines, broken words, split dates, times and numbers, the pane's own lines leaving a word alone or
 * starting a line with a dot, each choice row's value on one line, and every
 * button answering a 48 dp touch. Walk-through week 5, A17: no open chip rows; each choice is a row opening a sheet. By
 * hand: Evenings from → 19:30 moves the evening and closes the sheet; Work mail → Shows as it comes and back; Open tonight, just this once lifts tonight and says
 * so, and Undo puts it back. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ProtectedTimeScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(49), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> core.setProtectedTime(true, 19 * 60, false)
            Fill.MANY -> {
                core.setProtectedTime(true, 19 * 60, true)
                core.setProtectedOpenToday(true)
            }
        }
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /**
     * [text] is on screen (as the pane draws it). The hand test waits for the words as well as the core's state: the
     * pane's line comes back from the core thread a moment after the state changes, so waiting on the state alone
     * raced it (CI run 1153).
     */
    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { it.contains(text) }

    /** Every button drawn answers a 48 dp touch (the chips were ~32 dp). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, shoot: Boolean = true): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { ProtectedTimePane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        if (shoot) compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("protected", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Protected time")
        val v = core.protectedView.value
        val lines = listOfNotNull(v.summary, v.todayLine, v.mailLine, ProtectedRules.NOTE, if (v.on) ProtectedMailRules.NOTE else null)
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        // Walk-through week 5, A17: no open chip row on the page; Evenings from, Weekends and (while on) Work mail are
        // each one row saying what's chosen.
        val radios = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
        if (radios.isNotEmpty()) fail("$name: ${radios.size} open choice chips on the page; they belong in a sheet")
        val chips = listOf("Off") + ProtectedRules.FROM_CHOICES.map(ProtectedRules::fromChip) + listOf("Open", "Protected")
        chips.forEach { label ->
            if (compose.onAllNodes(hasText(label) and hasClickAction() and !hasTestTag(NOTIFY_CHOICE_ROW_TAG)).fetchSemanticsNodes().isNotEmpty()) fail("$name: a \"$label\" chip on the page")
        }
        val rows = listOfNotNull(ProtectedChoiceRules.eveningsRow(v), ProtectedChoiceRules.weekendsRow(v), if (v.on) ProtectedChoiceRules.mailRow(v) else null)
        val drawn = compose.onAllNodesWithTag(NOTIFY_CHOICE_ROW_TAG).fetchSemanticsNodes().size
        if (drawn != rows.size) fail("$name: $drawn choice rows, not ${rows.size}")
        rows.forEach { r ->
            if (compose.onAllNodes(hasContentDescription(r.spoken) and hasClickAction()).fetchSemanticsNodes().isEmpty())
                fail("$name: no row reading \"${r.title} · ${r.value}\"")
        }
        ScreenShots.assertEachOneLine(compose, name, rows.map { it.value })
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.on) fail("$name: protected time is on with nothing set ($v)")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.OFF_LINE))
            }
            Fill.ONE -> {
                if (v.todayLine != "Protected from 19:00 tonight") fail("$name: today's line reads \"${v.todayLine}\"")
                // Week 5 A8: the button is a verb, not a status reading as the opposite of today's line.
                ScreenShots.assertShows(compose, name, "Open tonight, just this once")
                if (onScreen("Not protected tonight")) fail("$name: \"Not protected tonight\" sits under \"${v.todayLine}\"")
                ScreenShots.assertShows(compose, name, ProtectedMailRules.ON)
                ScreenShots.assertShows(compose, name, "19:00")
            }
            Fill.MANY -> {
                if (!v.openToday) fail("$name: tonight isn't lifted ($v)")
                ScreenShots.assertShows(compose, name, "Protect tonight again")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.Lifted.EVENING.line))
            }
        }
        return core
    }

    /**
     * Works by hand: 19:30 moves the evening (the summary follows); Open tonight, just this once lifts tonight and says
     * what changed; Protect tonight again puts it back.
     */
    private fun byHand(core: MekaCore) {
        val name = "protected-by-hand"
        fun tap(desc: String) {
            val n = compose.onAllNodes(hasContentDescription(desc) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$desc\" to tap")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        fun sheetOpen() = compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()

        // A17: Evenings from opens its sheet; 19:30 saves and closes it.
        tap(ProtectedChoiceRules.eveningsRow(core.protectedView.value).spoken)
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: Evenings from didn't open its sheet")
        tap(ProtectedChoiceRules.eveningsSheet(core.protectedView.value).choices.first { it.label == "19:30" }.spoken)
        val moved = LoneWordGlue.kept(ProtectedRules.summary(os.meka.core.domain.ProtectedSetting(true, 19 * 60 + 30, false)))
        // The sheet first: while its window is up there are two roots, and reading the screen's words needs one.
        until { core.protectedView.value.fromMin == 19 * 60 + 30 && !sheetOpen() && onScreen(moved) }
        if (core.protectedView.value.fromMin != 19 * 60 + 30) fail("$name: 19:30 left the evening at ${core.protectedView.value.fromMin}")
        if (sheetOpen()) fail("$name: the sheet stayed open after a choice")

        // Work mail: Shows as it comes, then back to Waits till morning.
        tap(ProtectedChoiceRules.mailRow(core.protectedView.value).spoken)
        until { sheetOpen() }
        tap(ProtectedChoiceRules.mailSheet(core.protectedView.value).choices.first { it.name == ProtectedChoiceRules.SHOW }.spoken)
        until { !core.protectedView.value.holdMail && !sheetOpen() }
        if (core.protectedView.value.holdMail) fail("$name: Shows as it comes left work mail held")
        tap(ProtectedChoiceRules.mailRow(core.protectedView.value).spoken)
        until { sheetOpen() }
        tap(ProtectedChoiceRules.mailSheet(core.protectedView.value).choices.first { it.name == ProtectedChoiceRules.HOLD }.spoken)
        until { core.protectedView.value.holdMail && !sheetOpen() }
        if (!core.protectedView.value.holdMail) fail("$name: Waits till morning didn't come back")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.summary(os.meka.core.domain.ProtectedSetting(true, 19 * 60 + 30, false))))

        val lift = compose.onAllNodes(hasText("Open tonight, just this once") and hasClickAction())
        if (lift.fetchSemanticsNodes().isEmpty()) fail("$name: no Open tonight, just this once")
        lift[0].performSemanticsAction(SemanticsActions.OnClick)
        val done = LoneWordGlue.kept(ProtectedRules.Lifted.EVENING.done)
        until { core.protectedView.value.openToday && onScreen(done) }
        if (!core.protectedView.value.openToday) fail("$name: Open tonight left tonight protected")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.Lifted.EVENING.done))
        // Week 5 A11: the by-hand shot shows what the taps did (19:30, tonight opened), not the one state again.
        turns()
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("protected", name))

        val undo = compose.onAllNodes(hasText("Protect tonight again") and hasClickAction())
        if (undo.fetchSemanticsNodes().isEmpty()) fail("$name: no Undo once tonight is lifted")
        undo[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !core.protectedView.value.openToday && onScreen("Open tonight, just this once") }
        if (core.protectedView.value.openToday) fail("$name: Undo left tonight lifted")
        ScreenShots.assertShows(compose, name, "Open tonight, just this once")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE, shoot = false)) }
}
