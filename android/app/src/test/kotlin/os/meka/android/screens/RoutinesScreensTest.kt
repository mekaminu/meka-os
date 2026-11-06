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
import androidx.compose.ui.test.junit4.createComposeRule
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
import os.meka.android.ask.RoutinesPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.DateGlue
import os.meka.core.domain.RoutineRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate for Ask → Daily → Routines (slice 1): no routines yet (the four starters) and three added with one
 * paused, on the Fold's cover and inner screens, Dark (and Light). Saved under `routines/` and read for nonsense, cut
 * lines, broken words, split dates and clocks, lone words and stray dots, and every button answering a 48 dp touch.
 * By hand: Add puts a starter under YOUR ROUTINES, the switch pauses and resumes it, Remove takes it away and offers the
 * starter again. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class RoutinesScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private enum class Fill { EMPTY, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(67), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(done: () -> Boolean) {
        repeat(60) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

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

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill == Fill.MANY) {
                core.addStarterRoutine("sunday-reset")
                core.addStarterRoutine("study-night")
                val payday = core.addStarterRoutine("payday-check")?.id
                if (payday != null) core.setRoutineOn(payday, false)
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    RoutinesPane(core, onClose = {})
                }
            }
        }
        val v = core.routinesView.value
        until { onScreen(RoutineRules.TITLE) && (fill == Fill.EMPTY || onScreen(RoutineRules.YOURS.uppercase())) }
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("routines", name))
        val lines = listOf(v.summary, RoutineRules.RULE_LINE) + v.rows.flatMap { listOfNotNull(it.name, it.line, it.next) } +
            v.starters.flatMap { listOf(it.name, it.line, it.whenLine) }
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, RoutineRules.TITLE)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.summary != RoutineRules.EMPTY_LINE) fail("$name: the summary reads \"${v.summary}\"")
                if (count("routine-row") != 0) fail("$name: rows with no routines")
                if (count("routine-starter") != 4) fail("$name: ${count("routine-starter")} starters, not 4")
            }
            Fill.MANY -> {
                if (v.summary != "2 on · 1 paused") fail("$name: the summary reads \"${v.summary}\"")
                if (count("routine-row") != 3) fail("$name: ${count("routine-row")} rows, not 3")
                if (!onScreen("Next: tonight")) fail("$name: Study night's next run isn't said")
                if (!onScreen("Paused · ")) fail("$name: the paused routine isn't marked")
            }
        }
        return core
    }

    /** By hand: Add a starter, pause it with its switch and switch it back on, then Remove it. */
    private fun byHand(core: MekaCore) {
        val name = "routines-by-hand"
        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        fun sunday() = core.routinesView.value.rows.firstOrNull { it.name == "Sunday reset" }
        tap("${RoutineRules.ADD} Sunday reset")
        until { sunday() != null && count("routine-row") == 1 }
        if (sunday() == null) fail("$name: Add didn't add Sunday reset")
        if (!onScreen("Added Sunday reset")) fail("$name: Add didn't say so")
        val row = compose.onAllNodes(hasTestTag("routine-row") and hasClickAction())
        row[0].performSemanticsAction(SemanticsActions.OnClick)
        until { sunday()?.on == false }
        if (sunday()?.on != false) fail("$name: the switch didn't pause it")
        compose.onAllNodes(hasTestTag("routine-row") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { sunday()?.on == true }
        if (sunday()?.on != true) fail("$name: the switch didn't switch it back on")
        tap("${RoutineRules.REMOVE} Sunday reset")
        until { sunday() == null && count("routine-starter") == 4 }
        if (sunday() != null) fail("$name: Remove left it")
        if (count("routine-starter") != 4) fail("$name: the starter isn't offered again")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.EMPTY)) }
}
