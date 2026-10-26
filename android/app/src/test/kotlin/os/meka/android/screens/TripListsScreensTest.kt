package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import os.meka.android.ask.TripListsPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.TripList
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Trip lists, slice 1: a trip's To pack on the Fold's cover and inner screens (Dark, and Light on the cover), read for
 * nonsense and words broken in two, saved under `trip-lists/`; a tick shows in the next frame; and (slice 2b) an empty
 * trip starts from the last trip's lists. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TripListsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(41), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun filled(theme: ThemeChoice): Pair<MekaCore, String> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        val tripId = runBlocking {
            core.tick()
            check(!core.addTrip("Lisbon 28–31 Oct").refused)
            val id = core.tripsView.value.rows.single().id
            listOf(
                "passports × 4", "GHIC cards × 4", "boarding passes", "t-shirts × 5 for me", "kids outfits × 10",
                "jumpers × 4", "toothbrushes and paste", "phone chargers × 3", "kids tablets × 2", "buy sun cream",
                "buy EU plug adapters × 2", "before we leave: put the bins out",
            ).forEach { check(!core.addTripItem(id, it, TripList.PACK).refused) }
            val pass = core.tripListsView.value.single().tab(TripList.PACK).groups.first().rows.first().id
            core.tickTripItem(pass, true)
            core.tick()
            id
        }
        return core to tripId
    }

    private fun show(core: MekaCore, tripId: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    TripListsPane(core, tripId, onBack = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.waitForIdle()
    }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        val (core, tripId) = filled(theme)
        show(core, tripId)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("trip-lists", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Lisbon trip")
        ScreenShots.assertShows(compose, name, "2 days to go")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun packCoverDark() = shoot("cover-dark-pack")
    @Test @Config(qualifiers = ScreenShots.COVER) fun packCoverLight() = shoot("cover-light-pack", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun packInnerDark() = shoot("inner-dark-pack")

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun aTickShowsInTheNextFrame() {
        val (core, tripId) = filled(ThemeChoice.DARK)
        show(core, tripId)
        val ghic = core.tripListsView.value.single().tab(TripList.PACK).groups.first().rows.first { it.text == "GHIC cards" }
        compose.onNodeWithTag("trip-item-${ghic.id}").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithTag("trip-item-${ghic.id}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ticked"))
        // The core's write lands after: the view then says the same.
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) {
            core.tripListsView.value.single().tab(TripList.PACK).groups.first().rows.first { it.id == ghic.id }.done
        }
        assertEquals(1, core.tripListsView.value.single().tab(TripList.PACK).groups.first().rows.count { !it.done })
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun anEmptyTripStartsFromTheLastTripsLists() {
        val (core, lisbonId) = filled(ThemeChoice.DARK)
        val rome = runBlocking {
            check(!core.addTrip("Rome 20–23 Dec").refused)
            core.tick()
            core.tripsView.value.rows.first { it.id != lisbonId }.id
        }
        show(core, rome)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("trip-lists", "cover-dark-start-from"))
        ScreenShots.assertSensible(compose, "cover-dark-start-from")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-start-from")
        ScreenShots.assertShows(compose, "cover-dark-start-from", "Start from Lisbon trip")
        compose.onNodeWithTag("trip-start-from").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.tripListsView.value.single { it.tripId == rome }.packed.total == 9 }
        // Copied unticked (Lisbon's passports were ticked), and the offer has gone.
        assertEquals(null, core.tripListsView.value.single { it.tripId == rome }.startFrom)
        assertEquals(0, core.tripListsView.value.single { it.tripId == rome }.packed.done)
        assertEquals("1 of 9", core.tripListsView.value.single { it.tripId == lisbonId }.packed.line)
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun anEmptyTripStartsFromASavedTemplate() {
        val (core, lisbonId) = filled(ThemeChoice.DARK)
        assertEquals("Save as a template for next time", core.tripListsView.value.single().saveTemplate)
        val rome = runBlocking {
            check(!core.saveTripTemplate(lisbonId).refused)
            check(!core.addTrip("Rome 20–23 Dec").refused)
            core.tick()
            core.tripsView.value.rows.first { it.id != lisbonId }.id
        }
        show(core, rome)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("trip-lists", "cover-dark-start-template"))
        ScreenShots.assertSensible(compose, "cover-dark-start-template")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-start-template")
        ScreenShots.assertShows(compose, "cover-dark-start-template", "Start from your Lisbon template")
        compose.onNodeWithTag("trip-start-template").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.tripListsView.value.single { it.tripId == rome }.packed.total == 9 }
        assertEquals(0, core.tripListsView.value.single { it.tripId == rome }.packed.done)
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun whosGoingAndTheLeaveTimeShowInTheHeader() {
        val (core, tripId) = filled(ThemeChoice.DARK)
        runBlocking {
            check(core.setTripGoing(tripId, "me", true))
            check(core.setTripGoing(tripId, "partner", true))
            check(!core.setTripLeave(tripId, "6.30am").refused)
            core.tick()
        }
        show(core, tripId)
        compose.onNodeWithTag("trip-head").performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val name = "cover-dark-going"
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("trip-lists", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "2 people")
        ScreenShots.assertShows(compose, name, "leave home 06:30 Wednesday")
        ScreenShots.assertShows(compose, name, "WHO'S GOING")
        // A chip lights in the next frame; the core's write lands after.
        compose.onNodeWithTag("trip-going-kids").performClick()
        compose.mainClock.advanceTimeBy(16L)
        compose.onNodeWithTag("trip-going-kids")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Going"))
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000L) { core.tripListsView.value.single().going.last().on }
    }

    @Test
    @Config(qualifiers = ScreenShots.COVER)
    fun aJobsReminderShowsOnItsRow() {
        val (core, tripId) = filled(ThemeChoice.DARK)
        runBlocking {
            check(!core.addTripItem(tripId, "before we leave: charge everything at 9pm", TripList.PACK).refused)
            core.tick()
        }
        show(core, tripId)
        compose.onNodeWithTag("trip-tab-leave").performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val name = "cover-dark-leave"
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Charge everything")
        ScreenShots.assertShows(compose, name, "Remind Tue 21:00")
    }
}
