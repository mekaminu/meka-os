package os.meka.android.screens

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.robolectric.Shadows
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import os.meka.core.domain.HabitBoardRules
import os.meka.core.domain.HabitMark
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    private val now = 1_792_573_200_000L // Wed 21 Oct 2026, 10:00 in London, the day every other shot stands on (A13)
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
                // The same eight habits Goals shows (ShotLife, the walk-through 2026-11-15 A13).
                ids.putAll(ShotLife.addHabits(core))
                clock = now
                ShotLife.markHabits(core, ids, now / dayMs) // 10:00 in London is 09:00 UTC on 21 Oct
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
        if (full) {
            ScreenShots.assertShows(compose, name, "Wed")
            ScreenShots.assertShows(compose, name, "3 of 8 done today")
            ScreenShots.assertShows(compose, name, "best streak 7 days")
            ScreenShots.assertShows(compose, name, "Morning")
            ScreenShots.assertShows(compose, name, "before work")
            ScreenShots.assertShows(compose, name, "Do · 7 day streak")
            ScreenShots.assertShows(compose, name, "Avoid · 7 day streak")
            // A11 (walk-through 2026-11-15): the fifth chip was cut at the cover's edge; the lists wrap whole now.
            val chips = listOf("All", "Morning", "All day", "Night time", "+ New list")
            chips.forEach { ScreenShots.assertShows(compose, name, it) }
            ScreenShots.assertNotSliced(compose, name, chips)
            // A19: with habits counted, the summary and today's day carry their bars.
            compose.onNodeWithTag("habits-summary-bar", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("habits-day-bar-6-true", useUnmergedTree = true).assertExists()
            // A19 (walk-through 2026-11-15): today's ring sat over today's dot, so a 7 day streak showed 6 green dots and
            // Drink water's one kept day showed none. Today's dot is as green at its centre as yesterday's.
            val teeth = ids.getValue("Brush teeth")
            assertEquals(dotCentre(teeth, 5), dotCentre(teeth, 6), "$name: today's dot is the same green as yesterday's")
            assertEquals(dotCentre(teeth, 6), dotCentre(ids.getValue("Drink water"), 6), "$name: Drink water's kept day is green")
        } else {
            // Walk-through 2026-11-22 A15 (g4b 18): "No habits yet" then "Add a habit to do…" under seven day tiles and
            // the list chips with nothing to mark or filter. Now the one line, once, in the empty card with its button.
            ScreenShots.assertShows(compose, name, "Add a habit")
            assertEquals(1, compose.onAllNodes(hasText("No habits yet", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size,
                "$name: the blank board says \"No habits yet\" more than once")
            compose.onNodeWithTag(os.meka.android.ask.LIFE_EMPTY_TAG, useUnmergedTree = true).assertExists()
            listOf("Morning", "All day", "Night time", "+ New list").forEach {
                assertTrue(compose.onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                    "$name: \"$it\" shown on a blank board with nothing to filter")
            }
            compose.onNodeWithTag("habits-summary", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("habits-day-0", useUnmergedTree = true).assertDoesNotExist()
            // Its button opens the new habit's sheet, as + Add does.
            compose.onNodeWithText(HabitBoardRules.ADD_FIRST, useUnmergedTree = true).performClick()
            settle()
            compose.onNodeWithTag("habits-add-sheet", useUnmergedTree = true).assertExists()
        }
    }

    /** The colour at the centre of a habit's dot [i] (0–6, 6 = today), as ARGB. */
    private fun dotCentre(id: String, i: Int): Int {
        val px = compose.onNodeWithTag("habits-dot-$id-$i", useUnmergedTree = true).captureToImage().toPixelMap()
        return px[px.width / 2, px.height / 2].toArgb()
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
        // Monday on the strip: the circle marks Monday, today stays as it was.
        compose.onNodeWithTag("habits-day-4").performClick(); settle()
        // CI run 43733890002: the picked day's line can land a frame after the settle; wait for it before checking.
        awaitCore("Monday picked") { runCatching { ScreenShots.assertShows(compose, "cycle", "done · Mon 19") }.isSuccess }
        ScreenShots.assertShows(compose, "cycle", "done · Mon 19")
        compose.onNodeWithTag("habits-mark-$walk").performClick(); settle()
        awaitCore("walk done on Monday") { markOf(core, "Walk 20 minutes", back = 2) == HabitMark.DONE }
        assertEquals(HabitMark.CLEAR, markOf(core, "Walk 20 minutes"))
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun addPutsAnAvoidHabitIntoItsList() {
        val core = prepared(true, ThemeChoice.DARK)
        show(core)
        compose.onNodeWithTag("habits-add").performClick(); settle()
        compose.onNode(hasContentDescription("Habit name")).performTextInput("No phone in bed")
        // Each choice settles before the next tap (ci run 43460366350). The habit is made in one commit with its Avoid
        // and list (Goals.addHabitToList), so the view this test reads never holds it half made (ci run 43523059596).
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
        ScreenShots.assertOneLine(compose, "history", "Done 7 of 7 days since you added it")
        // Walk-through week 5, A17: Reminder, Do or avoid and List are card rows opening sheets; no chips on the page.
        listOf("remind", "kind", "list").forEach { compose.onNodeWithTag("habits-row-$it-$teeth").assertExists() }
        listOf("Every day", "Avoid").forEach {
            if (compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty()) fail("\"$it\" is still an open chip on the page")
        }
        ScreenShots.assertShows(compose, "history", HabitBoardRules.KIND_HEAD)
        // By hand: Reminder → Every day sets the list's time (07:30) and the stepper shows under the row; Off clears it.
        openSheet("habits-row-remind-$teeth")
        if (!inSheet("07:30")) fail("the reminder's sheet doesn't say when Every day nudges")
        chooseInSheet("Every day")
        awaitCore("the reminder on") { core.goalsView.value.habits.first { it.id == teeth }.remindAt == 7 * 60 + 30 }
        // The core holds the time before the row has recomposed with it (ci run 43521258380): settle first.
        settle()
        ScreenShots.assertShows(compose, "history", "07:30")
        compose.onNodeWithTag("habits-row-remind-time-$teeth").assertExists()
        openSheet("habits-row-remind-$teeth")
        chooseInSheet("Off")
        awaitCore("the reminder off") { core.goalsView.value.habits.first { it.id == teeth }.remindAt == null }
        // Do or avoid → Avoid, then List → Night time, each through its sheet.
        openSheet("habits-row-kind-$teeth")
        chooseInSheet("Avoid")
        awaitCore("teeth is an avoid") { core.goalsView.value.habits.first { it.id == teeth }.avoid }
        settle()
        openSheet("habits-row-list-$teeth")
        if (!inSheet("Before bed")) fail("the list's sheet doesn't say when Night time is for")
        chooseInSheet("Night time")
        awaitCore("teeth moved to Night time") {
            HabitBoardRules.listName(core.goalsView.value.habits.first { it.id == teeth }) == HabitBoardRules.NIGHT
        }
    }

    /** A setting's sheet is its own window (a second root): open it and wait for it through the main looper. */
    private fun openSheet(rowTag: String) {
        compose.onNodeWithTag(rowTag).performSemanticsAction(SemanticsActions.OnClick)
        until { sheetOpen() }
        if (!sheetOpen()) fail("$rowTag didn't open its sheet")
    }

    private fun chooseInSheet(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { !sheetOpen() }
        if (sheetOpen()) fail("choosing $label didn't close the sheet")
    }

    private fun inSheet(text: String): Boolean {
        val shows = SemanticsMatcher("shows \"$text\"") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
        }
        return compose.onAllNodes(shows and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun sheetOpen() = compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
    }
}
