package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.ERRANDS_LIST_TAG
import os.meka.android.ask.ErrandsPane
import os.meka.android.ask.MEALS_LIST_TAG
import os.meka.android.ask.MealsPane
import os.meka.android.ask.PEOPLE_LIST_TAG
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
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(29), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String, val listTag: String) {
        PEOPLE("people", "People", PEOPLE_LIST_TAG), ERRANDS("errands", "Errands", ERRANDS_LIST_TAG),
        DINNERS("dinners", "Dinners", MEALS_LIST_TAG),
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

    private fun shoot(place: Place, name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
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
        return core
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /**
     * Scrolls the pane's own list (bounded: at most 20 steps of 60 % down, then 40 up, never performScrollToNode with
     * the clock paused) until a node matching [m] is composed, then returns the matches, failing with [what] if none.
     */
    private fun reach(place: Place, name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        fun found() = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
        if (!found()) {
            val list = compose.onNodeWithTag(place.listTag)
            val step = list.fetchSemanticsNode().size.height * 0.6f
            for (dy in List(20) { step } + List(40) { -step }) {
                if (found()) break
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                compose.mainClock.advanceTimeBy(1_000)
            }
            turns(500)
        }
        val n = compose.onAllNodes(m)
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no $what")
        return n
    }

    private fun tap(place: Place, name: String, what: String, matcher: SemanticsMatcher) {
        reach(place, name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Types [text] into the field TalkBack reads as [hint] and presses Done on the keyboard. */
    private fun type(place: Place, name: String, hint: String, text: String) {
        val field = hasContentDescription(hint) and hasSetTextAction()
        reach(place, name, field, "\"$hint\" field")[0].performTextInput(text)
        turns(500)
        compose.onAllNodes(field)[0].performImeAction()
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /** Every button drawn answers a 48 dp touch (Errands' Done and Remove, Dinners' Remove and near-me's pill were ~30). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /**
     * Works by hand, People: a tap on a person opens what's owed either way; each Kept is a 48 dp touch and takes its
     * promise off; a second tap folds the person again.
     */
    private fun peopleByHand(core: MekaCore) {
        val name = "people-by-hand"
        val p = Place.PEOPLE
        assertTouchTargets(name)
        val alex = core.peopleView.value.rows.firstOrNull { it.name == "Alex" } ?: fail("$name: Alex isn't on People")
        val promise = alex.promised.firstOrNull() ?: fail("$name: nothing promised to Alex")
        val kept = hasContentDescription("Kept: ${promise.title}")
        tap(p, name, "Alex", hasContentDescription(alex.spoken))
        until { shows(kept) }
        if (!shows(kept)) fail("$name: tapping Alex didn't open what's owed")
        ScreenShots.assertShows(compose, name, "WAITING FOR")
        assertTouchTargets(name)
        tap(p, name, "Kept", kept)
        until { core.peopleView.value.rows.first { it.key == alex.key }.promised.none { it.id == promise.id } }
        if (core.peopleView.value.rows.first { it.key == alex.key }.promised.any { it.id == promise.id }) {
            fail("$name: Kept left \"${promise.title}\" on Alex")
        }
        if (core.listsView.value.promised.any { it.id == promise.id }) fail("$name: Kept on People didn't keep it on Lists")
        val now = core.peopleView.value.rows.first { it.key == alex.key }
        val other = now.promised.firstOrNull() ?: fail("$name: Alex's other promise went too")
        tap(p, name, "Alex", hasContentDescription(now.spoken))
        until { !shows(hasContentDescription("Kept: ${other.title}")) }
        if (shows(hasContentDescription("Kept: ${other.title}"))) fail("$name: a second tap on Alex didn't fold him")
    }

    /**
     * Works by hand, Errands: every button a 48 dp touch; Done moves an errand to Done lately and Back returns it;
     * Remove takes one off; the field adds a typed line at its place.
     */
    private fun errandsByHand(core: MekaCore) {
        val name = "errands-by-hand"
        val p = Place.ERRANDS
        assertTouchTargets(name)
        val first = core.errandsView.value.places.first().rows.first()
        tap(p, name, "Done", hasContentDescription("Done: ${first.shown}"))
        until { core.errandsView.value.done.any { it.id == first.id } }
        if (core.errandsView.value.done.none { it.id == first.id }) fail("$name: Done didn't tick \"${first.shown}\"")
        reach(p, name, hasText("Done lately", ignoreCase = true), "Done lately label")
        assertTouchTargets(name)
        tap(p, name, "Back", hasContentDescription("Back: ${first.shown}"))
        until { core.errandsView.value.done.none { it.id == first.id } }
        if (core.errandsView.value.done.any { it.id == first.id }) fail("$name: Back left \"${first.shown}\" in Done lately")
        val pharmacy = core.errandsView.value.places.flatMap { it.rows }.first { it.title.contains("collect the order") }
        tap(p, name, "Remove", hasContentDescription("Remove ${pharmacy.shown}"))
        until { core.errandsView.value.places.flatMap { it.rows }.none { it.id == pharmacy.id } }
        if (core.errandsView.value.places.flatMap { it.rows }.any { it.id == pharmacy.id }) fail("$name: Remove left \"${pharmacy.shown}\"")
        type(p, name, ErrandRules.HINT, "Butcher: sausages for Saturday")
        until { core.errandsView.value.places.any { g -> g.rows.any { it.title.contains("sausages") } } }
        if (core.errandsView.value.places.none { g -> g.rows.any { it.title.contains("sausages") } }) fail("$name: Done on the field added nothing")
        reach(p, name, hasText("sausages", substring = true, ignoreCase = true), "new errand on screen")
    }

    /**
     * Works by hand, Dinners: a tap on an unplanned day opens its favourites (48 dp chips) and one plans it; Add to
     * shopping puts the week's ingredients on the list and Undo takes them back; Remove takes a favourite off; the field
     * adds a favourite.
     */
    private fun dinnersByHand(core: MekaCore) {
        val name = "dinners-by-hand"
        val p = Place.DINNERS
        assertTouchTargets(name)
        val day = core.mealsView.value.week.first { it.mealId == null }
        val fav = core.mealsView.value.favourites.last()
        tap(p, name, day.label, hasContentDescription(day.spoken))
        val chip = hasText(fav.title) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        until { shows(chip) }
        if (!shows(chip)) fail("$name: tapping ${day.label} didn't open the favourites")
        assertTouchTargets(name)
        compose.onAllNodes(chip)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.mealsView.value.week.first { it.day == day.day }.mealId == fav.id }
        if (core.mealsView.value.week.first { it.day == day.day }.mealId != fav.id) fail("$name: the chip didn't plan ${fav.title}")
        val label = core.mealsView.value.shoppingLabel ?: fail("$name: no Add to shopping with four dinners planned")
        val before = core.listsView.value.shopping.toBuy.size
        tap(p, name, label, hasText(label))
        until { core.listsView.value.shopping.toBuy.size > before }
        if (core.listsView.value.shopping.toBuy.size <= before) fail("$name: \"$label\" added nothing to shopping")
        tap(p, name, "Undo", hasText("Undo"))
        until { core.listsView.value.shopping.toBuy.size == before }
        if (core.listsView.value.shopping.toBuy.size != before) fail("$name: Undo left ${core.listsView.value.shopping.toBuy.size - before} on shopping")
        val gone = core.mealsView.value.favourites.first { it.title.startsWith("Pasta") }
        tap(p, name, "Remove", hasContentDescription("Remove ${gone.title}"))
        until { core.mealsView.value.favourites.none { it.id == gone.id } }
        if (core.mealsView.value.favourites.any { it.id == gone.id }) fail("$name: Remove left ${gone.title}")
        type(p, name, MealRules.ADD_HINT, "Leek soup: leeks, potatoes, stock")
        until { core.mealsView.value.favourites.any { it.title.startsWith("Leek soup") } }
        if (core.mealsView.value.favourites.none { it.title.startsWith("Leek soup") }) fail("$name: Done on the field added no favourite")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverEmpty() { shoot(Place.PEOPLE, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverOne() { shoot(Place.PEOPLE, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverMany() { shoot(Place.PEOPLE, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun peopleCoverManyLight() { shoot(Place.PEOPLE, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun peopleInnerEmpty() { shoot(Place.PEOPLE, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun peopleInnerMany() { shoot(Place.PEOPLE, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverEmpty() { shoot(Place.ERRANDS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverOne() { shoot(Place.ERRANDS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverMany() { shoot(Place.ERRANDS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsCoverManyLight() { shoot(Place.ERRANDS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun errandsInnerEmpty() { shoot(Place.ERRANDS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun errandsInnerMany() { shoot(Place.ERRANDS, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun peopleByHand() { peopleByHand(shoot(Place.PEOPLE, "cover-dark-people-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun errandsByHand() { errandsByHand(shoot(Place.ERRANDS, "cover-dark-errands-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun dinnersByHand() { dinnersByHand(shoot(Place.DINNERS, "cover-dark-dinners-by-hand", Fill.MANY)) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverEmpty() { shoot(Place.DINNERS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverOne() { shoot(Place.DINNERS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverMany() { shoot(Place.DINNERS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun dinnersCoverManyLight() { shoot(Place.DINNERS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun dinnersInnerEmpty() { shoot(Place.DINNERS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun dinnersInnerMany() { shoot(Place.DINNERS, "inner-dark-many", Fill.MANY) }
}
