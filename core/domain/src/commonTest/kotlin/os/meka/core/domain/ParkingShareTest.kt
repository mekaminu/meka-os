package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParkingShareTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_740L // Wed 14 Oct 2026
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    // Invented points (rule 13).
    private val spot = ParkedSpot("car", 51.5, -0.1, 8.4, 0L, "Level 3 · Row F", photoAtMs = 5L)

    private fun cars(vararg titles: String) = CarsView(
        titles.mapIndexed { i, t -> CarView.EMPTY.copy(carId = CarRules.CAR_IDS[i], title = t) }, true,
    )

    @Test
    fun aSpotTravelsWithoutItsPhotoAndComesBackTheSame() {
        val s = spot.copy(savedAtMs = at(14, 5))
        val line = SharedSpotRules.encode(s)
        assertEquals("51.500000\t-0.100000\t8\t${at(14, 5)}\tLevel 3 · Row F", line)
        val back = SharedSpotRules.decode("car", line)!!
        assertEquals(s.copy(accuracyM = 8.0, photoAtMs = null), back)
        assertNull(SharedSpotRules.decode("car", "0\t0\t8\t1\t"), "no real point")
        assertNull(SharedSpotRules.decode("car", "nonsense"))
        assertNull(SharedSpotRules.decode("car", SharedSpotRules.encode(s.copy(label = null)))?.label)
    }

    @Test
    fun onlyWhatChangedIsWrittenAndADroveOffIsCleared() {
        val s = spot.copy(savedAtMs = at(14, 5))
        val line = SharedSpotRules.encode(s)
        val (put, clear) = SharedSpotRules.changes(mapOf("car" to s, "nope" to s.copy(carId = "nope")), mapOf("car2" to "x"))
        assertEquals(mapOf("car" to line), put)
        assertEquals(setOf("car2"), clear)
        // Nothing new: nothing written (a new photo doesn't count).
        val (p2, c2) = SharedSpotRules.changes(mapOf("car" to s.copy(photoAtMs = 99L)), mapOf("car" to line))
        assertTrue(p2.isEmpty() && c2.isEmpty())
    }

    @Test
    fun theWristSaysWhichCarAndWhereWithTwoCars() {
        val now = at(16, 5)
        val spots = mapOf("car" to spot.copy(savedAtMs = at(14, 5)), "car2" to spot.copy(carId = "car2", label = null, savedAtMs = at(15, 50)))
        val two = SharedSpotRules.watch(spots, cars("the Golf", "the family car"), now, cal)
        assertEquals(listOf("PARKED · THE GOLF", "PARKED · THE FAMILY CAR"), two.map { it.label })
        assertEquals("Level 3 · Row F · parked 14:05 · 2 h ago", two[0].line)
        assertEquals("Parked 15:50 · 15 min ago", two[1].line)
        val one = SharedSpotRules.watch(mapOf("car" to spots.getValue("car")), cars("the Golf"), now, cal)
        assertEquals("PARKED", one.single().label)
    }

    @Test
    fun withoutACompassTheArrowPointsFromNorthAndSaysSo() {
        val s = spot.copy(label = null)
        val here = LocationFix(51.499, -0.1, 5.0, 0L) // about 111 m south of the car
        val view = ParkingRules.find(s, here, null)
        assertTrue(abs(SharedSpotRules.arrowDeg(s, here, view)!!) < 0.5)
        assertEquals(listOf(view.line, view.accuracy!!, SharedSpotRules.NO_COMPASS), SharedSpotRules.findLines(view, compass = false))
        assertEquals(listOf(view.line, view.accuracy!!), SharedSpotRules.findLines(view, compass = true))
        val withHeading = ParkingRules.find(s, here, 90.0)
        assertTrue(abs(SharedSpotRules.arrowDeg(s, here, withHeading)!! + 90.0) < 0.5)
        assertNull(SharedSpotRules.arrowDeg(s, null, ParkingRules.find(s, null, null)))
    }
}
