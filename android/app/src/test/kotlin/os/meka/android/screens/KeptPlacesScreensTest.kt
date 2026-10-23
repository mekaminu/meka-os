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
import os.meka.android.ask.DateNightPane
import os.meka.android.ask.EmergencyPane
import os.meka.android.ask.FactsPane
import os.meka.android.ask.LeavePane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.DateNightRules
import os.meka.core.domain.EmergencyRules
import os.meka.core.domain.EmergencySection
import os.meka.core.domain.FactRules
import os.meka.core.domain.LeaveRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 23 (build plan: "the rest of Ask's places"): Date night, Annual leave, Emergency card and
 * What Navilife knows, each on the Fold's cover and inner screens with nothing in, one thing and a full set, Dark (and
 * the full set in Light). Saved under `date-night/`, `leave/`, `emergency/` and `facts/` (the `fold-screens` artifact,
 * then the `screens` branch's docs/screens/fold/…) and read for nonsense, cut lines, words broken in two, dates and
 * times split across lines, and each add field's hint on one line; each pane's summary must be the one the core says.
 * Invented data only (rule 13): the numbers are Ofcom's drama range.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class KeptPlacesScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String) {
        DATE_NIGHT("date-night", "Date night"), LEAVE("leave", "Annual leave"),
        EMERGENCY("emergency", "Emergency card"), FACTS("facts", "What Navilife knows"),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.DATE_NIGHT -> {
                val friday = 5
                check(core.setDateNight(friday, 19 * 60, DateNightRules.firstNight(friday, core.todayEpochDay())) != null)
                if (fill == Fill.MANY) {
                    val second = core.dateNightView.value.nights.getOrNull(1) ?: fail("no second night")
                    check(core.skipDateNight(second.day, true) != null)
                }
            }
            Place.LEAVE -> {
                check(core.addLeave("27–30 Oct")?.refused == false)
                if (fill == Fill.MANY) {
                    check(core.addLeave("Spain 18 Dec – 4 Jan")?.refused == false)
                    check(core.addLeave("Fri 13 Nov")?.refused == false)
                    check(core.addLeave("Long weekend at the cottage in the Lake District 5–8 Feb")?.refused == false)
                    check(core.addLeave("2–3 Jul")?.refused == false)
                    check(core.addSchool("Half term 26–30 Oct") != null)
                    check(core.addSchool("Christmas holidays 21 Dec – 1 Jan") != null)
                }
            }
            Place.EMERGENCY -> {
                check(!core.addEmergency(EmergencySection.CALL.key, "GP: Dr Patel, 01632 960123").refused)
                if (fill == Fill.MANY) {
                    check(!core.addEmergency(EmergencySection.CALL.key, "Alex's school office: 01632 960456").refused)
                    check(!core.addEmergency(EmergencySection.CALL.key, "Neighbour with the spare key: Sam two doors down, 07700 900123").refused)
                    check(!core.addEmergency(EmergencySection.DOCUMENTS.key, "Passports: study desk, top drawer").refused)
                    check(!core.addEmergency(EmergencySection.DOCUMENTS.key, "Wills and the deeds to the house: the grey box file in the loft above the landing").refused)
                    check(!core.addEmergency(EmergencySection.ACCOUNTS.key, "Home insurance: Oakfield Mutual").refused)
                    check(!core.addEmergency(EmergencySection.ACCOUNTS.key, "Energy: Oakfield Energy").refused)
                }
            }
            Place.FACTS -> {
                check(!core.addFact("Car reg: AB12 CDE").refused)
                if (fill == Fill.MANY) {
                    check(!core.addFact("GP: Oakfield Surgery, 01632 960789").refused)
                    check(!core.addFact("Alex's shoe size: 12").refused)
                    check(!core.addFact("Energy supplier: Oakfield Energy").refused)
                    check(!core.addFact("Boiler: Oakfield Combi 30, serviced every autumn by the plumber on the high street").refused)
                    check(!core.addFact("Wi-Fi name: Oakfield-Home-5G").refused)
                    val kept = core.factsView.value.groups.flatMap { it.rows }.first { it.label.startsWith("Wi-Fi") }
                    check(core.setFactAi(kept.id, use = false))
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
                        Place.DATE_NIGHT -> DateNightPane(core, onClose = {})
                        Place.LEAVE -> LeavePane(core, onClose = {})
                        Place.EMERGENCY -> EmergencyPane(core, onClose = {})
                        Place.FACTS -> FactsPane(core, onClose = {})
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
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, place.title)
        // Each add field's hint is one example on one line (the quality gate's rule for hints, slice 20), and each
        // row's buttons keep their words whole.
        ScreenShots.assertEachOneLine(compose, name, listOf(LeaveRules.ADD_HINT, FactRules.HINT, FactRules.COPY_BUTTON,
            FactRules.KEEP_BUTTON, FactRules.USE_BUTTON, DateNightRules.SKIP_LABEL, DateNightRules.KEEP_LABEL, "Remove") +
            EmergencySection.entries.map { it.hint })
        when (place) {
            Place.DATE_NIGHT -> {
                val v = core.dateNightView.value
                ScreenShots.assertShows(compose, name, v.summaryShown)
                ScreenShots.assertReadable(compose, name, v.nights.flatMap { listOf(it.label, it.line) }, mustShow = false)
                if (fill == Fill.EMPTY && v.on) fail("$name: date night on with nothing chosen")
                if (fill != Fill.EMPTY && v.nights.isEmpty()) fail("$name: no nights coming up once it's on")
                if (fill == Fill.MANY && v.nights.none { it.skipped }) fail("$name: the skipped night isn't skipped")
            }
            Place.LEAVE -> {
                val v = core.leaveView.value
                ScreenShots.assertShows(compose, name, v.summary)
                ScreenShots.assertReadable(compose, name, (v.booked + v.taken).flatMap { listOf(it.title, it.line) } +
                    v.holidays.flatMap { listOf(it.title, it.line) }, mustShow = false)
                if (fill == Fill.EMPTY && v.summary != LeaveRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nothing booked")
                if (fill == Fill.MANY && v.booked.size + v.taken.size != 5) fail("$name: ${v.booked.size + v.taken.size} rows for five stretches")
                if (fill == Fill.MANY && v.holidays.isEmpty()) fail("$name: no school holidays after typing two")
            }
            Place.EMERGENCY -> {
                val v = core.emergencyView.value
                ScreenShots.assertShows(compose, name, v.summary)
                ScreenShots.assertReadable(compose, name, v.groups.flatMap { g -> g.rows.flatMap { listOfNotNull(it.title, it.detail) } }, mustShow = false)
                if (fill == Fill.EMPTY && v.summary != EmergencyRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing on the card")
                if (fill == Fill.MANY && v.count != 7) fail("$name: ${v.count} lines for seven")
            }
            Place.FACTS -> {
                val v = core.factsView.value
                ScreenShots.assertShows(compose, name, v.summary)
                ScreenShots.assertReadable(compose, name, v.groups.flatMap { g -> g.rows.flatMap { listOf(it.label, it.value) } }, mustShow = false)
                if (fill == Fill.EMPTY && v.summary != FactRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && v.count != 6) fail("$name: ${v.count} facts for six")
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverEmpty() = shoot(Place.DATE_NIGHT, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverOne() = shoot(Place.DATE_NIGHT, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverMany() = shoot(Place.DATE_NIGHT, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverManyLight() = shoot(Place.DATE_NIGHT, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun dateNightInnerEmpty() = shoot(Place.DATE_NIGHT, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun dateNightInnerMany() = shoot(Place.DATE_NIGHT, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverEmpty() = shoot(Place.LEAVE, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverOne() = shoot(Place.LEAVE, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverMany() = shoot(Place.LEAVE, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverManyLight() = shoot(Place.LEAVE, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun leaveInnerEmpty() = shoot(Place.LEAVE, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun leaveInnerMany() = shoot(Place.LEAVE, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverEmpty() = shoot(Place.EMERGENCY, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverOne() = shoot(Place.EMERGENCY, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverMany() = shoot(Place.EMERGENCY, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverManyLight() = shoot(Place.EMERGENCY, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun emergencyInnerEmpty() = shoot(Place.EMERGENCY, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun emergencyInnerMany() = shoot(Place.EMERGENCY, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverEmpty() = shoot(Place.FACTS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverOne() = shoot(Place.FACTS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverMany() = shoot(Place.FACTS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverManyLight() = shoot(Place.FACTS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun factsInnerEmpty() = shoot(Place.FACTS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun factsInnerMany() = shoot(Place.FACTS, "inner-dark-many", Fill.MANY)
}
