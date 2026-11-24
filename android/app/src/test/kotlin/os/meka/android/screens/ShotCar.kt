package os.meka.android.screens

import kotlin.test.fail
import os.meka.core.domain.CarRules
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.facade.MekaCore

/**
 * One car's dates for every shot that shows them (the walk-through 2026-11-22, A13): Life admin's many shot gathered a
 * "Car insurance · Renewal · due Sat 24 Oct" that Car's many shot didn't know (its Dates held the MOT alone), and its
 * Home insurance was due Fri 30 Oct beside Mon 26 Oct on the one shot. Both now read these: the first car's MOT 22 days
 * on, and (many) its insurance three days on, which the radar files under the first car as the app does. Invented data
 * only (rule 13). Today is Wed 21 Oct 2026 on both tests' clocks.
 */
object ShotCar {
    const val NAME = "the Golf"
    const val REGISTRATION = "AB12 CDE"
    const val INSURANCE = "Car insurance"
    const val INSURANCE_DAYS = 3L
    const val MOT_DAYS = 22L
    const val HOME_INSURANCE_DAYS = 5L

    /** The first car named and registered, its MOT on the radar, and with [many] its insurance (the nearest renewal). */
    suspend fun dates(core: MekaCore, many: Boolean) {
        val first = CarRules.ENTITY_ID
        check(core.setCarName(first, NAME))
        check(core.setCarRegistration(first, REGISTRATION))
        val today = core.todayEpochDay()
        val mot = core.addCarDate(first, "mot") ?: fail("no MOT date")
        core.setRenewalDue(mot, today + MOT_DAYS)
        if (many) core.addRenewal(INSURANCE, ObligationKind.INSURANCE, today + INSURANCE_DAYS, RenewalRepeat.YEARLY, "£420", null)
    }

    /** Which of [titles] no car's Dates hold (empty when every one is there). */
    fun missingFromCar(core: MekaCore, titles: List<String>): List<String> {
        val onCars = core.carsView.value.cars.flatMap { c -> c.rows.filter { it.trackedId != null }.map { it.title } }
        return titles.filter { it !in onCars }
    }
}
