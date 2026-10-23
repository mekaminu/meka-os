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
import os.meka.android.ask.CarPane
import os.meka.android.ask.ParkingStore
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.CarRules
import os.meka.core.domain.ParkedSpot
import os.meka.core.domain.ParkingRules
import os.meka.core.domain.ParkingTimerRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 21 (build plan: "then Car's shots"): Ask → Life → Car on the Fold's cover and inner screens
 * with no car set up, one car (named, registered, parked with a bay, a timer running, its MOT due) and two cars (both
 * parked, the second's bay a long one), Dark (and two cars in Light). Saved under `car/` (the `fold-screens` artifact,
 * then the `screens` branch's docs/screens/fold/car/) and read for nonsense, cut lines, words broken in two and dates
 * split across lines; the summary is the core's, each car has its DATES row and each parked car its card.
 * The parked spots go straight into the phone's own store (no location in a test); fuel and Bluetooth show their
 * "allow" lines (no permission here). Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class CarScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(41), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        val first = CarRules.ENTITY_ID
        check(core.setCarName(first, "the Golf"))
        check(core.setCarRegistration(first, "AB12 CDE"))
        val mot = core.addCarDate(first, "mot") ?: fail("no MOT date")
        core.setRenewalDue(mot, now / 86_400_000L + 22)
        check(core.setParkingTimer(first, "2 h") != null)
        // The pane reads the parked time against the phone's own clock.
        val at = System.currentTimeMillis() - 95 * 60_000L
        val spots = mutableMapOf(first to ParkedSpot(first, 51.5007, -0.1246, 6.0, at, label = "Level 3 · Row F"))
        if (fill == Fill.MANY) {
            val second = core.addCar("the van") ?: fail("no second car")
            check(core.setCarRegistration(second, "XY34 ZZZ"))
            spots[second] = ParkedSpot(second, 51.5033, -0.1196, 12.0, at - 3 * 3_600_000L,
                label = "Oakfield Retail Park, by the garden centre entrance, bay 214")
        }
        ParkingStore.store(context, spots)
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    CarPane(core, onClose = {}, onDates = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("car", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertShows(compose, name, ParkingRules.TITLE)
        val cars = core.carsView.value
        ScreenShots.assertReadable(compose, name, cars.cars.flatMap { listOf(it.title, it.summary) }.filter { it.isNotBlank() }, mustShow = false)
        when (fill) {
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, ParkingRules.NOT_PARKED)
            else -> {
                val spots = ParkingStore.spots(context)
                if (spots.size != (if (fill == Fill.MANY) 2 else 1)) fail("$name: ${spots.size} parked spots kept")
                if (fill == Fill.MANY && cars.cars.size != 2) fail("$name: ${cars.cars.size} cars for two")
                ScreenShots.assertShows(compose, name, "Level 3")
                if (core.parkingTimersView.value.rows.isEmpty()) fail("$name: no parking timer after setting one")
            }
        }
        // The timer's add field: one example on one line (the quality gate's rule for hints); slice 22: each action on
        // one line ("Drove off" broke across two on the cover screen).
        ScreenShots.assertEachOneLine(compose, name, listOf(ParkingTimerRules.HINT, ParkingRules.FIND, ParkingRules.DROVE_OFF,
            ParkingRules.PHOTO, ParkingTimerRules.STOP, ParkingTimerRules.CLEAR))
        // Slice 22: a car's name starts its line capitalised ("The Golf", never "the Golf" heading a card or chip), and
        // a running timer's button says what it does (no "Ended" beside "2 h left").
        val lowerStart = ScreenShots.texts(compose).filter { t -> cars.cars.any { c -> c.title != ParkingRules.heading(c.title) && t.startsWith(c.title) } }
        if (lowerStart.isNotEmpty()) fail("$name starts a line with a lower-case car name: $lowerStart")
        if (fill != Fill.EMPTY) {
            ScreenShots.assertShows(compose, name, ParkingTimerRules.STOP)
            if (ScreenShots.texts(compose).any { it == "Ended" }) fail("$name: an \"Ended\" button beside a running timer")
            ScreenShots.assertShows(compose, name, "The Golf")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun carCoverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun carCoverOne() = shoot("cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun carCoverMany() = shoot("cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun carCoverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun carInnerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun carInnerMany() = shoot("inner-dark-many", Fill.MANY)
}
