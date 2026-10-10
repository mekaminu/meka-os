package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.MealRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meal plan → shopping through the facade (slice 1): favourites, the week on two devices, the shopping list and Undo. */
class MealPlanFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theWeeksDinnersGoOnTheShoppingListFromEitherAppAndUndoTakesThemOff() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val sat = CivilDate.toEpochDay(2026, 10, 10)
        assertEquals(MealRules.NO_FAVOURITES, fold.mealsView.value.summary)
        assertEquals("Added Chilli · 3 ingredients", fold.addMeal("Chilli: mince, kidney beans, rice"))
        assertEquals("Added Stir fry · 2 ingredients", fold.addMeal("Stir fry: noodles, rice"))
        assertNull(fold.addMeal("  "))
        val chilli = fold.mealsView.value.favourites.first { it.title == "Chilli" }.id
        val stirFry = fold.mealsView.value.favourites.first { it.title == "Stir fry" }.id

        fold.syncNow(); mac.syncNow()
        assertEquals("Chilli for tonight", mac.planMeal(sat, chilli))
        assertEquals("Stir fry for Mon 12 Oct", mac.planMeal(sat + 2, stirFry))
        assertNull(mac.planMeal(sat + 2, stirFry)) // already so
        mac.addShopping("milk, rice")
        mac.syncNow(); fold.syncNow()
        val v = fold.mealsView.value
        assertEquals("2 of 7 dinners planned · tonight: Chilli", v.summary)
        assertEquals(listOf("mince", "kidney beans", "noodles"), v.toAdd)
        assertEquals("Add 3 ingredients to shopping", v.shoppingLabel)

        now += 60_000L // a minute on, so the list keeps its order (things added together are a millisecond apart)
        val done = fold.mealsToShopping()
        assertEquals("Added 3 to shopping · 1 was already on it", done.line)
        assertEquals(listOf("milk", "rice", "mince", "kidney beans", "noodles"), fold.listsView.value.shopping.toBuy.map { it.title })
        assertNull(fold.mealsView.value.shoppingLabel)
        assertEquals("Everything is already on the shopping list", fold.mealsToShopping().line)

        // Undo takes off only what it added.
        assertTrue(fold.undoMealsShopping(done))
        assertEquals(listOf("milk", "rice"), fold.listsView.value.shopping.toBuy.map { it.title })

        // Tonight's dinner in Today's header in the evening only.
        assertNull(fold.today.value.dinner)
        now += 6 * 3_600_000L // 16:00
        fold.syncNow()
        assertEquals("Dinner tonight: Chilli", fold.today.value.dinner?.text)

        assertEquals("Nothing planned for tonight", fold.planMeal(sat, null))
        assertTrue(fold.removeMeal(stirFry))
        assertNull(fold.today.value.dinner)
        assertEquals(MealRules.NOTHING_PLANNED, fold.mealsView.value.summary)
    }
}
