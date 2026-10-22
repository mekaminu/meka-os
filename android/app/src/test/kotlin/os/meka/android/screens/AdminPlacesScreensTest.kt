package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import os.meka.android.ask.AppointmentsPane
import os.meka.android.ask.KidsPane
import os.meka.android.ask.SchoolPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AppointmentRules
import os.meka.core.domain.KidsRules
import os.meka.core.domain.SchoolRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 16 (build plan: "the rest of Ask's places"): the family-admin places, Appointments, Kids
 * and School, each on the Fold's cover and inner screens with nothing in, one thing and a full set, Dark (and the
 * full set in Light). Saved under `appointments/`, `kids/` and `school/` (the `fold-screens` artifact, then the
 * `screens` branch's docs/screens/fold/…) and read for nonsense, cut lines and words broken in two; each pane's
 * summary line must be the one the core says. Appointments' rows must read whole (their first finding: on the cover
 * screen "Every 6 months · last went Sat 12 Apr" was cut to one line). Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class AdminPlacesScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(31), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String) {
        APPOINTMENTS("appointments", "Appointments"), KIDS("kids", KidsRules.TITLE), SCHOOL("school", "School"),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun children(core: MekaCore, fill: Fill) {
        core.addAboutYouChild("Alex, U7, Oakfield School")
        if (fill == Fill.MANY) core.addAboutYouChild("Sam, U10, Oakfield School")
    }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.APPOINTMENTS -> {
                check(!core.typeAppointment("Dentist every 6 months, last 12 Apr").refused)
                if (fill == Fill.MANY) {
                    check(!core.typeAppointment("Haircut every 6 weeks, last 26 Sep").refused)
                    check(!core.typeAppointment("Optician every 2 years, last March 2025").refused)
                    check(!core.typeAppointment("Hygienist every 6 months, booked Tue 3 Nov 9:30").refused)
                    check(!core.typeAppointment("Orthodontist check for the brace and retainer every 6 weeks, last 30 Sep").refused)
                }
            }
            Place.KIDS -> {
                children(core, fill)
                check(!core.setKidSize("Alex", "shoes", "13").refused)
                if (fill == Fill.MANY) {
                    check(!core.setKidSize("Alex", "tops", "6-7").refused)
                    check(!core.setKidSize("Sam", "shoes", "2").refused)
                    check(!core.addKidFee("Alex", "Football £35 monthly from 1 Nov").refused)
                    check(!core.addKidFee("Sam", "Swimming £60 on the 14th every month").refused)
                    check(!core.setKidPocketMoney("Sam", "£5").refused)
                    check(!core.addKidChore("Sam", "Tidy room £1").refused)
                }
            }
            Place.SCHOOL -> {
                children(core, fill)
                check(core.addSchool("INSET 27 Oct") != null)
                if (fill == Fill.MANY) {
                    check(core.addSchool("Half term 26–30 Oct") != null)
                    check(core.addSchool("Alex PE Tue") != null)
                    check(core.addSchool("Sam's trip to the science museum with a packed lunch 20 Nov") != null)
                    check(core.addSchool("Trip payment by 14 Nov") != null)
                }
            }
        }
    }

    private fun shoot(place: Place, name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, place, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    when (place) {
                        Place.APPOINTMENTS -> AppointmentsPane(core, onClose = {})
                        Place.KIDS -> KidsPane(core, onClose = {}, onAboutYou = {})
                        Place.SCHOOL -> SchoolPane(core, onClose = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(place.area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, place.title)
        when (place) {
            Place.APPOINTMENTS -> {
                val v = core.appointmentsView.value
                // Each row's name and how often / last went read whole (on two lines if they must), never "Every 6 mo…".
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.title, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != AppointmentRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && v.rows.size != 5) fail("$name: ${v.rows.size} rows for five")
                v.rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.title) }
            }
            Place.KIDS -> {
                val v = core.kidsView.value
                ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY) {
                    if (v.summary != KidsRules.NO_CHILDREN) fail("$name: \"${v.summary}\" with no children")
                    ScreenShots.assertShows(compose, name, "About you")
                }
                if (fill == Fill.MANY && v.kids.size != 2) fail("$name: ${v.kids.size} cards for two children")
            }
            Place.SCHOOL -> {
                val v = core.schoolView.value
                ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != SchoolRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nothing entered")
                if (fill != Fill.EMPTY && v.isEmpty) fail("$name: nothing kept from what was typed")
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverEmpty() = shoot(Place.APPOINTMENTS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverOne() = shoot(Place.APPOINTMENTS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverMany() = shoot(Place.APPOINTMENTS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverManyLight() = shoot(Place.APPOINTMENTS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun appointmentsInnerEmpty() = shoot(Place.APPOINTMENTS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun appointmentsInnerMany() = shoot(Place.APPOINTMENTS, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverEmpty() = shoot(Place.KIDS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverOne() = shoot(Place.KIDS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverMany() = shoot(Place.KIDS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverManyLight() = shoot(Place.KIDS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun kidsInnerEmpty() = shoot(Place.KIDS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun kidsInnerMany() = shoot(Place.KIDS, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverEmpty() = shoot(Place.SCHOOL, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverOne() = shoot(Place.SCHOOL, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverMany() = shoot(Place.SCHOOL, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverManyLight() = shoot(Place.SCHOOL, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun schoolInnerEmpty() = shoot(Place.SCHOOL, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun schoolInnerMany() = shoot(Place.SCHOOL, "inner-dark-many", Fill.MANY)
}
