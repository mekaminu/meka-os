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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.DayListPane
import os.meka.core.domain.BoardArea
import os.meka.core.domain.BoardAreaRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * The Day list, slice 1 (approved 2026-11-08), on the Fold's cover screen at 19:10 on a Wednesday with three tasks typed
 * in: the header's total and "1 of 3 done", the rows with their lengths and areas; typing "Pay the nursery 15m #money"
 * and pressing Enter adds it (Money, 15m) and clears the bar; a row's tap opens its sheet, where 1h sets the length.
 * Every button answers a 48 dp touch. Dark and Light; saved under `day-list/`. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class DayListScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_606_200_000L // Wed 21 Oct 2026, 19:10 in London

    private fun setUp(theme: ThemeChoice): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(31), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            val today = core.todayEpochDay()
            core.addToDayList("Complete feedbacks 30m #work", today)
            core.addToDayList("Call the school about Friday 15m #family", today)
            val mot = core.addToDayList("Book the MOT 15m", today)!!.ids.single()
            core.complete(mot)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    DayListPane(core, onClose = {})
                }
            }
        }
        turns()
        return core
    }

    private fun turns(ms: Long = 3_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(what: () -> Boolean) {
        var i = 0
        while (!what() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n -> (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?") + " ${(n.size.height / compose.density.density).toInt()} dp" }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun run(theme: ThemeChoice, name: String) {
        val core = setUp(theme)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("day-list", name))
        assertTrue(shows("Complete feedbacks") && shows("Call the school about Friday") && shows("Book the MOT"), "$name: the rows aren't listed")
        assertTrue(shows("1 of 3 done"), "$name: the done line isn't shown")
        assertTrue(shows("1h"), "$name: the day's total isn't shown")
        assertTrue(shows("Work") && shows("Family"), "$name: the areas aren't shown")
        assertTrue(shows("Paste a list") && shows("Fit times from"), "$name: Paste a list or Fit times is missing")
        assertTrue(shows("Say them"), "$name: Say them is missing")
        assertTouchTargets(name)

        // Rapid add: Enter adds the line with its length and area, and the bar clears for the next.
        compose.onNode(hasSetTextAction()).performTextInput("Pay the nursery 15m #money")
        compose.onNode(hasSetTextAction()).performImeAction()
        // The line is drawn through LoneWordGlue (the last words held together), so look for it as drawn.
        val addedLine = LoneWordGlue.kept("Added “Pay the nursery” · 15m")
        until { shows(addedLine) }
        assertTrue(shows(addedLine), "$name: the add isn't said")
        val day = core.weekBoard.value.day(core.weekBoard.value.todayEpochDay)!!
        val added = day.cards.single { it.title == "Pay the nursery" }
        assertEquals(15, added.task.estimateMinutes)
        assertEquals(BoardAreaRules.raw(BoardArea.MONEY), added.task.area)
        assertTrue(shows("2 of 4 done").not() && shows("1 of 4 done"), "$name: the header didn't count the new task")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("day-list", "$name-added"))

        // A row's tap opens its sheet; 1h sets the length.
        compose.onAllNodes(hasText("Complete feedbacks", substring = true) and hasClickAction(), useUnmergedTree = false)
            .fetchSemanticsNodes().ifEmpty { fail("$name: the row can't be tapped") }
        compose.onAllNodes(hasText("Complete feedbacks", substring = true) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { shows("LENGTH") && shows("MOVE TO") }
        assertTrue(shows("LENGTH") && shows("MOVE TO") && shows("Tomorrow"), "$name: the sheet didn't open")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("day-list", "$name-sheet"))
        compose.onAllNodes(hasText("1h") and hasClickAction(), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        val id = day.cards.single { it.title == "Complete feedbacks" }.id
        until { core.weekBoard.value.day(day.epochDay)?.cards?.single { it.id == id }?.task?.estimateMinutes == 60 }
        assertEquals(60, core.weekBoard.value.day(day.epochDay)!!.cards.single { it.id == id }.task.estimateMinutes)
    }

    /**
     * Drag to reorder (slice 1c): each open row has a ≡ grip a screen reader can move; Move down on the first row's
     * grip puts it second on the board (Today, the board and Fit times follow), and the done row has no grip.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun dragGripsReorderTheDay() {
        val core = setUp(ThemeChoice.DARK)
        val rows = core.dayList(core.weekBoard.value, core.weekBoard.value.todayEpochDay, timesOn = true)!!.rows
        val (first, second) = rows[0].title to rows[1].title
        val grip = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Drag to reorder $first")), useUnmergedTree = true)
        grip.assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Drag to reorder Book the MOT")), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "the done row has a grip") }
        val down = grip.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Move down" }
        compose.runOnIdle { down.action() }
        fun open() = core.weekBoard.value.day(core.weekBoard.value.todayEpochDay)!!.openCards.map { it.title }
        until { open().firstOrNull() == second }
        assertEquals(listOf(second, first), open())
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("day-list", "fold-dark-moved"))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun dayListDark() = run(ThemeChoice.DARK, "fold-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun dayListLight() = run(ThemeChoice.LIGHT, "fold-light")
}
