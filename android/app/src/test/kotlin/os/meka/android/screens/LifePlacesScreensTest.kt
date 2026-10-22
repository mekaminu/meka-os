package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
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
import os.meka.android.ask.ErrandsPane
import os.meka.android.ask.MealsPane
import os.meka.android.ask.PeoplePane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.ErrandRules
import os.meka.core.domain.MealRules
import os.meka.core.domain.PeopleRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 14 (build plan: "the rest of Ask's places' shots"): three of Ask → Life's everyday places,
 * People, Errands and Dinners, each on the Fold's cover and inner screens with nothing in, one thing and a full set,
 * Dark (and the full set in Light). Saved under `people/`, `errands/` and `dinners/` (the `fold-screens` artifact,
 * then the `screens` branch's docs/screens/fold/…) and read for nonsense, cut lines and words broken in two; each
 * pane's summary line must be the one the core says. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class LifePlacesScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(29), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String) {
        PEOPLE("people", "People"), ERRANDS("errands", "Errands"), DINNERS("dinners", "Dinners"),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.PEOPLE -> {
                core.addPromise("Send the photos from the party", "Alex", 2)
                if (fill == Fill.MANY) {
                    core.addPromise("Return the drill", "Alex", null)
                    core.addWaiting("The quote for the fence", "Alex", 3)
                    core.addWaiting("Their sign-off on the long weekend rota for the whole of November", "Jordan Whitfield", 0)
                    core.addOccasion("Sam's birthday 3 Nov")
                    core.addOccasion("Priya's birthday 24 Oct")
                }
            }
            Place.ERRANDS -> {
                check(!core.addErrand("Post office: return the parcel").refused)
                if (fill == Fill.MANY) {
                    check(!core.addErrand("Post office: buy first-class stamps").refused)
                    check(!core.addErrand("Hardware shop: picture hooks, sandpaper, a spare key for the shed").refused)
                    check(!core.addErrand("Pharmacy: collect the order").refused)
                }
            }
            Place.DINNERS -> {
                check(core.addMeal("Chilli: mince, kidney beans, rice, onion") != null)
                if (fill == Fill.MANY) {
                    check(core.addMeal("Fish pie: cod, prawns, potatoes, milk, leeks") != null)
                    check(core.addMeal("Chicken and vegetable stir fry with noodles: chicken, peppers, noodles, soy sauce") != null)
                    check(core.addMeal("Pasta bake: pasta, passata, cheddar") != null)
                    core.tick()
                    val meals = core.mealsView.value.favourites
                    val today = core.todayEpochDay()
                    meals.take(3).forEachIndexed { i, m -> core.planMeal(today + i, m.id) }
                    core.addShopping("Rice")
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
                        Place.PEOPLE -> PeoplePane(core, onClose = {})
                        Place.ERRANDS -> ErrandsPane(core, onClose = {})
                        Place.DINNERS -> MealsPane(core, onClose = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(place.area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, place.title)
        when (place) {
            Place.PEOPLE -> {
                val v = core.peopleView.value
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != PeopleRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nobody in")
                if (fill != Fill.EMPTY && v.rows.isEmpty()) fail("$name: no person shown with a promise in")
                v.rows.forEach { ScreenShots.assertShows(compose, name, it.name) }
            }
            Place.ERRANDS -> {
                val v = core.errandsView.value
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != ErrandRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && v.places.size != 3) fail("$name: ${v.places.size} places for three")
                // Slice 15: every row reads as a sentence ("Buy first-class stamps", not lower case).
                // (The inner screen is short: rows below it aren't composed, so the first is checked on screen and
                // nothing on screen may read as a typed lower-case title.)
                val rows = v.places.flatMap { it.rows }
                rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.shown) }
                rows.forEach { r -> if (r.shown.first().isLowerCase()) fail("$name: \"${r.shown}\" starts lower case") }
                val raw = rows.map { it.title }.filter { it.first().isLowerCase() }.toSet()
                ScreenShots.texts(compose).firstOrNull { it in raw }?.let { fail("$name: \"$it\" reads lower case") }
            }
            Place.DINNERS -> {
                val v = core.mealsView.value
                ScreenShots.assertShows(compose, name, v.paneSummary)
                if (fill == Fill.EMPTY) ScreenShots.assertShows(compose, name, MealRules.NO_FAVOURITES)
                // Slice 15: tonight's dinner is said once (its row), and the add field's hint sits on one line.
                v.week.firstOrNull()?.title?.let { t ->
                    // Its own row (and its favourite's) say it alone; nothing else on screen repeats it inside a line.
                    ScreenShots.texts(compose).firstOrNull { it != t && it.contains(t) }
                        ?.let { fail("$name: tonight's \"$t\" is said again in \"$it\"") }
                }
                // The hint itself (exactly; the no-favourites line quotes it too). Below the fold on the inner screen.
                val hints = compose.onAllNodes(hasText(MealRules.ADD_HINT, substring = false), useUnmergedTree = true).fetchSemanticsNodes()
                if (hints.isEmpty() && fill != Fill.MANY) fail("$name: the add field's hint isn't on screen")
                hints.forEach { n ->
                    val results = mutableListOf<TextLayoutResult>()
                    n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                    val lines = results.firstOrNull()?.lineCount ?: 1
                    if (lines != 1) fail("$name: the add field's hint takes $lines lines")
                }
                if (fill == Fill.MANY && v.week.count { it.mealId != null } != 3) fail("$name: the week doesn't hold its three dinners")
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverEmpty() = shoot(Place.PEOPLE, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverOne() = shoot(Place.PEOPLE, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverMany() = shoot(Place.PEOPLE, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverManyLight() = shoot(Place.PEOPLE, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun peopleInnerEmpty() = shoot(Place.PEOPLE, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun peopleInnerMany() = shoot(Place.PEOPLE, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverEmpty() = shoot(Place.ERRANDS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverOne() = shoot(Place.ERRANDS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverMany() = shoot(Place.ERRANDS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverManyLight() = shoot(Place.ERRANDS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun errandsInnerEmpty() = shoot(Place.ERRANDS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun errandsInnerMany() = shoot(Place.ERRANDS, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverEmpty() = shoot(Place.DINNERS, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverOne() = shoot(Place.DINNERS, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverMany() = shoot(Place.DINNERS, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverManyLight() = shoot(Place.DINNERS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun dinnersInnerEmpty() = shoot(Place.DINNERS, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun dinnersInnerMany() = shoot(Place.DINNERS, "inner-dark-many", Fill.MANY)
}
