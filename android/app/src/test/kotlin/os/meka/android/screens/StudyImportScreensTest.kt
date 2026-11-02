package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import os.meka.android.goals.GoalsRoute
import os.meka.android.goals.STUDY_IMPORT_TAG
import os.meka.core.domain.StudyImportRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One-tap course import on the Fold: a course link waiting on Goals shows "Add <course> · … lessons?" at the top
 * (cover Dark and Light, read for nonsense and broken words, saved under `study/`); nothing is written until Add, which
 * makes the course; Cancel puts it away with nothing written. Invented course (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class StudyImportScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London

    private val link = StudyImportRules.link(
        "Data analytics",
        listOf(
            "Module 1: Foundations" to listOf("Welcome 15 min", "Spreadsheets refresher 45 min"),
            "Module 2: Core skills" to listOf("Pivot tables 1 h", "Charts 40 min", "Lookups 50 min"),
        ),
    )

    private fun open(theme: ThemeChoice = ThemeChoice.DARK): Pair<ShotApplication, MekaCore> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(51), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking { core.tick() }
        context.studyImport.value = link
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { GoalsRoute(core) }
            }
        }
        ScreenShots.awaitShows(compose, "import", "Add Data analytics · 2 modules · 5 lessons?")
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        return context to core
    }

    private fun shoot(name: String, theme: ThemeChoice) {
        open(theme)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("study", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, StudyImportRules.LABEL)
        ScreenShots.assertShows(compose, name, StudyImportRules.NOTE)
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun importCoverDark() = shoot("cover-dark-import", ThemeChoice.DARK)
    @Test @Config(qualifiers = ScreenShots.COVER) fun importCoverLight() = shoot("cover-light-import", ThemeChoice.LIGHT)

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun askingWritesNothingAndAddMakesTheCourse() {
        val (app, core) = open()
        assertTrue(core.studyView.value.courses.isEmpty())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("$STUDY_IMPORT_TAG-add").performClick()
        // The view catches up after the course and each lesson are written, so wait for the whole course.
        compose.waitUntil(10_000L) { runBlocking { core.tick() }; core.studyView.value.courses.singleOrNull()?.total == 5 }
        assertNull(app.studyImport.value)
        val c = core.studyView.value.courses.single()
        assertEquals("Data analytics", c.title)
        assertEquals(5, c.total)
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun cancelPutsItAwayWithNothingWritten() {
        val (app, core) = open()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("$STUDY_IMPORT_TAG-cancel").performClick()
        compose.waitForIdle()
        assertNull(app.studyImport.value)
        assertTrue(core.studyView.value.courses.isEmpty())
    }
}
