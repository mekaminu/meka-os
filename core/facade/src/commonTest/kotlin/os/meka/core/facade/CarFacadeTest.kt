package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.WeekAheadKind
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car on autopilot through the facade (slice 1): the registration and the MOT typed on the Fold, on the Mac too; the reminders. */
class CarFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theCarTypedOnTheFoldRemindsAMonthAndAWeekBeforeOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals("MOT, tax, insurance and service · type the registration once", fold.carView.value.summary)
        assertFalse(fold.setCarRegistration("not a plate"))
        val mot = fold.addCarDate("mot")!!
        assertNull(fold.addCarDate("mot"))
        assertTrue(fold.setCarRegistration("ab12 cde"))
        assertEquals("AB12 CDE · 1 of 4 on the radar", fold.carView.value.summary)
        // The MOT added before the registration takes it as its subject.
        assertEquals("AB12 CDE", fold.listsView.value.renewals.all.single { it.id == mot }.subject)

        // Due in four weeks: the reminder is today at 09:00, and the radar's digest line leaves it to the car's.
        fold.setRenewalDue(mot, fold.todayEpochDay() + 28)
        fold.syncNow(); mac.syncNow()
        assertEquals("AB12 CDE", mac.carView.value.registration)
        // Car and parking, slice 1: the Car tile on Ask's Life grid says the next date on both apps.
        assertEquals("MOT due Mon 9 Nov", mac.lifeLines.value.of(os.meka.core.domain.LifeTile.CAR)?.text)
        assertEquals("MOT due Mon 9 Nov", fold.lifeLines.value.of(os.meka.core.domain.LifeTile.CAR)?.text)
        assertFalse(mac.lifeLines.value.needsSetUp(os.meka.core.domain.LifeTile.CAR))
        val g = mac.governNotifications(null, DeviceAlerts.ALL)
        val car = g.post.filter { it.source == NoticeSource.CAR }
        assertEquals(listOf("MOT due Mon 9 Nov · AB12 CDE" to "In 4 weeks · an MOT up to a month early keeps the same date"), car.map { it.title to it.text })
        assertTrue(g.post.none { it.source == NoticeSource.RENEWAL } && g.digest?.lines.orEmpty().none { it.startsWith("MOT") })
        assertTrue(mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL).post.none { it.source == NoticeSource.CAR })

        // Three weeks on: the week-before reminder.
        now += 21 * 24 * 3_600_000L
        mac.tick()
        val w = mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf("In a week · an MOT up to a month early keeps the same date"), w.post.filter { it.source == NoticeSource.CAR }.map { it.text })
        // Saturday: next week's week ahead shows the MOT on Monday.
        now += 5 * 24 * 3_600_000L
        mac.tick()
        assertTrue(mac.weekAheadView.value.days.flatMap { it.lines }.any { it.kind == WeekAheadKind.DUE && it.text.startsWith("MOT due") })
    }

    @Test
    fun theDvlasAnswerFromTheServerFillsTheMotAndOffersItsDayOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.setCarRegistration("AB12 CDE"))
        fold.syncNow()
        // MEKA's server writes the DVLA's answer (slice 2) as server ops.
        val server = os.meka.core.sync.Replica(
            "hh", "server", os.meka.core.sync.HlcClock("server", { now }), InMemoryReplicaStore(), os.meka.core.domain.MekaSchema,
        ) { "s" + (n++) }
        val mot = os.meka.core.domain.CivilDate.toEpochDay(2026, 11, 2)
        val check = os.meka.core.domain.CarCheck(
            "AB12CDE", found = true, make = "FORD", motDueDay = mot, taxStatus = "Taxed",
            taxDueDay = os.meka.core.domain.CivilDate.toEpochDay(2026, 12, 1),
            lastTestDay = os.meka.core.domain.CivilDate.toEpochDay(2025, 11, 3), lastPassed = true, advisories = 2, checkedMs = now,
        )
        ops.transaction {
            server.commitLocal(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.CarCheckRules.ENTITY_ID, os.meka.core.domain.CarCheckRules.fields(check))
                .forEach { ops.append(it) }
        }
        fold.syncNow(); mac.syncNow()
        assertEquals("DVLA · Ford · MOT due Mon 2 Nov · taxed until Tue 1 Dec · checked today", mac.carView.value.dvlaLine)
        assertEquals("MOT history · passed 3 Nov 2025 · 2 advisories", fold.carView.value.historyLine)

        // Add takes the DVLA's day; a different day typed later is offered back, and one tap puts it right.
        val id = fold.addCarDate("mot")!!
        assertEquals(mot, fold.listsView.value.renewals.all.single { it.id == id }.dueDay)
        assertFalse(fold.useDvlaDate("mot"))
        fold.setRenewalDue(id, mot + 5)
        fold.syncNow(); mac.syncNow()
        assertEquals("Use the DVLA's date · Mon 2 Nov", mac.carView.value.rows.first().dvlaOffer)
        assertTrue(mac.useDvlaDate("mot"))
        assertEquals(mot, mac.listsView.value.renewals.all.single { it.id == id }.dueDay)
        assertNull(mac.carView.value.rows.first().dvlaOffer)
        assertFalse(mac.useDvlaDate("insurance"))
    }

    private var n = 0

    @Test
    fun aSecondCarAddedOnTheFoldHasItsOwnDatesOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.setCarRegistration("ab12 cde"))
        val mot1 = fold.addCarDate("mot")!!
        val id = fold.addCar("the Golf")!!
        assertEquals(listOf("Car 1", "the Golf"), fold.carsView.value.cars.map { it.title })
        val mot2 = fold.addCarDate(id, "mot")!!
        assertNull(fold.addCarDate(id, "mot"))
        assertEquals("the Golf", fold.listsView.value.renewals.all.single { it.id == mot2 }.subject)
        // Its registration typed: its MOT follows, the first car's doesn't.
        assertTrue(fold.setCarRegistration(id, "xy34 zzz"))
        assertEquals("XY34 ZZZ", fold.listsView.value.renewals.all.single { it.id == mot2 }.subject)
        assertEquals("AB12 CDE", fold.listsView.value.renewals.all.single { it.id == mot1 }.subject)
        assertTrue(fold.setCarName(id, "the family car"))

        fold.syncNow(); mac.syncNow()
        val cars = mac.carsView.value
        assertEquals(listOf("Car 1", "the family car"), cars.cars.map { it.title })
        assertEquals("XY34 ZZZ", cars.carAt(1).registration)
        assertEquals(mot2, cars.carAt(1).rows.first { it.presetId == "mot" }.trackedId)
        assertEquals(mot1, mac.carView.value.rows.first { it.presetId == "mot" }.trackedId)

        // Removed on the Mac: the Fold shows one car, the date stays on the radar; Undo brings it back.
        assertTrue(mac.removeCar(id))
        assertFalse(mac.removeCar(os.meka.core.domain.CarRules.ENTITY_ID))
        mac.syncNow(); fold.syncNow()
        assertEquals(1, fold.carsView.value.count)
        assertTrue(fold.listsView.value.renewals.all.any { it.id == mot2 })
        assertTrue(fold.undoRemoveCar(id))
        assertEquals(2, fold.carsView.value.count)
    }

    @Test
    fun aParkingTimerTypedOnTheFoldShowsOnTheMacAndRemindsTenMinutesBefore() = runTest {
        val fold = core("android")
        val mac = core("mac")
        // Mon 12 Oct 2026, 10:00 in London: "until 11:30 RingGo 81234".
        assertNull(fold.setParkingTimer("car", "somewhere"))
        assertEquals("Parking timer until 11:30 · reminds you at 11:20", fold.setParkingTimer("car", "until 11:30 RingGo 81234"))
        assertEquals("Paid until 11:30 · 1 h 30 left", fold.parkingTimersView.value.rows.single().line)
        fold.syncNow(); mac.syncNow()
        assertEquals("Paid until 11:30 · 1 h 30 left", mac.parkingTimersView.value.rows.single().line)
        assertEquals("RingGo 81234", mac.parkingTimersView.value.rows.single().note)
        // The Car tile leads with it on both apps.
        assertEquals("Paid until 11:30 · 1 h 30 left", mac.lifeLines.value.of(os.meka.core.domain.LifeTile.CAR)?.text)

        val g = mac.governNotifications(null, DeviceAlerts.ALL)
        assertTrue(g.post.none { it.source == NoticeSource.PARKING })
        now += 80 * 60_000L // 11:20
        mac.tick()
        val due = mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf("Parking ends at 11:30"), due.post.filter { it.source == NoticeSource.PARKING }.map { it.title })
        assertEquals("Paid until 11:30 · 10 min left", mac.parkingTimersView.value.rows.single().line)

        // +30 min on the Mac: the Fold follows and the reminder comes again for 12:00.
        assertEquals("Parking timer until 12:00 · reminds you at 11:50", mac.extendParkingTimer("car", 30))
        mac.syncNow(); fold.syncNow()
        assertEquals("Paid until 12:00 · 40 min left", fold.parkingTimersView.value.rows.single().line)

        // Ended, then Undo.
        assertEquals("Parking timer ended", fold.endParkingTimer("car"))
        assertTrue(fold.parkingTimersView.value.rows.isEmpty())
        assertTrue(fold.undoEndParkingTimer("car"))
        assertEquals("Paid until 12:00 · 40 min left", fold.parkingTimersView.value.rows.single().line)
        assertFalse(fold.undoEndParkingTimer("car"))
    }

    @Test
    fun aTimerSetOnTheFoldReachesTheWatchWhichCountsItDownAndExtendsIt() = runTest {
        val fold = core("android")
        val watch = core("watch")
        assertEquals("Parking timer until 11:30 · reminds you at 11:20", fold.setParkingTimer("car", "until 11:30"))
        fold.syncNow(); watch.syncNow()
        val p = watch.watchHome().parking.single()
        assertEquals("PARKING", p.label)
        assertEquals("Paid until 11:30 · 1 h 30 left", p.line)
        assertEquals("Parking · 1 h 30 left", watch.watchTile().parkingLine)
        assertEquals("Park", watch.watchComplication().title)
        // Twenty minutes on, with nothing synced in between, the wrist counts down.
        now += 20 * 60_000L
        assertEquals("Paid until 11:30 · 1 h 10 left", watch.watchHome().parking.single().line)
        // +15 min on the watch: the Fold follows.
        assertEquals("Parking timer until 11:45 · reminds you at 11:35", watch.watchParkingExtend("car"))
        watch.syncNow(); fold.syncNow()
        assertEquals("Paid until 11:45 · 1 h 25 left", fold.parkingTimersView.value.rows.single().line)
        assertNull(watch.watchParkingExtend("car2"))
    }

    @Test
    fun aSharedRingGoConfirmationStartsTheTimerOnTheCarItNamesAndTheMacSeesIt() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.setCarRegistration("ab12 cde"))
        val golf = fold.addCar("the Golf")!!
        assertTrue(fold.setCarRegistration(golf, "xy34 zzz"))
        // Mon 12 Oct 2026, 10:00 in London.
        val text = "RingGo: your parking session has started. Location 81234. Vehicle XY34ZZZ. Expires: 11:45 on 12/10/2026."
        assertNull(fold.parkingFromShared("Lunch with Sam at 12:30", null))
        val offer = fold.parkingFromShared(text, null)!!
        assertEquals(golf, offer.carId)
        assertEquals("Parking timer until 11:45 · the Golf · RingGo 81234", offer.line)
        assertTrue(fold.parkingTimersView.value.rows.isEmpty()) // nothing until he taps
        assertEquals("Parking timer until 11:45 · the Golf · reminds you at 11:35", fold.startParkingFromShared(golf, text, null))
        fold.syncNow(); mac.syncNow()
        val row = mac.parkingTimersView.value.rows.single()
        assertEquals(golf, row.carId)
        assertEquals("Paid until 11:45 · 1 h 45 left", row.line)
        assertEquals("RingGo 81234", row.note)
        // A confirmation whose time has gone says so, on the share sheet and pasted into the Mac's timer field.
        val old = "RingGo: your parking session has started. Location 81234. Expires: 09:30 on 12/10/2026."
        assertEquals("That parking ended at 09:30 · nothing to remind you of", fold.parkingFromShared(old, null)?.line)
        assertNull(fold.startParkingFromShared(golf, old, null))
        assertEquals("That parking ended at 09:30 · nothing to remind you of", mac.setParkingTimer("car", old))
    }
}
