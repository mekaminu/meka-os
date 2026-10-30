package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
import os.meka.android.goals.StudyPane
import os.meka.android.calendar.EventUndo
import os.meka.android.calendar.STUDY_NOTE_TAG
import os.meka.android.calendar.WeekBoardView
import os.meka.android.shell.ShellDestination
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Study plan, slice 1: a course's page on the Fold's cover and inner screens (Dark, and Light on the cover), read for
 * nonsense and words broken in two, saved under `study/`; and a lesson's tick shows in the next frame. Slice 2a: the
 * plan card with a date and slots picked (cover Dark and Light), and a chip answering in the next frame. Study fixes: a
 * long press opens the lesson's sheet and never deletes; Delete asks first; Recently deleted restores. Invented course
 * (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class StudyScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val outline = """
        Module 1: Foundations
        Welcome and setup 30 min
        Spreadsheets refresher 45 min
        Cleaning data 45 min
        Module 2: Core skills
        Pivot tables 1 h
        Charts that say something 40 min
        Lookups 50 min
        Module 3: Putting it together
        A small project 1 h
        Presenting findings 45 min
    """.trimIndent()

    private fun filled(theme: ThemeChoice): Pair<MekaCore, String> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        val id = runBlocking {
            core.tick()
            val id = checkNotNull(core.addStudyCourse("Data analytics certificate").courseId)
            check(!core.addStudyLessons(id, outline).refused)
            core.studyView.value.course(id)!!.modules.first().lessons.forEach { core.tickStudyLesson(it.id, true) }
            core.tick()
            id
        }
        return core to id
    }

    private fun show(core: MekaCore, id: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    StudyPane(core, id, onClose = {}, onRemoved = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
    }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        val (core, id) = filled(theme)
        show(core, id)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Data analytics certificate")
        ScreenShots.assertShows(compose, name, "3 of 8 lessons · about 4½ h left")
        // Slice 2a: YOUR PLAN sits between the header and the lessons, so the modules are further down.
        scrollTo(hasTestTag("study-module-${core.studyView.value.course(id)!!.modules[1].key}"))
        ScreenShots.assertShows(compose, name, "2 · Core skills")
    }

    /** Scrolls the course to [m] with the clock running, then pauses it again for the next-frame checks. */
    private fun scrollTo(m: androidx.compose.ui.test.SemanticsMatcher) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("study-pane").performScrollToNode(m)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun courseCoverDark() = shoot("cover-dark-course")
    @Test @Config(qualifiers = ScreenShots.COVER) fun courseCoverLight() = shoot("cover-light-course", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun courseInnerDark() = shoot("inner-dark-course")

    private val fourWeeks = 20_752L + 28 // Mon 23 Nov 2026

    private fun shootPlan(name: String, theme: ThemeChoice) {
        val (core, id) = filled(theme)
        runBlocking {
            core.setStudyFinish(id, fourWeeks)
            core.setStudySlot(id, "train", true)
            core.setStudySlot(id, "sat", true)
            core.tick()
        }
        show(core, id)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "On track for Mon 23 Nov")
        ScreenShots.assertShows(compose, name, "2 sessions a week")
        ScreenShots.assertShows(compose, name, "Fits: you picked 4 slots a week.")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun planCoverDark() = shootPlan("cover-dark-plan", ThemeChoice.DARK)
    @Test @Config(qualifiers = ScreenShots.COVER) fun planCoverLight() = shootPlan("cover-light-plan", ThemeChoice.LIGHT)

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun besideItsMockupTheDatesSitTwoToARowAndTheFieldWaitsBehindTheDashedRow() {
        // Study beside its mockup (2026-10-30): on the closed Fold the finish dates drop their weekday so two share a row,
        // and a course with lessons shows "+ Add lessons" at the foot instead of an open field; a tap opens it there.
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        val ends = core.studyView.value.course(id)!!.plan.ends
        compose.onNodeWithText("23 Nov · 4 wks").assertExists()
        compose.onAllNodesWithText("Mon 23 Nov · 4 wks").assertCountEquals(0)
        val first = compose.onNodeWithTag("study-end-${ends[0].key}").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("study-end-${ends[1].key}").getUnclippedBoundsInRoot()
        assertEquals(first.top, second.top, "the first two finish dates share a row")
        compose.onAllNodesWithTag("study-outline").assertCountEquals(0)
        scrollTo(hasTestTag("study-add-lessons"))
        compose.onNodeWithText(os.meka.core.domain.StudyBesideRules.ADD_ROW).assertExists()
        compose.onNodeWithTag("study-add-lessons").performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onNodeWithTag("study-outline").assertExists()
        compose.onNodeWithTag("study-from-screenshot").assertExists()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-add-lessons"))
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun aPlanChipAnswersInTheNextFrame() {
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        scrollTo(hasTestTag("study-end-$fourWeeks"))
        compose.onNodeWithTag("study-end-$fourWeeks").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithTag("study-end-$fourWeeks").assertIsSelected()
        compose.onNodeWithText("2 sessions a week").assertExists()
        scrollTo(hasTestTag("study-slot-sat"))
        compose.onNodeWithTag("study-slot-sat").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithText("Doesn’t fit", substring = true).assertExists()
        // The core's writes land after: the view then says the same.
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.plan.obi != null }
        assertEquals("Move the date to Mon 7 Dec and it fits the slots you picked.", core.studyView.value.course(id)!!.plan.obi)
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun aLessonTickShowsInTheNextFrame() {
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        // Module 2 is the first not yet done, so it is open.
        val pivot = core.studyView.value.course(id)!!.modules[1].lessons.first()
        scrollTo(hasTestTag("study-lesson-${pivot.id}"))
        compose.onNodeWithTag("study-lesson-${pivot.id}").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithTag("study-lesson-${pivot.id}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Done"))
        // The core's write lands after: the view then says the same.
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.modules[1].lessons.first().done }
        assertEquals(4, core.studyView.value.course(id)!!.done)
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun aLongPressOpensTheSheetAndNeverDeletes() {
        // Study fixes (Meka 2026-10-26 23:21: "when I hold an item it deletes automatically, I just lost an item").
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        val pivot = core.studyView.value.course(id)!!.modules[1].lessons.first()
        compose.mainClock.autoAdvance = true
        scrollTo(hasTestTag("study-lesson-${pivot.id}"))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("study-lesson-${pivot.id}").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("study-lesson-sheet").assertExists()
        assertEquals(8, core.studyView.value.course(id)!!.total)
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-sheet"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-lesson-sheet"))
        ScreenShots.assertSensible(compose, "cover-dark-lesson-sheet")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-lesson-sheet")
        // Delete asks first; only "Delete" in the confirm deletes, and Recently deleted brings it back.
        // The sheet is taller with PLAN FOR on top: bring Delete, then its confirm, into view on the cover screen.
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-delete"))
        compose.onNodeWithTag("study-lesson-delete").performClick()
        compose.waitForIdle()
        assertEquals(8, core.studyView.value.course(id)!!.total)
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-confirm-yes"))
        compose.onNodeWithTag("study-lesson-confirm-yes").performClick()
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.total == 7 }
        val deleted = core.studyView.value.course(id)!!.deleted.single()
        assertEquals("Pivot tables", deleted.title)
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-deleted-head"))
        compose.onNodeWithTag("study-deleted-head").performClick()
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-restore-${deleted.id}"))
        compose.onNodeWithTag("study-restore-${deleted.id}").performClick()
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.total == 8 }
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun planningALessonForTomorrowShowsItsChipInTheNextFrame() {
        // Meka 2026-10-26 23:21: "how do you plan an item for today, tomorrow…"
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        val pivot = core.studyView.value.course(id)!!.modules[1].lessons.first()
        scrollTo(hasTestTag("study-lesson-${pivot.id}"))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("study-lesson-${pivot.id}").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-plan-Tomorrow"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("study-lesson-plan-Tomorrow").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("study-lesson-plan-Tomorrow").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.modules[1].lessons.first().plannedDay == 20_753L }
        assertEquals("Tomorrow", core.studyView.value.course(id)!!.modules[1].lessons.first().planned)
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-${pivot.id}"))
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-lesson-planned"))
        ScreenShots.assertSensible(compose, "cover-dark-lesson-planned")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-lesson-planned")
        ScreenShots.assertShows(compose, "cover-dark-lesson-planned", "Tomorrow")
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun severalLessonsSelectedArePlannedForADayTogether() {
        // Study fixes (3) slice 3: "Select several" in a lesson's sheet, pick another, then Plan for… → Tomorrow.
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        val (pivot, charts) = core.studyView.value.course(id)!!.modules[1].lessons
        scrollTo(hasTestTag("study-lesson-${pivot.id}"))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("study-lesson-${pivot.id}").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-select"))
        compose.onNodeWithTag("study-lesson-select").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("study-select-bar").assertExists()
        compose.onNodeWithTag("study-selected").assertTextEquals("1 lesson selected")
        // While selecting, a tap picks a lesson and never ticks it.
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-${charts.id}"))
        compose.onNodeWithTag("study-lesson-${charts.id}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("study-selected").assertTextEquals("2 lessons selected")
        assertEquals(false, core.studyView.value.course(id)!!.modules[1].lessons[1].done)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-lessons-selected"))
        ScreenShots.assertSensible(compose, "cover-dark-lessons-selected")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-lessons-selected")
        compose.onNodeWithTag("study-select-plan-Tomorrow").performClick()
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.modules[1].lessons.take(2).all { it.plannedDay == 20_753L } }
        compose.waitForIdle()
        compose.onNodeWithTag("study-select-bar").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun aLessonDraggedByItsGripMovesAndAModuleMovesUp() {
        // Study fixes (2): "drag to reorder lessons and modules". Lookups dragged two rows up by its ≡ lands first.
        val (core, id) = filled(ThemeChoice.DARK)
        show(core, id)
        val modules = core.studyView.value.course(id)!!.modules
        val lookups = modules[1].lessons[2]
        scrollTo(hasTestTag("study-lesson-grip-${lookups.id}"))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("study-lesson-grip-${lookups.id}").assertExists()
        val rowH = compose.onNodeWithTag("study-lesson-${lookups.id}").fetchSemanticsNode().size.height.toFloat()
        compose.onNodeWithTag("study-lesson-grip-${lookups.id}").performTouchInput {
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -rowH * 2.2f / 12)) }
            up()
        }
        compose.waitUntil(5_000L) {
            core.studyView.value.course(id)!!.modules[1].lessons.map { it.title } == listOf("Lookups", "Pivot tables", "Charts that say something")
        }
        assertEquals(8, core.studyView.value.course(id)!!.total)
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-lesson-${lookups.id}"))
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-lesson-dragged"))
        ScreenShots.assertSensible(compose, "cover-dark-lesson-dragged")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-lesson-dragged")
        // A module's grip offers Move up to a screen reader: Putting it together goes before Core skills.
        val last = modules[2]
        compose.onNodeWithTag("study-pane").performScrollToNode(hasTestTag("study-module-grip-${last.key}"))
        val actions = compose.onNodeWithTag("study-module-grip-${last.key}").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "Move up" }.action() }
        compose.waitUntil(5_000L) {
            core.studyView.value.course(id)!!.modules.map { it.name } == listOf("1 · Foundations", "3 · Putting it together", "2 · Core skills")
        }
    }

    @Test
    @Config(qualifiers = ScreenShots.INNER)
    fun plannedLessonsShowUnderTheirDayOnTheWeekBoardAndOpenTheCourse() {
        // Meka 2026-10-26 23:21: planned lessons show "on the Week board under that day".
        val (core, id) = filled(ThemeChoice.DARK)
        val lessons = core.studyView.value.course(id)!!.modules[1].lessons
        runBlocking {
            core.planStudyLesson(lessons[0].id, 20_752L) // Pivot tables, today (Mon 26 Oct)
            core.planStudyLesson(lessons[1].id, 20_753L) // Charts, tomorrow
            core.tick()
        }
        assertEquals("Study · 1 lesson · about 1 h", core.weekBoard.value.day(20_752L)!!.study!!.text)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WeekBoardView(core, EventUndo(), onEvent = {}, Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "inner-dark-board-study"))
        ScreenShots.assertSensible(compose, "inner-dark-board-study")
        ScreenShots.assertNoBrokenWords(compose, "inner-dark-board-study")
        ScreenShots.assertShows(compose, "inner-dark-board-study", os.meka.core.domain.LoneWordGlue.kept("Study · 1 lesson · about 1 h"))
        ScreenShots.assertShows(compose, "inner-dark-board-study", os.meka.core.domain.LoneWordGlue.kept("Study · 1 lesson · about 40 min"))
        // A tap opens the course on Goals.
        val app = ApplicationProvider.getApplicationContext<ShotApplication>()
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithTag(STUDY_NOTE_TAG, useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        assertEquals(id, app.openStudy.value)
        assertEquals(ShellDestination.GOALS, app.openDestination.value)
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun thisWeeksSessionStartsAndIsDoneInTheNextFrame() {
        // Study plan, slice 2b part 2: THIS WEEK with Start and Done (mockup).
        // A course with nothing ticked yet (lessons ticked today already count as today's session).
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        val id = runBlocking {
            core.tick()
            val id = checkNotNull(core.addStudyCourse("Data analytics certificate").courseId)
            check(!core.addStudyLessons(id, outline).refused)
            core.setStudySession(id, 60)
            core.setStudySlot(id, "eve", true) // Mon and Wed evenings
            core.tick()
            id
        }
        val today = core.todayEpochDay()
        show(core, id)
        scrollTo(hasTestTag("study-week-button-$today"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-this-week"))
        ScreenShots.assertSensible(compose, "cover-dark-this-week")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-this-week")
        ScreenShots.assertShows(compose, "cover-dark-this-week", "THIS WEEK")
        ScreenShots.assertShows(compose, "cover-dark-this-week", "Welcome and setup · 30 min")
        compose.onNodeWithTag("study-week-button-$today").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNode(hasContentDescription("Done, tick 1 lessons"), useUnmergedTree = true).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.week.first().note != null }
        scrollTo(hasTestTag("study-week-button-$today"))
        compose.onNodeWithTag("study-week-button-$today").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNode(hasContentDescription("Session done"), useUnmergedTree = true).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)!!.done == 1 }
        compose.onNodeWithText("Ticked \u201CWelcome and setup\u201D").assertExists()
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun theNudgeToggleUnderThisWeekAnswersInTheNextFrame() {
        // Study plan, slice 2b rest: "Nudge me 10 minutes before · A card from Obi, not out loud" (mockup's toggle).
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        val id = runBlocking {
            core.tick()
            val id = checkNotNull(core.addStudyCourse("Data analytics certificate").courseId)
            check(!core.addStudyLessons(id, outline).refused)
            core.setStudySession(id, 60)
            core.setStudySlot(id, "eve", true)
            core.tick()
            id
        }
        show(core, id)
        scrollTo(hasTestTag("study-nudge"))
        ScreenShots.assertShows(compose, "cover-dark-nudge", "Nudge me 10 minutes before")
        compose.onNodeWithTag("study-nudge").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithText("Off", useUnmergedTree = true).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)?.nudge == false }
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun theSundayCheckInToggleUnderThisWeekAnswersInTheNextFrame() {
        // Study plan, slice 2b rest: "Sunday check-in · How the week went, and next week's sessions" (mockup's toggle).
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        val id = runBlocking {
            core.tick()
            val id = checkNotNull(core.addStudyCourse("Data analytics certificate").courseId)
            check(!core.addStudyLessons(id, outline).refused)
            core.setStudySession(id, 60)
            core.setStudySlot(id, "eve", true)
            core.tick()
            id
        }
        show(core, id)
        scrollTo(hasTestTag("study-checkin"))
        ScreenShots.assertShows(compose, "cover-dark-checkin", "Sunday check-in")
        compose.onNodeWithTag("study-checkin").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithText("Off", useUnmergedTree = true).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.studyView.value.course(id)?.checkin == false }
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun fromAScreenshotFillsTheFieldToCheckThenAddLessonsAddsThem() {
        // Study plan, slice 3 part 1: a contents page's words (invented) land in the field as outline lines; nothing is
        // added until Add lessons.
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        val id = runBlocking {
            core.tick()
            val id = checkNotNull(core.addStudyCourse("Data analytics certificate").courseId)
            core.tick()
            id
        }
        val page = "Course content\nSection 1: Getting started 0 / 2 | 14min\n1. Welcome\nPlay\n2min\n2. Installing the tools\n12min\n25% complete"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    StudyPane(core, id, onClose = {}, onRemoved = {}, readScreenshot = { done -> done(os.meka.android.capture.PictureRead.Words(page)) })
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
        scrollTo(hasTestTag("study-from-screenshot"))
        compose.onNodeWithTag("study-from-screenshot").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithTag("study-outline").assertTextEquals("Section 1: Getting started\n1. Welcome 2 min\n2. Installing the tools 12 min")
        compose.mainClock.advanceTimeBy(1_000L)
        ScreenShots.assertShows(compose, "cover-dark-from-screenshot", "Read 2 lessons in 1 module, all with a time · check them, then Add lessons · the picture isn't kept")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", "cover-dark-from-screenshot"))
        assertEquals(0, core.studyView.value.course(id)!!.total)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Add lessons", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000L) { core.studyView.value.course(id)?.total == 2 }
        assertEquals("1 · Getting started", core.studyView.value.course(id)!!.modules.single().name)
    }
}
