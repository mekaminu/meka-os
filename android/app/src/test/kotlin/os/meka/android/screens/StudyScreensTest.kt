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
import androidx.compose.ui.test.hasTestTag
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
        compose.onNodeWithTag("study-lesson-delete").performClick()
        compose.waitForIdle()
        assertEquals(8, core.studyView.value.course(id)!!.total)
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
}
