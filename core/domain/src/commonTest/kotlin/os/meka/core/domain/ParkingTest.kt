package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParkingTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_740L // Wed 14 Oct 2026
    private fun at(h: Int, m: Int = 0, d: Long = day) = cal.toEpochMs(d, h * 60 + m)

    // An invented spot in a car park, and points around it (rule 13: no real places).
    private val lat = 51.5000
    private val lon = -0.1000

    private fun cars(vararg titles: String) = CarsView(
        titles.mapIndexed { i, t -> CarView.EMPTY.copy(carId = CarRules.CAR_IDS[i], title = t) }, true,
    )

    @Test
    fun theMostCertainRecentFixIsSavedAndAPoorOneRefused() {
        val now = at(14, 5)
        val fixes = listOf(
            LocationFix(lat, lon, 40.0, now - 5_000),
            LocationFix(lat + 0.0001, lon, 6.0, now - 3_000),
            LocationFix(lat, lon + 0.0001, 6.0, now - 1_000), // as certain, newer: wins
            LocationFix(lat, lon, 2.0, now - 60_000), // too old
            LocationFix(0.0, 0.0, 1.0, now), // no real point
        )
        val best = ParkingRules.bestFix(fixes, now)
        assertEquals(lon + 0.0001, best?.longitude)
        assertNull(ParkingRules.bestFix(emptyList(), now))

        val saved = ParkingRules.save(emptyMap(), "car", "Car", best, now)
        assertTrue(saved.saved)
        assertEquals("Saved where Car is · ±6 m", saved.line)
        assertEquals(6.0, saved.spots.getValue("car").accuracyM)

        val rough = ParkingRules.save(emptyMap(), "car", "Car", LocationFix(lat, lon, 45.0, now), now)
        assertEquals("Saved where Car is · ±45 m · rough, add the bay", rough.line)
        val refused = ParkingRules.save(saved.spots, "car", "Car", LocationFix(lat, lon, 900.0, now), now)
        assertFalse(refused.saved)
        assertEquals(saved.spots, refused.spots)
        assertEquals(ParkingRules.NO_FIX, ParkingRules.save(emptyMap(), "car", "Car", null, now).line)
        assertEquals(ParkingRules.NO_FIX, ParkingRules.save(emptyMap(), "car", "Car", LocationFix(lat, lon, null, now), now).line)
    }

    @Test
    fun eachCarKeepsItsOwnSpotOnThePhoneAndDroveOffForgetsIt() {
        val now = at(9, 15)
        var spots = ParkingRules.save(emptyMap(), "car", "the Golf", LocationFix(lat, lon, 5.0, now), now).spots
        spots = ParkingRules.save(spots, "car2", "the van", LocationFix(lat + 0.01, lon, 8.0, now), now).spots
        spots = ParkingRules.withLabel(spots, "car", "level 3 row f")
        assertEquals("Level 3 · Row F", spots.getValue("car").label)
        // Kept as text on the phone, and read back the same (a tab in a label can't break it).
        val back = ParkingRules.decode(ParkingRules.encode(ParkingRules.withLabel(spots, "car2", "bay\t12").values))
        assertEquals(spots.getValue("car"), back.getValue("car"))
        assertEquals("Bay 12", back.getValue("car2").label)
        assertTrue(ParkingRules.decode("junk\nmore\tjunk").isEmpty())
        // Saving again starts a fresh spot: the old bay doesn't carry over.
        val again = ParkingRules.save(spots, "car", "the Golf", LocationFix(lat, lon, 5.0, now), now).spots
        assertNull(again.getValue("car").label)
        val gone = ParkingRules.droveOff(spots, "car")
        assertFalse("car" in gone)
        assertTrue("car2" in gone)
        // A label for a car that isn't parked changes nothing.
        assertEquals(gone, ParkingRules.withLabel(gone, "car", "level 2"))
        // Which car: only asked with two or more.
        assertEquals(emptyList(), ParkingRules.choices(cars("Car")))
        assertEquals(listOf("car" to "the Golf", "car2" to "the van"), ParkingRules.choices(cars("the Golf", "the van")))
        assertEquals("car", ParkingRules.onlyCar(cars("Car")))
    }

    @Test
    fun theBayReadsAsOnTheSign() {
        assertEquals("Level 3 · Row F", ParkingRules.cleanLabel("level 3 row f"))
        assertEquals("Level 2 · Blue zone", ParkingRules.cleanLabel("level 2, blue zone"))
        assertEquals("Second floor · Near the lifts", ParkingRules.cleanLabel("second floor near the lifts"))
        assertEquals("Zone B · Level -1", ParkingRules.cleanLabel("Zone b level -1"))
        assertEquals("L3 F12", ParkingRules.cleanLabel("l3 f12"))
        assertNull(ParkingRules.cleanLabel("   "))
        assertNull(ParkingRules.cleanLabel(null))
        assertTrue(ParkingRules.cleanLabel("level 1 ".repeat(20))!!.length <= ParkingRules.MAX_LABEL)
    }

    @Test
    fun findMyCarPointsTheWayAndSaysWhenHeIsThere() {
        val spot = ParkedSpot("car", lat, lon, 8.0, at(9), "Level 3 · Row F")
        // About 84 m due north of where he stands.
        val south = lat - 84.0 / 111_195.0
        val here = LocationFix(south, lon, 6.0, at(17))
        assertTrue(abs(ParkingRules.distanceM(south, lon, lat, lon) - 84.0) < 1.0)
        assertTrue(abs(ParkingRules.bearingDeg(south, lon, lat, lon)) < 0.5 || abs(ParkingRules.bearingDeg(south, lon, lat, lon) - 360) < 0.5)

        // Facing east: the car is to his left.
        val east = ParkingRules.find(spot, here, 90.0)
        assertEquals("84 m · left", east.line)
        assertTrue(abs(east.arrowDeg!! + 90.0) < 1.0)
        assertEquals("You ±6 m · the car ±8 m", east.accuracy)
        assertEquals("84 m · ahead left", ParkingRules.find(spot, here, 40.0).line)
        assertEquals("84 m · behind", ParkingRules.find(spot, here, 180.0).line)
        // No compass: the compass point instead, and no arrow.
        val noHeading = ParkingRules.find(spot, here, null)
        assertEquals("84 m · north", noHeading.line)
        assertNull(noHeading.arrowDeg)
        // No fix yet.
        assertEquals(ParkingRules.FINDING, ParkingRules.find(spot, null, 0.0).line)

        // Close: look around, one buzz only on arriving.
        val close = ParkingRules.find(spot, LocationFix(lat - 10.0 / 111_195.0, lon, 5.0, at(17)), 0.0)
        assertTrue(close.close)
        assertEquals("Within 15 m · look around for Level 3 · Row F", close.line)
        assertTrue(ParkingRules.buzz(false, close))
        assertFalse(ParkingRules.buzz(true, close))
        assertFalse(ParkingRules.buzz(false, east))
    }

    @Test
    fun distancesDirectionsAndTheMapsLink() {
        assertEquals("8 m", ParkingRules.distanceText(8.2))
        assertEquals("350 m", ParkingRules.distanceText(348.0))
        assertEquals("1.2 km", ParkingRules.distanceText(1_240.0))
        assertEquals("12 km", ParkingRules.distanceText(12_400.0))
        assertEquals("1 m", ParkingRules.metres(0.2))
        assertEquals("ahead right", ParkingRules.relativeWords(30.0))
        assertEquals("right", ParkingRules.relativeWords(-270.0))
        assertEquals("behind left", ParkingRules.relativeWords(-140.0))
        assertEquals("north-west", ParkingRules.compassPoint(-40.0))
        assertEquals("south", ParkingRules.compassPoint(181.0))
        assertEquals("-0.100000", ParkingRules.fixed6(-0.1))
        assertEquals("51.500001", ParkingRules.fixed6(51.5000005))
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=51.500000,-0.100000&travelmode=walking",
            ParkingRules.mapsUrl(ParkedSpot("car", lat, lon, 5.0, 0L)),
        )
    }

    @Test
    fun theRowsAndTheTileSayWhereAndWhen() {
        val spots = mapOf(
            "car2" to ParkedSpot("car2", lat, lon, 40.0, at(14, 5)),
            "car" to ParkedSpot("car", lat, lon, 6.0, at(14, 5, day - 1), "Level 3 · Row F"),
        )
        val two = cars("the Golf", "the van")
        val rows = ParkingRules.rows(spots, two, at(16, 35), cal)
        assertEquals(listOf("car", "car2"), rows.map { it.carId })
        assertEquals("Parked yesterday 14:05 · ±6 m", rows[0].line)
        assertEquals("Parked 14:05 · 2 h 30 ago · ±40 m · rough", rows[1].line)
        assertEquals(LifeTileLine("2 cars parked", true), ParkingRules.tileLine(rows, at(16, 35)))
        assertEquals(LifeTileLine("The Golf parked · Level 3 · Row F", true), ParkingRules.tileLine(rows.take(1), at(16, 35)))
        val one = ParkingRules.rows(mapOf("car" to ParkedSpot("car", lat, lon, 6.0, at(16, 23))), cars("Car"), at(16, 35), cal)
        assertEquals(LifeTileLine("Parked 12 min ago", true), ParkingRules.tileLine(one, at(16, 35)))
        assertNull(ParkingRules.tileLine(emptyList(), at(16, 35)))
        assertEquals("Parked ${CivilDate.shortLabel(day - 2)} 09:00", ParkingRules.parkedLine(at(9, 0, day - 2), at(10), cal))
        assertEquals("Parked 09:59 · just now", ParkingRules.parkedLine(at(9, 59), at(9, 59), cal))

        // The grid: while parked, the Car tile says so and isn't waiting to be set up.
        val lines = LifeTileRules.lines(LifeTileInputs())
        assertTrue(lines.needsSetUp(LifeTile.CAR))
        val parked = ParkingRules.withParked(lines, LifeTileLine("Parked 12 min ago", true))
        assertEquals("Parked 12 min ago", parked.of(LifeTile.CAR)?.text)
        assertFalse(parked.needsSetUp(LifeTile.CAR))
        assertEquals(LifeTile.CAR, LifeTileRules.ordered(parked).first())
        assertEquals(lines, ParkingRules.withParked(lines, null))
    }

    @Test
    fun theCarTileSaysTheNextDateOnBothApps() {
        fun item(id: String, title: String, kind: ObligationKind, due: Long, subject: String? = null) = RenewalItem(
            id = id, title = title, kind = kind, subject = subject, notes = null, dueDay = due, cancelByDay = null, leadDays = 14,
            costPence = null, repeats = RenewalRepeat.YEARLY, repeatLabel = "Every year", state = RenewalState.LATER, meta = "",
            doneLabel = "Done", stopLabel = "Stop tracking", hasConflict = false,
        )
        val first = CarEntry(CarRules.ENTITY_ID, "AB12CDE", null)
        val items = listOf(
            item("r1", "Car insurance", ObligationKind.INSURANCE, day + 40),
            item("r2", "MOT", ObligationKind.MOT, day + 5),
            item("r3", "Boiler service", ObligationKind.BOILER, day + 1),
        )
        val line = ParkingRules.carLine(listOf(first), items, day)
        assertEquals("MOT due ${CivilDate.shortLabel(day + 5)}", line?.text)
        assertTrue(line!!.lit)
        val two = listOf(first.copy(name = "the Golf"), CarEntry("car2", null, "the van"))
        val later = listOf(item("r4", "Car tax", ObligationKind.CAR_TAX, day + 20, subject = "the van"))
        val vans = ParkingRules.carLine(two, later, day)
        assertEquals("Car tax due ${CivilDate.shortLabel(day + 20)} · the van", vans?.text)
        assertFalse(vans!!.lit)
        assertEquals(LifeTileLine("AB12 CDE · add its dates", false), ParkingRules.carLine(listOf(first), emptyList(), day))
        assertNull(ParkingRules.carLine(emptyList(), emptyList(), day))
        assertEquals("Add your car's dates", LifeTileRules.lines(LifeTileInputs()).of(LifeTile.CAR)?.text)
        assertEquals("MOT due Fri", LifeTileRules.lines(LifeTileInputs(carLine = LifeTileLine("MOT due Fri", true))).of(LifeTile.CAR)?.text)
    }

    @Test
    fun talkOpensFindMyCarOnTheFoldOnly() {
        listOf(
            "where did I park?", "where did I park the car", "where did I leave my car", "where's my car", "where is the car",
            "find my car", "OK Navilife, find my car please", "where am I parked", "show me where I parked",
        ).forEach { assertEquals(TalkPlace.CAR, TalkOpenRules.request(it, TalkOpenRules.FOLD), it) }
        assertNull(TalkOpenRules.request("where's my car", TalkOpenRules.DEFAULT))
        assertNull(TalkOpenRules.request("when's the car's MOT?", TalkOpenRules.FOLD))
        assertNull(TalkOpenRules.request("is the car taxed", TalkOpenRules.FOLD))
        assertEquals(ParkingRules.OPENING, TalkOpenRules.opening(TalkPlace.CAR))
        assertTrue(TalkRules.reply("where did I park", 0, TalkOpenRules.FOLD) is TalkReply.Open)
        assertNotNull(HelpRules.entry("car"))
    }

    @Test
    fun talkFindsTheCarItNames() {
        val two = CarsView(listOf(
            CarView.EMPTY.copy(carId = CarRules.ENTITY_ID, title = "the Golf", name = "the Golf"),
            CarView.EMPTY.copy(carId = CarRules.CAR_IDS[1], title = "the family car", name = "the family car"),
        ), true)
        assertEquals(TalkCar("where's car", CarRules.ENTITY_ID), ParkingRules.talkCar("Where's the Golf?", two))
        assertEquals(CarRules.ENTITY_ID, ParkingRules.talkCar("OK Navilife, find the golf please", two)?.carId)
        assertEquals(CarRules.CAR_IDS[1], ParkingRules.talkCar("where did I park the family car", two)?.carId)
        assertEquals(TalkPlace.CAR, TalkOpenRules.request(ParkingRules.talkCar("where is my family car", two)!!.utterance, TalkOpenRules.FOLD))
        // Unnamed cars: "Car 2" (and "car two") names the second only when there are two.
        val unnamed = cars("Car 1", "Car 2")
        assertEquals(CarRules.CAR_IDS[1], ParkingRules.talkCar("where's car two", unnamed)?.carId)
        assertEquals(CarRules.CAR_IDS[1], ParkingRules.talkCar("find car 2", unnamed)?.carId)
        assertNull(ParkingRules.talkCar("where's my car", unnamed))
        assertNull(ParkingRules.talkCar("where's car 2", cars("Car")))
        // A question about the car stays a question; no name, nothing.
        assertNull(ParkingRules.talkCar("when's the Golf's MOT?", two))
        assertNull(ParkingRules.talkCar("is the golf taxed", two))
        assertNull(ParkingRules.talkCar("where did I park", two))
        assertNull(ParkingRules.talkCar("where's the van", two))
    }

    @Test
    fun findMyCarOpensOnTheNamedCarOrSaysItIsntParked() {
        val now = at(10, 0)
        val two = cars("the Golf", "the van")
        val spots = ParkingRules.save(emptyMap(), CarRules.CAR_IDS[1], "the van", LocationFix(lat, lon, 5.0, now), now).spots
        val rows = ParkingRules.rows(spots, two, now, cal)
        assertEquals(FindFirst(CarRules.CAR_IDS[1], null), ParkingRules.findFirst(rows, null, two))
        assertEquals(FindFirst(CarRules.CAR_IDS[1], null), ParkingRules.findFirst(rows, CarRules.CAR_IDS[1], two))
        assertEquals(
            FindFirst(null, "The Golf isn't saved as parked · tap “I've parked here” when you leave it"),
            ParkingRules.findFirst(rows, CarRules.ENTITY_ID, two),
        )
        assertEquals(FindFirst(null, null), ParkingRules.findFirst(emptyList(), null, two))
    }

    @Test
    fun theBaysPhotoRidesOnTheSpotOnThePhoneAndIsTidiedWhenItGoes() {
        val now = at(10, 0)
        var spots = ParkingRules.save(emptyMap(), "car", "the Golf", LocationFix(lat, lon, 5.0, now), now).spots
        spots = ParkingRules.save(spots, "car2", "the van", LocationFix(lat + 0.01, lon, 8.0, now), now).spots
        assertEquals(ParkingRules.PHOTO, ParkingRules.photoAction(spots.getValue("car")))
        // Not parked: nothing to attach it to.
        assertEquals(spots, ParkingRules.withPhoto(spots, "car3", now))
        spots = ParkingRules.withPhoto(spots, "car", now + 60_000)
        assertEquals(now + 60_000, spots.getValue("car").photoAtMs)
        assertEquals(ParkingRules.RETAKE, ParkingRules.photoAction(spots.getValue("car")))
        // Kept with the spot on the phone, and a spot saved before slice 4 (six fields) still reads.
        assertEquals(spots, ParkingRules.decode(ParkingRules.encode(spots.values)))
        val old = "car\t51.5\t-0.1\t6.0\t$now\tLevel 2"
        assertNull(ParkingRules.decode(old).getValue("car").photoAtMs)
        assertEquals("Level 2", ParkingRules.decode(old).getValue("car").label)
        // The row says there's a photo.
        assertTrue(ParkingRules.rows(spots, cars("the Golf", "the van"), now + 120_000, cal).first().line.endsWith(" · photo"))
        // A new spot for the car starts without one.
        val again = ParkingRules.save(spots, "car", "the Golf", LocationFix(lat, lon, 5.0, now), now + 3_600_000).spots
        assertNull(again.getValue("car").photoAtMs)
        assertNull(ParkingRules.withoutPhoto(spots, "car").getValue("car").photoAtMs)

        // File names can't leave the folder.
        assertEquals("bay-car2.jpg", ParkingRules.photoFile("car2"))
        assertEquals("bay----etc.jpg", ParkingRules.photoFile("../etc"))
        assertEquals("bay-car.jpg", ParkingRules.photoFile(""))
        // Tidying: photos no parked car uses go; one kept for Undo stays; other files are never touched.
        val files = listOf("bay-car.jpg", "bay-car2.jpg", "bay-car3.jpg", "notes.txt", "bay-car.tmp")
        assertEquals(listOf("bay-car2.jpg", "bay-car3.jpg"), ParkingRules.photosToDelete(spots, files))
        val droveOff = ParkingRules.droveOff(spots, "car")
        assertEquals(listOf("bay-car.jpg", "bay-car2.jpg", "bay-car3.jpg"), ParkingRules.photosToDelete(droveOff, files))
        assertEquals(listOf("bay-car2.jpg", "bay-car3.jpg"), ParkingRules.photosToDelete(droveOff, files, keep = spots.getValue("car")))

        // Shrunk to 1600 px on the long side, the shape kept; small ones as they are.
        assertEquals(1600 to 1200, ParkingRules.photoSize(4000, 3000))
        assertEquals(900 to 1600, ParkingRules.photoSize(2250, 4000))
        assertEquals(800 to 600, ParkingRules.photoSize(800, 600))
    }

    @Test
    fun theBayIsReadOffThePhotoOfTheSignOnlyWhenItNamesOne() {
        assertEquals("Level 3 · Zone F", ParkingRules.labelFromSign("WELCOME\nLEVEL 3\nZONE F\nPay at machine"))
        assertEquals("Level 2 · Blue zone", ParkingRules.labelFromSign("LEVEL\n2\nBLUE ZONE"))
        assertEquals("Level 3 · Row F", ParkingRules.labelFromSign("Level 3 Row F"))
        assertEquals("Bay 112", ParkingRules.labelFromSign("Oakfield Centre\nBAY 112"))
        // Two at most; the same line read twice counts once.
        assertEquals("Level 1 · Row C", ParkingRules.labelFromSign("LEVEL 1\nLEVEL 1\nROW C\nBAY 9"))
        // Nothing like a bay: not guessed at.
        assertNull(ParkingRules.labelFromSign("EXIT\nPay at machine"))
        assertNull(ParkingRules.labelFromSign("Car park entrance"))
        assertNull(ParkingRules.labelFromSign("Pay and display before leaving your vehicle level 3"))
        assertNull(ParkingRules.labelFromSign(""))
        assertNull(ParkingRules.labelFromSign(null))
        assertEquals(ParkingRules.PHOTO_KEPT, ParkingRules.photoKeptLine(null))
        assertEquals("Photo kept · read “Level 3 · Zone F” from the sign", ParkingRules.photoKeptLine("Level 3 · Zone F"))
    }
}
