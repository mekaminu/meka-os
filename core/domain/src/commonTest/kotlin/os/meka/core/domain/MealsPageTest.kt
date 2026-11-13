package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 8: Ask → Dinners as the page shape (both apps). */
class MealsPageTest {
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private val chilli = Meal("m1", "Chilli", listOf("mince", "kidney beans", "rice"), ShoppingRules.OWNER, 1L)
    private val roast = Meal("m2", "Roast chicken", listOf("chicken", "potatoes", "rice"), ShoppingRules.OWNER, 2L)
    private fun view(meals: List<Meal> = listOf(chilli, roast), plan: Map<Long, String> = mapOf(sat to "m1", sat + 2 to "m2"), toBuy: Set<String> = emptySet()) =
        MealRules.view(meals, plan, sat, toBuy)

    @Test
    fun theThreeNumbersAreThePlannedDinnersWhatToBuyAndTheFavourites() {
        val p = MealsPageRules.page(view(toBuy = setOf(ShoppingRules.key("rice"))))
        assertEquals("Dinners", p.title)
        assertEquals(listOf("2 of 7", "4", "2"), p.tiles.map { it.number })
        assertEquals(listOf("dinners planned", "to add to shopping", "favourites"), p.tiles.map { it.label })
        assertEquals(listOf(false, true, false), p.tiles.map { it.lit })
        assertEquals(listOf(MealsPageRules.WEEK, MealsPageRules.SHOPPING, MealsPageRules.FAVOURITES), p.cards.map { it.key })
        assertEquals(listOf("A favourite dinner"), p.addChoices.map { it.label })
        assertEquals(MealRules.ADD_HINT, MealsPageRules.hint(MealsPageRules.ADD_FAVOURITE))
    }

    @Test
    fun theWeekIsADayARowWithTonightInBrassAndSeeAllForTheLastTwoDays() {
        val v = view()
        val week = MealsPageRules.page(v).cards.first()
        assertEquals(7, week.count)
        assertEquals(LifePageRules.MAX_ROWS, week.rows.size)
        assertEquals(LifePageRules.SEE_ALL, week.more)
        val r = week.rows
        assertEquals(listOf("Chilli", MealRules.NOT_PLANNED, "Roast chicken"), r.take(3).map { it.name })
        assertEquals(listOf("3 ingredients", MealsPageRules.PICK, "3 ingredients"), r.take(3).map { it.line })
        assertEquals(listOf("Tonight", "Tomorrow", "Mon 12 Oct"), r.take(3).map { it.whenText })
        assertEquals(listOf(true, false, false), r.take(3).map { it.lit })
        assertEquals(7, MealsPageRules.allRows(v, MealsPageRules.WEEK).size)
        // Tonight's dinner is said once in its row; the summary under the header leaves it out.
        assertEquals("2 of 7 dinners planned", MealsPageRules.summary(v))
        // Nothing planned tonight: no brass.
        assertFalse(MealsPageRules.page(view(plan = mapOf(sat + 1 to "m1"))).cards.first().rows.any { it.lit })
    }

    @Test
    fun shoppingIsOneRowWhileADinnerIsPlanned() {
        val shop = MealsPageRules.page(view()).cards.first { it.key == MealsPageRules.SHOPPING }.rows.single()
        assertEquals(MealsPageRules.KEY_SHOP, shop.key)
        assertEquals("Add 5 ingredients to shopping", shop.name)
        assertTrue(shop.lit)
        val all = MealsPageRules.page(view(toBuy = listOf("mince", "kidney beans", "rice", "chicken", "potatoes").map(ShoppingRules::key).toSet()))
        val done = all.cards.first { it.key == MealsPageRules.SHOPPING }.rows.single()
        assertEquals(MealsPageRules.NOTHING_TO_ADD, done.name)
        assertEquals("All 5 ingredients are on the shopping list", done.line)
        assertFalse(done.lit)
        assertEquals("None", all.tiles[1].number)
        // Nothing planned: no Shopping card at all.
        assertTrue(MealsPageRules.page(view(plan = emptyMap())).cards.none { it.key == MealsPageRules.SHOPPING })
    }

    @Test
    fun anEmptyPageSaysHowToStartInWordsNeverADash() {
        val v = view(meals = emptyList(), plan = emptyMap())
        val p = MealsPageRules.page(v)
        assertEquals(listOf("None", "None", "None"), p.tiles.map { it.number })
        assertEquals(MealsPageRules.EMPTY_SUMMARY, MealsPageRules.summary(v))
        assertEquals(listOf(MealsPageRules.WEEK_EMPTY, MealsPageRules.FAVOURITES_EMPTY), p.cards.map { it.empty })
        assertTrue(p.cards.all { it.rows.isEmpty() })
        assertEquals(listOf("dinner planned", "favourite"), MealsPageRules.page(view(meals = listOf(chilli), plan = mapOf(sat to "m1"))).tiles.let { listOf(it[0].label, it[2].label) })
    }

    @Test
    fun aRowFindsItsDayOrFavouriteAsTheyAreNow() {
        val v = view()
        assertEquals("Roast chicken", MealsPageRules.day(v, MealsPageRules.dayKey(sat + 2))?.title)
        assertNull(MealsPageRules.day(v, MealsPageRules.dayKey(sat + 9)))
        assertEquals("Chilli", MealsPageRules.meal(v, MealsPageRules.mealKey("m1"))?.title)
        assertNull(MealsPageRules.meal(view(meals = listOf(roast)), MealsPageRules.mealKey("m1")))
        assertNull(MealsPageRules.day(v, MealsPageRules.mealKey("m1")))
        assertEquals(setOf(sat), MealsPageRules.plannedDays(v, "m1"))
        assertTrue(MealsPageRules.isPlanned(v, "m2", sat + 2))
        assertFalse(MealsPageRules.isPlanned(v, "m2", sat))
        val favs = MealsPageRules.page(v).cards.last()
        assertEquals(listOf(MealsPageRules.mealKey("m1"), MealsPageRules.mealKey("m2")), favs.rows.map { it.key })
        assertEquals("mince, kidney beans, rice", favs.rows.first().line)
    }

    @Test
    fun sixFavouritesShowFiveAndSeeAll() {
        val six = (1..6).map { Meal("x$it", "Dish $it", listOf("thing"), ShoppingRules.OWNER, it.toLong()) }
        val favs = MealsPageRules.page(view(meals = six, plan = emptyMap())).cards.last()
        assertEquals(6, favs.count)
        assertEquals(5, favs.rows.size)
        assertEquals(LifePageRules.SEE_ALL, favs.more)
    }
}
