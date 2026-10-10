package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meal plan → shopping, slice 1: favourites typed once, the week's dinners, the ingredients onto the shopping list. */
class MealPlanTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private fun at(day: Long, h: Int, min: Int = 0) = cal.toEpochMs(day, h * 60 + min)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun meals(dev: Device) = MealPlan(dev.replica, { "meal${n++}" }, { world.clock.nowMs }, cal)
    private fun shopping(dev: Device) = Shopping(dev.replica, { "shop${n++}" }, { world.clock.nowMs }, cal)
    private val mFold = meals(fold)
    private val mMac = meals(mac)

    init { world.clock.nowMs = at(sat, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun keys(dev: Device) = shopping(dev).view().toBuy.map { ShoppingRules.key(it.title) }.toSet()

    @Test
    fun aFavouriteIsReadFromOneLine() {
        assertEquals(MealEntry("Chilli", listOf("mince", "kidney beans", "rice")), MealRules.read("chilli: mince, kidney beans, rice"))
        assertEquals(MealEntry("Pasta bake", listOf("pasta", "cheese", "tomatoes")), MealRules.read("Pasta bake (pasta, cheese, tomatoes)"))
        assertEquals(MealEntry("Roast chicken", listOf("chicken", "potatoes")), MealRules.read("Roast chicken - chicken; potatoes"))
        // "and" never splits, a name alone is a favourite with no ingredients yet, repeats in a line go once.
        assertEquals(MealEntry("Fish and chips", emptyList()), MealRules.read("  fish and chips. "))
        assertEquals(MealEntry("Tacos", listOf("tortillas", "mince")), MealRules.read("Tacos: tortillas, mince, Tortillas"))
        assertNull(MealRules.read("   "))
        assertNull(MealRules.read(": mince, rice"))
        assertEquals(MealRules.MAX_TITLE, MealRules.title("x".repeat(80))!!.length)
    }

    @Test
    fun theWeekIsPickedADayAtATimeOnEitherDeviceAndAFavouriteTypedAgainIsUpdated() {
        assertEquals(MealRules.NO_FAVOURITES, mFold.view(emptySet()).summary)
        assertEquals("Chilli" to false, mFold.add("Chilli: mince, kidney beans, rice")?.let { it.first.title to it.second })
        assertEquals("Added Chilli · 3 ingredients", MealRules.addedLine(MealRules.read("Chilli: mince, kidney beans, rice")!!, false))
        mFold.add("Roast chicken: chicken, potatoes, carrots")
        assertEquals(MealRules.NOTHING_PLANNED, mFold.view(emptySet()).summary)
        val chilli = mFold.meals().first { it.title == "Chilli" }
        val roast = mFold.meals().first { it.title == "Roast chicken" }
        sync()

        // The Mac plans tonight and Monday; the Fold sees them.
        assertTrue(mMac.set(sat, chilli.id))
        assertTrue(mMac.set(sat + 2, roast.id))
        assertFalse(mMac.set(sat + 2, roast.id)) // already so
        assertFalse(mMac.set(sat - 1, roast.id)) // gone
        assertFalse(mMac.set(sat + 14, roast.id)) // too far
        assertFalse(mMac.set(sat + 1, "nope"))
        sync()
        val v = mFold.view(emptySet())
        assertEquals(listOf("Tonight", "Tomorrow", "Mon 12 Oct"), v.week.take(3).map { it.label })
        assertEquals(listOf("Chilli", null, "Roast chicken"), v.week.take(3).map { it.title })
        assertEquals("3 ingredients", v.week[0].line)
        assertEquals(MealRules.NOT_PLANNED, v.week[1].line)
        assertEquals("Tomorrow: not planned", v.week[1].spoken)
        assertEquals("2 of 7 dinners planned · tonight: Chilli", v.summary)
        assertEquals(listOf("Chilli", "Roast chicken"), v.favourites.map { it.title })
        assertEquals("mince, kidney beans, rice", v.favourites[0].line)

        // Typing a favourite again replaces its ingredients on the same entity; a name alone leaves them.
        assertEquals(true, mFold.add("chilli: mince, beans, rice, peppers")?.second)
        assertEquals("Updated Chilli · 4 ingredients", mFold.add("Chilli")?.let { MealRules.addedLine(it.first, it.second) })
        assertEquals(2, mFold.meals().size)
        assertEquals(listOf("mince", "beans", "rice", "peppers"), mFold.meals().first { it.title == "Chilli" }.ingredients)

        // Clearing a day; removing a favourite leaves its days unplanned.
        assertTrue(mFold.set(sat + 2, null))
        assertEquals("Nothing planned for Mon 12 Oct", MealRules.plannedLine(sat + 2, sat, null))
        assertEquals("Chilli for tonight", MealRules.plannedLine(sat, sat, "Chilli"))
        assertTrue(mFold.remove(chilli.id))
        assertFalse(mFold.remove(chilli.id))
        assertEquals(MealRules.NOTHING_PLANNED, mFold.view(emptySet()).summary)
        sync()
        assertEquals(listOf("Roast chicken"), mMac.view(emptySet()).favourites.map { it.title })
        assertNull(mMac.view(emptySet()).week[0].title)
    }

    @Test
    fun theWeeksIngredientsGoOnTheShoppingListOnceLessWhatsAlreadyThere() {
        mFold.add("Chilli: mince, kidney beans, rice, onions")
        mFold.add("Stir fry: noodles, peppers, Onions")
        mFold.add("Curry: chicken, rice, coconut milk")
        val ids = mFold.meals().associate { it.title to it.id }
        mFold.set(sat, ids["Chilli"])
        mFold.set(sat + 3, ids["Stir fry"])
        mFold.set(sat + 8, ids["Curry"]) // next week: not this week's shopping
        shopping(fold).add("milk, onions")

        val v = mFold.view(keys(fold))
        // Onions twice in the week and already to buy: on once, and not again.
        assertEquals(listOf("mince", "kidney beans", "rice", "noodles", "peppers"), v.toAdd)
        assertEquals("Add 5 ingredients to shopping", v.shoppingLabel)
        assertEquals("1 is already on the list", v.shoppingLine)
        assertEquals("Added 5 to shopping · 1 was already on it", MealRules.shoppedLine(5, 1))

        shopping(fold).add(v.toAdd.joinToString(", "))
        val after = mFold.view(keys(fold))
        assertNull(after.shoppingLabel)
        assertEquals("All 6 ingredients are on the shopping list", after.shoppingLine)
        assertEquals("Everything is already on the shopping list", MealRules.shoppedLine(0, 6))
        assertEquals(MealRules.SHOPPING_HINT, MealRules.view(emptyList(), emptyMap(), sat, emptySet()).shoppingLine)
    }

    @Test
    fun tonightsDinnerShowsInTheEveningAndAskHearsTheWeek() {
        mFold.add("Chilli: mince, rice")
        mFold.set(sat, mFold.meals().single().id)
        assertNull(mFold.tonight())
        world.clock.nowMs = at(sat, 15)
        assertEquals(MealLine("Dinner tonight: Chilli", "Dinner tonight is Chilli."), mFold.tonight())
        world.clock.nowMs = at(sat, 20, 59)
        assertEquals("Dinner tonight: Chilli", mFold.tonight()?.text)
        world.clock.nowMs = at(sat, 21)
        assertNull(mFold.tonight())
        world.clock.nowMs = at(sat, 10)

        val line = MealRules.askLine(mFold.view(emptySet()))!!
        assertTrue(line.startsWith("Dinners · tonight: Chilli · Tomorrow: not planned · Mon 12 Oct: not planned"), line)
        assertTrue(line.endsWith("favourites: Chilli"), line)
        assertNull(MealRules.askLine(MealPlanView.EMPTY))
    }

    // ---- Slice 2: Ask and Talk hear the week, and "plan chilli for Friday" is a card ----

    private fun card(title: String?, date: String?, meals: List<Meal>) =
        AskRules.card(AskRawAction("plan_dinner", title = title, date = date), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal, meals)

    @Test
    fun askHearsTheWeekAndPlanDinnerIsACardForAFavouriteOrANewOne() {
        mFold.add("Chilli: mince, kidney beans, rice")
        mFold.add("Fajitas: chicken, peppers, wraps")
        val chilli = mFold.meals().first { it.title == "Chilli" }
        assertTrue(mFold.set(sat, chilli.id))
        val line = MealRules.askLine(mFold.view(emptySet()))!!
        assertTrue(line.startsWith("Dinners · tonight: Chilli · Tomorrow: not planned"), line)
        // The week goes with a question as one meals line; Jeanette's favourites make it someone else's words.
        val today = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(at(sat, 0), at(sat + 1, 0)))
        val ctx = AskRules.context(today, world.clock.nowMs, cal, meals = line)
        assertEquals(listOf(line.take(AskRules.MAX_LINE)), ctx.items.filter { it.kind == AskItemKind.MEALS }.map { it.line })
        assertFalse(ctx.untrusted)
        assertTrue(AskRules.context(today, world.clock.nowMs, cal, meals = line, mealsFromOthers = true).untrusted)

        val meals = mFold.meals()
        // A favourite by name, any case; Friday is "Fri 16 Oct"; no date means tonight.
        val fri = card("fajitas", "2026-10-16", meals)!!
        assertEquals(AskProposal.PlanDinner(d(10, 16), "Fajitas", meals.first { it.title == "Fajitas" }.id), fri.proposal)
        assertEquals("Dinner · Fajitas for Fri 16 Oct", fri.line)
        assertEquals("Plan", fri.button)
        assertEquals("Dinner · Chilli for tonight", card("Chilli", null, meals)!!.line)
        // A dinner that isn't a favourite yet becomes one (its name only).
        val pizza = card("pizza", "2026-10-11", meals)!!
        assertEquals(AskProposal.PlanDinner(sat + 1, "Pizza", null), pizza.proposal)
        assertEquals("Dinner · Pizza for tomorrow · new favourite", pizza.line)
        assertEquals("Planned Pizza for tomorrow", AskRules.doneLine(pizza.proposal, sat))
        // Gone, too far, no name, a bad date: no card.
        assertNull(card("Chilli", "2026-10-09", meals))
        assertNull(card("Chilli", "2026-10-25", meals))
        assertNull(card("  ", "2026-10-12", meals))
        assertNull(card(null, "2026-10-12", meals))
        assertNull(card("Chilli", "Friday", meals))
        assertTrue("plan_dinner" in AskRules.KINDS)
        // Talk says it in words.
        assertEquals("plan Fajitas for dinner on Friday 16 October", TalkRules.phrase(fri.proposal, sat, past = false))
        assertEquals("planned Pizza for dinner tomorrow", TalkRules.phrase(pizza.proposal, sat, past = true))
    }
}
