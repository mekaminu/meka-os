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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
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
import os.meka.android.calendar.DAY_LIST_COLUMN_TAG
import os.meka.android.calendar.DAY_LIST_SPREAD_TAG
import os.meka.android.calendar.DayListSpread
import os.meka.core.domain.DayListBoardRules
import os.meka.core.domain.BoardArea
import os.meka.core.domain.BoardAreaRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * The Week board on the open Fold as Day lists side by side (approved 2026-11-08, `docs/design/day-list/OpenSpread.dc.html`),
 * on the inner screen at 19:10 on a Wednesday: the header "Week 43 · … planned", the 1 · 2 · 3 days switch and the strip,
 * then three Day lists across (Wed · Thu · Fri); 2 days widens them, Sunday's pill brings Sat · Sun (held inside the week),
 * and Enter in Sunday's bar adds to Sunday. Every button answers a 48 dp touch. Dark and Light; saved under `day-list/`
 * beside the mockup. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class DayListSpreadScreensTest {
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
            secureRandom = Random(37), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            val today = core.todayEpochDay()
            core.addToDayList("Complete feedbacks 30m #work", today)
            core.addToDayList("Call the school about Friday 15m #family", today)
            val mot = core.addToDayList("Book the MOT 15m", today)!!.ids.single()
            core.complete(mot)
            core.addToDayList("Study module 6 1h30 21:00", today + 2)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    DayListSpread(core, onAgenda = {}, onAddEvent = {}, onPlanWeek = {})
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

    private fun described(text: String) = compose.onAllNodes(
        SemanticsMatcher("described $text") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true },
    )

    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n -> (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?") + " ${(n.size.height / compose.density.density).toInt()} dp" }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun columns() = compose.onAllNodesWithTag(DAY_LIST_COLUMN_TAG).fetchSemanticsNodes().size

    private fun run(theme: ThemeChoice, name: String) {
        val core = setUp(theme)
        compose.onNodeWithTag(DAY_LIST_SPREAD_TAG).assertExists()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("day-list", name))
        val board = core.weekBoard.value
        val week = board.weeks.first { w -> w.days.any { it.isToday } }
        val head = DayListBoardRules.head(week)
        assertTrue(shows(head.month) && shows(head.weekLine), "$name: the header isn't the mockup's (${head.weekLine})")
        assertTrue(shows("1 day") && shows("2 days") && shows("3 days"), "$name: no 1 · 2 · 3 days switch")
        assertTrue(described("Wednesday 21 October, today, 1h planned").fetchSemanticsNodes().isNotEmpty(), "$name: today's pill doesn't carry its length")
        // Three days across by default, today first: Wed · Thu · Fri, each its own Day list.
        assertEquals(3, columns(), "$name: not three days across")
        assertTrue(shows("Wed 21 · today") && shows("Thu 22") && shows("Fri 23"), "$name: the three days' headings aren't shown")
        assertTrue(shows("Complete feedbacks") && shows("1 of 3 done") && shows("Study module 6"), "$name: the days' lists aren't side by side")
        assertTrue(shows("Add · Enter for next"), "$name: the narrow add bar's prompt isn't shown")
        // A narrow column stacks time · title · area, so the title keeps the width (it was squeezed to "C …", ~10 dp; whole it is ~100 dp).
        val title = compose.onAllNodes(hasText("Complete feedbacks"), useUnmergedTree = true).fetchSemanticsNodes().first()
        assertTrue(title.size.width / compose.density.density > 80f, "$name: a task's title is squeezed (${title.size.width / compose.density.density} dp)")
        assertTouchTargets(name)

        // Two days: wider headings; a tap on Sunday's pill holds the spread inside the week (Sat · Sun).
        compose.onNode(hasText("2 days") and hasClickAction(), useUnmergedTree = true).performClick()
        until { columns() == 2 && shows("Wednesday 21 · today") }
        assertEquals(2, columns(), "$name: 2 days didn't show two lists")
        described("Sunday 25 October").fetchSemanticsNodes().ifEmpty { fail("$name: no Sunday pill") }
        described("Sunday 25 October")[0].performSemanticsAction(SemanticsActions.OnClick)
        until { shows("Saturday 24") && shows("Sunday 25") }
        assertTrue(shows("Saturday 24") && shows("Sunday 25") && !shows("Complete feedbacks"), "$name: Sunday didn't bring Sat · Sun")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("day-list", "$name-two"))

        // Each column adds to its own day: Enter in Sunday's bar lands on Sunday.
        val sundayBar = hasSetTextAction() and SemanticsMatcher("Sunday's add bar") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains("Add to Sunday") } == true
        }
        compose.onNode(sundayBar).performTextInput("Gym 45m")
        compose.onNode(sundayBar).performImeAction()
        val sun = board.todayEpochDay + 4
        until { core.weekBoard.value.day(sun)?.cards?.any { it.title == "Gym" } == true }
        assertEquals(45, core.weekBoard.value.day(sun)!!.cards.single { it.title == "Gym" }.task.estimateMinutes)
    }

    @Test @Config(qualifiers = ScreenShots.INNER) fun boardOpenDark() = run(ThemeChoice.DARK, "board-open-dark")
    @Test @Config(qualifiers = ScreenShots.INNER) fun boardOpenLight() = run(ThemeChoice.LIGHT, "board-open-light")
}
