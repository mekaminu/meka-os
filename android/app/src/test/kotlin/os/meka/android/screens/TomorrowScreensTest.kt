package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.TomorrowPane
import os.meka.android.today.TomorrowPeekCard
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Tomorrow peek and sheet (Meka 2026-10-26: "I asked the AI to add a task for tomorrow, it did, but on this screen how
 * do I see tomorrow's stuff?"), on the Fold at 19:10 on a Wednesday with two tasks dated tomorrow (one at 07:40): the peek
 * card says what's on and the sheet lists both; **Today** moves one to today in the same frame and says so; the tick
 * ring completes the other. Every button answers a 48 dp touch. Dark and Light; saved under `tomorrow/`. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TomorrowScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_606_200_000L // Wed 21 Oct 2026, 19:10 in London

    private fun setUp(theme: ThemeChoice): Pair<MekaCore, Pair<String, String>> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(27), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        val ids = runBlocking {
            core.tick()
            val tomorrow = core.todayEpochDay() + 1
            val gym = core.addTask("Gym before work")
            core.setWhen(gym, tomorrow, 7 * 60 + 40)
            val school = core.addTask("Ring the school about the trip")
            core.setWhen(school, tomorrow, null)
            core.tick()
            gym to school
        }
        compose.mainClock.autoAdvance = false
        // Read once, outside composition: the peek as Today would draw it.
        val peek = core.shutdownView.value.peek
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    Column {
                        peek?.let { TomorrowPeekCard(it, onOpen = {}) }
                        TomorrowPane(core, onClose = {})
                    }
                }
            }
        }
        turns()
        return core to ids
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
        val (core, ids) = setUp(theme)
        val (gym, school) = ids
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("tomorrow", name))
        val peek = assertNotNull(core.shutdownView.value.peek)
        assertTrue(peek.line.endsWith("2 tasks · first 07:40 Gym before work"), peek.line)
        // Lines are drawn through LoneWordGlue (the last words held together), so look for them as drawn.
        assertTrue(shows(LoneWordGlue.kept(peek.line)), "$name: the peek isn't shown")
        assertTrue(shows("Gym before work") && shows("Ring the school about the trip"), "$name: tomorrow's tasks aren't listed")
        assertTouchTargets(name)

        // Today on the school task: it leaves tomorrow and the sheet says where it went.
        val todays = compose.onAllNodes(hasText("Today") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        if (todays.size < 2) fail("$name: no Today on each task")
        compose.onAllNodes(hasText("Today") and hasClickAction(), useUnmergedTree = true)[1].performSemanticsAction(SemanticsActions.OnClick)
        until { core.shutdownView.value.tomorrow.rows.none { it.id == "t-$school" } }
        assertTrue(core.shutdownView.value.left.any { it.task.id == school })
        val moved = LoneWordGlue.kept("Moved “Ring the school about the trip” to Today")
        until { shows(moved) }
        assertTrue(shows(moved), "$name: the move isn't said")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("tomorrow", "$name-moved"))
        assertEquals(listOf("t-$gym"), core.shutdownView.value.tomorrow.rows.map { it.id })
    }

    @Test fun tomorrowDark() = run(ThemeChoice.DARK, "fold-dark")
    @Test fun tomorrowLight() = run(ThemeChoice.LIGHT, "fold-light")
}
