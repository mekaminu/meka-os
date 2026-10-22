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
import os.meka.android.ask.BinsPane
import os.meka.android.ask.GiftsPane
import os.meka.android.ask.ReadingPane
import os.meka.android.ask.TripsPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.BinRules
import os.meka.core.domain.GiftRules
import os.meka.core.domain.ReadingRules
import os.meka.core.domain.TripRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 18 (build plan: "the rest of Ask's places"): Trips, Gifts, Bins and Reading, each on the
 * Fold's cover and inner screens with nothing in, one thing and a full set, Dark (and the full set in Light). Saved
 * under `trips/`, `gifts/`, `bins/` and `reading/` (the `fold-screens` artifact, then the `screens` branch's
 * docs/screens/fold/…) and read for nonsense, cut lines, words broken in two and dates split across lines; each
 * pane's summary line must be the one the core says. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class PlansPlacesScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(37), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String) {
        TRIPS("trips", "Trips"), GIFTS("gifts", "Gifts"), BINS("bins", "Bins"), READING("reading", ReadingRules.TITLE),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.TRIPS -> {
                check(!core.addTrip("Barcelona 14–18 Nov").refused)
                if (fill == Fill.MANY) {
                    check(!core.addTrip("Flight BA478 Sat 14 Nov 07:40 ref ABC123").refused)
                    check(!core.addTrip("Hotel Arts on the seafront with breakfast 14–18 Nov ref HX7781").refused)
                    check(!core.addTrip("Dinner at the tapas bar by the harbour Sun 15 Nov 20:30").refused)
                    check(!core.addTrip("Flight BA479 Wed 18 Nov 18:20 ref ABC123").refused)
                    check(!core.addTrip("Lisbon 4–7 Dec").refused)
                }
            }
            Place.GIFTS -> {
                check(!core.addOccasion("Alex's birthday 31 Oct 2019").refused)
                if (fill == Fill.MANY) {
                    check(!core.addOccasion("Wedding anniversary 20 Aug 2015").refused)
                    check(!core.addOccasion("Sam's birthday 12 Dec 2016").refused)
                    check(!core.addOccasion("Grandma Rose's eightieth birthday party at the community hall 6 Nov 1946").refused)
                    val first = core.giftsView.value.rows.first()
                    check(core.addGiftIdea(first.id, "Football boots, size 13"))
                    check(core.addGiftIdea(first.id, "A space Lego set with the rocket and launch pad"))
                }
            }
            Place.BINS -> {
                check(!core.addBin("Black bin Tue fortnightly from 13 Oct").refused)
                if (fill == Fill.MANY) {
                    check(!core.addBin("Food caddy every Tuesday").refused)
                    check(!core.addBin("Recycling Tue fortnightly from 20 Oct").refused)
                    check(!core.addBin("Garden waste every other Friday from 23 Oct").refused)
                }
            }
            Place.READING -> {
                check(!core.addReading("reading Atomic Habits by James Clear, 320 pages").refused)
                if (fill == Fill.MANY) {
                    val id = core.readingView.value.onTheGo.single().id
                    check(!core.readingProgress(id, "120").refused)
                    check(!core.addReading("Dune by Frank Herbert, 400 pages").refused)
                    check(!core.addReading("podcast The Rest Is History").refused)
                    check(!core.addReading("The Very Long Book About Everything That Ever Happened in the North by Alexandra Longname-Smith, 900 pages").refused)
                    check(!core.setReadingGoal("12 books").refused)
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
                        Place.TRIPS -> TripsPane(core, onClose = {})
                        Place.GIFTS -> GiftsPane(core, onClose = {})
                        Place.BINS -> BinsPane(core, onClose = {})
                        Place.READING -> ReadingPane(core, onClose = {})
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
        ScreenShots.assertShows(compose, name, place.title)
        // Slice 20: each add field's hint is one example on one line, and a trip's time difference stays whole.
        ScreenShots.assertEachOneLine(compose, name, listOf(TripRules.ADD_HINT, TripRules.BOOKING_HINT, GiftRules.ADD_HINT, BinRules.ADD_HINT))
        ScreenShots.assertClocksWhole(compose, name)
        when (place) {
            Place.TRIPS -> {
                val v = core.tripsView.value
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.name, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != TripRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with no trips")
                if (fill == Fill.MANY && v.rows.size != 2) fail("$name: ${v.rows.size} rows for two trips")
                ScreenShots.assertShows(compose, name, TripRules.addHint(hasTrips = !v.isEmpty))
            }
            Place.GIFTS -> {
                val v = core.giftsView.value
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.title, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != GiftRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nothing entered")
                if (fill == Fill.MANY && v.rows.size != 4) fail("$name: ${v.rows.size} rows for four occasions")
                // Slice 19: who it is comes before "birthday", never the party's words jumbled into a name.
                if (fill == Fill.MANY && v.rows.none { it.title == "Grandma Rose's birthday" }) fail("$name: Grandma Rose's row reads ${v.rows.map { it.title }}")
            }
            Place.BINS -> {
                val v = core.binsView.value
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.name, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != BinRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with no bins")
                if (fill == Fill.MANY && v.rows.size != 4) fail("$name: ${v.rows.size} rows for four bins")
                // Slice 19: a start date just gone is this year's, so a Tuesday bin stays a Tuesday bin.
                v.rows.filter { it.name == "Black bin" || it.name == "Recycling" }.forEach { r ->
                    if ("Tuesday" !in r.line) fail("$name: ${r.name} reads \"${r.line}\", typed as a Tuesday")
                }
            }
            Place.READING -> {
                val v = core.readingView.value
                val rows = v.onTheGo + v.upNext
                ScreenShots.assertReadable(compose, name, rows.flatMap { listOf(it.title, it.meta) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != ReadingRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && rows.size != 4) fail("$name: ${rows.size} rows for four things")
                // Slice 19: nothing put down reads as "moved on", and next up is the first added.
                if (fill == Fill.MANY) {
                    val month = v.month ?: fail("$name: no month line after reading this month")
                    if ("moved on" in month || "next up: Dune" !in month) fail("$name: the month line reads \"$month\"")
                    if (v.upNext.firstOrNull()?.title != "Dune") fail("$name: up next starts ${v.upNext.map { it.title }}")
                }
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverEmpty() = shoot(Place.TRIPS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverOne() = shoot(Place.TRIPS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverMany() = shoot(Place.TRIPS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverManyLight() = shoot(Place.TRIPS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun tripsInnerEmpty() = shoot(Place.TRIPS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun tripsInnerMany() = shoot(Place.TRIPS, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverEmpty() = shoot(Place.GIFTS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverOne() = shoot(Place.GIFTS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverMany() = shoot(Place.GIFTS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverManyLight() = shoot(Place.GIFTS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun giftsInnerEmpty() = shoot(Place.GIFTS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun giftsInnerMany() = shoot(Place.GIFTS, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverEmpty() = shoot(Place.BINS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverOne() = shoot(Place.BINS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverMany() = shoot(Place.BINS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverManyLight() = shoot(Place.BINS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun binsInnerEmpty() = shoot(Place.BINS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun binsInnerMany() = shoot(Place.BINS, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverEmpty() = shoot(Place.READING, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverOne() = shoot(Place.READING, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverMany() = shoot(Place.READING, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverManyLight() = shoot(Place.READING, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun readingInnerEmpty() = shoot(Place.READING, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun readingInnerMany() = shoot(Place.READING, "inner-dark-many", Fill.MANY)
}
