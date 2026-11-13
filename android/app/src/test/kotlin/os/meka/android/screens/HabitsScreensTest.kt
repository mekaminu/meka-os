package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasContentDescription
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.goals.HabitsPage
import os.meka.core.domain.HabitMark
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The Habits screen (approved 2026-11-11, `docs/design/habits/Habits.dc.html`; slice 1) on the Fold's cover and inner
 * screens, Dark and Light, with eight habits in three lists and with none; saved under `habits/` and read for
 * nonsense and broken words. Then by hand: the circle cycles done → missed → clear, a day on the strip marks that
 * day, and + Add adds an Avoid habit into Night time. Invented habits only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class HabitsScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_794_477_600_000L // Thu 12 Nov 2026, 10:00 in London
    private val dayMs = 86_400_000L
    private var clock = now

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(31), timeZone = { TimeZone.of("Europe/London") }, nowMs = { clock },
    )

    private fun settle() {
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
    }

    private val ids = mutableMapOf<String, String>()

    private fun prepared(full: Boolean, theme: ThemeChoice): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        clock = now - 6 * dayMs
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            if (full) {
                listOf(
                    Triple("Brush teeth", false, "Morning"), Triple("Drink water", false, "Morning"),
                    Triple("Walk 20 minutes", false, "All day"), Triple("No shop coffee", true, "All day"),
                    Triple("No biscuits", true, "All day"), Triple("No cake", true, "Night time"),
                    Triple("Floss", false, "Night time"), Triple("Read 10 pages", false, "Night time"),
                ).forEach { (name, avoid, list) -> ids[name] = core.addHabitToList(name, avoid, list) }
                clock = now
                val today = now / dayMs // 10:00 in London is 10:00 UTC in November
                for (back in 0..6) {
                    core.setHabitMark(ids.getValue("Brush teeth"), today - back, HabitMark.DONE)
                    core.setHabitMark(ids.getValue("No shop coffee"), today - back, HabitMark.DONE)
                }
                core.setHabitMark(ids.getValue("Drink water"), today, HabitMark.DONE)
                core.setHabitMark(ids.getValue("No biscuits"), today - 1, HabitMark.MISSED)
                core.setHabitMark(ids.getValue("Read 10 pages"), today - 2, HabitMark.MISSED)
            }
            clock = now
            core.tick()
        }
        return core
    }

    private fun show(core: MekaCore) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    HabitsPage(core, onClose = {})
                }
            }
        }
        settle()
    }

    private fun shoot(name: String, full: Boolean, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = prepared(full, theme)
        show(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("habits", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Habits")
        ScreenShots.assertShows(compose, name, "+ Add")
        ScreenShots.assertShows(compose, name, "Thu")
        if (full) {
            ScreenShots.assertShows(compose, name, "3 of 8 done today")
            ScreenShots.assertShows(compose, name, "best streak 7 days")
            ScreenShots.assertShows(compose, name, "Morning")
            ScreenShots.assertShows(compose, name, "before work")
            ScreenShots.assertShows(compose, name, "Do · 7 day streak")
            ScreenShots.assertShows(compose, name, "Avoid · 7 day streak")
        } else {
            ScreenShots.assertShows(compose, name, "Add a habit to do")
            ScreenShots.assertShows(compose, name, "No habits yet")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverFull() = shoot("cover-dark-full", true)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverFullLight() = shoot("cover-light-full", true, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", false)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerFull() = shoot("inner-dark-full", true)

    private fun awaitCore(what: String, done: () -> Boolean) {
        repeat(100) {
            if (done()) return
            Thread.sleep(20)
            settle()
        }
        fail("never saw: $what")
    }

    private fun markOf(core: MekaCore, name: String, back: Int = 0): HabitMark =
        core.goalsView.value.habits.first { it.title == name }.recent[6 - back]

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theCircleCyclesAndADayOnTheStripMarksThatDay() {
        val core = prepared(true, ThemeChoice.DARK)
        show(core)
        val walk = ids.getValue("Walk 20 minutes")
        compose.onNodeWithTag("habits-mark-$walk").performClick(); settle()
        awaitCore("walk done") { markOf(core, "Walk 20 minutes") == HabitMark.DONE }
        ScreenShots.assertShows(compose, "cycle", "4 of 8 done today")
        compose.onNodeWithTag("habits-mark-$walk").performClick(); settle()
        awaitCore("walk missed") { markOf(core, "Walk 20 minutes") == HabitMark.MISSED }
        compose.onNodeWithTag("habits-mark-$walk").performClick(); settle()
        awaitCore("walk clear") { markOf(core, "Walk 20 minutes") == HabitMark.CLEAR }
        // Tuesday on the strip: the circle marks Tuesday, today stays as it was.
        compose.onNodeWithTag("habits-day-4").performClick(); settle()
        ScreenShots.assertShows(compose, "cycle", "done · Tue 10")
        compose.onNodeWithTag("habits-mark-$walk").performClick(); settle()
        awaitCore("walk done on Tuesday") { markOf(core, "Walk 20 minutes", back = 2) == HabitMark.DONE }
        assertEquals(HabitMark.CLEAR, markOf(core, "Walk 20 minutes"))
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun addPutsAnAvoidHabitIntoItsList() {
        val core = prepared(true, ThemeChoice.DARK)
        show(core)
        compose.onNodeWithTag("habits-add").performClick(); settle()
        compose.onNode(hasContentDescription("Habit name")).performTextInput("No phone in bed")
        // Each choice settles before the next tap: Add habit's tap reads the choices from the last composition, so a
        // save before Avoid had recomposed added it as a habit to build (ci run 43460366350).
        compose.onNodeWithTag("habits-add-avoid").performClick(); settle()
        compose.onNodeWithTag("habits-add-list-Night time").performClick(); settle()
        compose.onNodeWithTag("habits-add-save").performClick(); settle()
        awaitCore("the habit added") { core.goalsView.value.habits.any { it.title == "No phone in bed" } }
        val h = core.goalsView.value.habits.first { it.title == "No phone in bed" }
        assertEquals(true, h.avoid)
        assertEquals("Night time", os.meka.core.domain.HabitBoardRules.listName(h))
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aHabitsTapShowsItsFourWeeksAndTheReminderTurnsOnAndOff() {
        // Slice 3: the unfolded row shows the history and the reminder; Every day sets the list's time, Off clears it.
        val core = prepared(true, ThemeChoice.DARK)
        show(core)
        val teeth = ids.getValue("Brush teeth")
        compose.onNodeWithTag("habits-row-$teeth").performClick(); settle()
        compose.onNodeWithTag("habits-row-history-$teeth").assertExists()
        ScreenShots.assertShows(compose, "history", "Done 7 of 7 days since you added it")
        ScreenShots.assertShows(compose, "history", "Best run 7 days")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("habits", "cover-dark-open"))
        ScreenShots.assertNoBrokenWords(compose, "history")
        compose.onNodeWithTag("habits-row-remind-on-$teeth").performClick(); settle()
        awaitCore("the reminder on") { core.goalsView.value.habits.first { it.id == teeth }.remindAt == 7 * 60 + 30 }
        ScreenShots.assertShows(compose, "history", "07:30")
        compose.onNodeWithTag("habits-row-remind-off-$teeth").performClick(); settle()
        awaitCore("the reminder off") { core.goalsView.value.habits.first { it.id == teeth }.remindAt == null }
    }
}
