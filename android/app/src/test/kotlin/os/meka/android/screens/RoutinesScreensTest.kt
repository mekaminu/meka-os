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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import os.meka.android.ask.NEW_ROUTINE_TAG
import os.meka.android.ask.RoutinesPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.DateGlue
import os.meka.core.domain.RoutineOutcome
import os.meka.core.domain.RoutineIdeas
import os.meka.core.domain.RoutineRules
import os.meka.core.domain.RoutineSentence
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate for Ask → Daily → Routines (slice 1): no routines yet (the four starters) and three added with one
 * paused, on the Fold's cover and inner screens, Dark (and Light). Saved under `routines/` and read for nonsense, cut
 * lines, broken words, split dates and clocks, lone words and stray dots, and every button answering a 48 dp touch.
 * By hand: Add puts a starter under YOUR ROUTINES, the switch pauses and resumes it, its card opens its page (shot and
 * read the same way) where a label tap moves the label on, ‹ Routines comes back, and Remove takes it away and offers
 * the starter again. Invented data only (rule 13).
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
        tap("Pause Sunday reset")
        until { sunday()?.on == false }
        if (sunday()?.on != false) fail("$name: the switch didn't pause it")
        tap("Switch on Sunday reset")
        until { sunday()?.on == true }
        if (sunday()?.on != true) fail("$name: the switch didn't switch it back on")
        // Slice 2: the card opens the routine's page; a tap on a step's label moves it on; ‹ Routines comes back.
        compose.onAllNodes(hasTestTag("routine-row") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { count("routine-step") == 4 }
        if (count("routine-step") != 4) fail("$name: the page doesn't show Sunday reset's 4 steps")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("routines", "cover-dark-page"))
        val d = core.routinesView.value.detail(sunday()!!.id)!!
        val lines = listOf(d.name, d.whenLine, d.stepsNote, d.history) + d.steps.map { it.title }
        ScreenShots.assertSensible(compose, "$name-page")
        ScreenShots.assertNoBrokenWords(compose, "$name-page")
        ScreenShots.assertDatesWhole(compose, "$name-page")
        ScreenShots.assertNoLoneWords(compose, "$name-page", lines)
        ScreenShots.assertNoDotStartingALine(compose, "$name-page", lines)
        ScreenShots.assertNoDotEndingALine(compose, "$name-page", lines)
        assertTouchTargets("$name-page")
        tap(d.steps[0].spoken)
        until { core.routinesView.value.detail(d.id)?.steps?.first()?.badge == "Asks you" }
        if (core.routinesView.value.detail(d.id)?.steps?.first()?.badge != "Asks you") fail("$name: the label didn't move on")
        // Slice 3: Run now shows the Running now card; Carry on, Carry on (Tidy the inbox is Inbox autopilot's, so it
        // goes by), Skip this step, and Last time says what each step came to.
        fun page() = core.routinesView.value.detail(d.id)
        tap("${RoutineRules.RUN_NOW}: ${d.name}")
        until { page()?.running != null && count("routine-running") == 1 }
        if (count("routine-running") != 1) fail("$name: Run now didn't show the Running now card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("routines", "cover-dark-running"))
        val c = page()!!.running!!
        val runLines = listOf(c.name, c.stepTitle, "${c.progress} · ${c.place} · ${c.state}")
        ScreenShots.assertSensible(compose, "$name-running")
        ScreenShots.assertNoBrokenWords(compose, "$name-running")
        ScreenShots.assertNoLoneWords(compose, "$name-running", runLines)
        ScreenShots.assertNoDotEndingALine(compose, "$name-running", runLines)
        assertTouchTargets("$name-running")
        if (!onScreen(RoutineRules.WAITING)) fail("$name: an Asks you step doesn't say it's waiting on you")
        tap("${RoutineRules.CARRY_ON}: ${c.stepTitle}")
        until { page()?.running?.progress == "Step 2 of 4" }
        tap("${RoutineRules.CARRY_ON}: ${page()!!.running!!.stepTitle}")
        until { page()?.running?.progress == "Step 4 of 4" }
        if (page()?.running?.progress != "Step 4 of 4") fail("$name: the run didn't let Inbox autopilot's step go by")
        tap("${RoutineRules.SKIP_STEP}: ${page()!!.running!!.stepTitle}")
        until { page()?.running == null && count("routine-running") == 0 }
        if (count("routine-running") != 0) fail("$name: the card stayed after the last step")
        if (!onScreen("3 done, 1 skipped")) fail("$name: Last time doesn't say how the run went")
        if (!onScreen(RoutineOutcome.LEFT.label)) fail("$name: Last time doesn't list the steps")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("routines", "cover-dark-last-time"))
        tap("Back to ${RoutineRules.BACK}")
        until { count("routine-page") == 0 && count("routine-row") == 1 }
        if (count("routine-page") != 0) fail("$name: ‹ Routines left the page open")
        tap("${RoutineRules.REMOVE} Sunday reset")
        until { sunday() == null && count("routine-starter") == 4 }
        if (sunday() != null) fail("$name: Remove left it")
        if (count("routine-starter") != 4) fail("$name: the starter isn't offered again")
    }

    /**
     * Slice 4 by hand: + New opens New routine; the mockup's sentence typed becomes WHEN Sundays 19:00 and four steps
     * (shot and read); a part that would text someone is left out and named; a tap on Plan next week's label moves it
     * on; Save routine adds it switched on and the list says when it first runs.
     */
    private fun newRoutine(core: MekaCore) {
        val name = "routines-new"
        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        tap(RoutineSentence.NEW_SPOKEN)
        until { count(NEW_ROUTINE_TAG) == 1 }
        if (count(NEW_ROUTINE_TAG) != 1) fail("$name: + New didn't open New routine")
        compose.onNodeWithTag("new-routine-field").performTextInput(RoutineSentence.HINT + ", and text Sam I'm home")
        until { count("new-routine-step") == 4 }
        if (count("new-routine-step") != 4) fail("$name: ${count("new-routine-step")} steps, not 4")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("routines", "cover-dark-new"))
        val d = RoutineSentence.draft(RoutineSentence.HINT + ", and text Sam I'm home")
        val lines = listOfNotNull(RoutineSentence.FOOT, RoutineSentence.NOTE, d.leftOut, d.whenTile) + d.steps.map { it.title }
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        if (!onScreen("Sundays 19:00")) fail("$name: WHEN doesn't say Sundays 19:00")
        if (count("new-routine-left-out") != 1 || !onScreen("Left out: Text Sam")) fail("$name: the text to Sam isn't named as left out")
        tap(d.steps[1].spoken)
        until { onScreen("Just show") && compose.onAllNodes(hasContentDescription(d.steps[1].spoken)).fetchSemanticsNodes().isEmpty() }
        tap("${RoutineSentence.SAVE}: ${d.name}, ${d.whenTile}, 4 steps")
        until { core.routinesView.value.rows.isNotEmpty() && count(NEW_ROUTINE_TAG) == 0 }
        val row = core.routinesView.value.rows.singleOrNull() ?: fail("$name: Save didn't add it")
        if (row.name != "Sunday evening") fail("$name: saved as \"${row.name}\"")
        val page = core.routinesView.value.detail(row.id)!!
        if (page.steps[1].badge != "Just show") fail("$name: the label he chose wasn't kept (${page.steps[1].badge})")
        if (page.steps.any { it.title.contains("Sam") }) fail("$name: a step that texts someone was saved")
        if (!onScreen("Saved Sunday evening · first run")) fail("$name: the list doesn't say it was saved")
    }

    /**
     * Slice 5: IDEAS FROM YOUR WEEK. With a bin collected on Tuesdays, Bin night shows as a dashed tile saying why; a tap
     * opens New routine with its sentence filled in (WHEN Mondays and its step), Save routine adds it, and the idea goes.
     */
    @Test @Config(qualifiers = ScreenShots.COVER) fun ideas() {
        val name = "routines-ideas"
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            check(!core.addBin("Black bin every Tuesday").refused)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { RoutinesPane(core, onClose = {}) }
            }
        }
        until { onScreen(RoutineIdeas.TITLE.uppercase()) }
        val idea = core.routinesView.value.ideas.singleOrNull() ?: fail("$name: ${core.routinesView.value.ideas.size} ideas, not 1")
        if (idea.name != "Bin night") fail("$name: the idea is \"${idea.name}\"")
        compose.onNodeWithTag(ROUTINES_PANE_TAG).performScrollToNode(hasTestTag("routine-idea"))
        turns()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("routines", "cover-dark-ideas"))
        val lines = listOf(idea.name, idea.why, RoutineIdeas.NOTE)
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        if (count("routine-idea") != 1 || !onScreen("Bins go out on Tuesdays")) fail("$name: Bin night's tile doesn't say why")
        compose.onAllNodes(hasContentDescription(idea.spoken) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { count(NEW_ROUTINE_TAG) == 1 && onScreen("Mondays") }
        if (!onScreen("Mondays")) fail("$name: New routine didn't open with Bin night's sentence")
        val d = RoutineSentence.draft(idea.sentence)
        compose.onAllNodes(hasContentDescription("${RoutineSentence.SAVE}: ${d.name}, ${d.whenTile}, 1 step") and hasClickAction())
            .fetchSemanticsNodes().ifEmpty { fail("$name: no Save routine for Bin night") }
        compose.onAllNodes(hasContentDescription("${RoutineSentence.SAVE}: ${d.name}, ${d.whenTile}, 1 step") and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { core.routinesView.value.rows.isNotEmpty() && count(NEW_ROUTINE_TAG) == 0 }
        val v = core.routinesView.value
        if (v.rows.singleOrNull()?.name != "Bin night") fail("$name: saved as ${v.rows.map { it.name }}")
        if (v.ideas.isNotEmpty()) fail("$name: Bin night is still offered after it was saved")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun newRoutine() { newRoutine(pane("cover-dark-new-list", Fill.EMPTY)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.EMPTY)) }
}
