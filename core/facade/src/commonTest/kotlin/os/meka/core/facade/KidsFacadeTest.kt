package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.LifeTile
import os.meka.core.domain.KidsRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kids' admin, slice 1, through the facade: sizes typed on one app reach the other; the check lights three months on. */
class KidsFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun sizesSyncAndTheCheckComesBackAfterThreeMonths() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // No children yet: About you first, and a size for a stranger is turned away.
        assertEquals(KidsRules.NO_CHILDREN, fold.kidsView.value.empty)
        assertTrue(fold.setKidSize("Alex", "shoes", "13").refused)
        fold.addAboutYouChild("Alex, U7, Oakfield School")
        fold.addAboutYouChild("Sam, U10")
        assertEquals(listOf("Alex", "Sam"), fold.kidsView.value.kids.map { it.name })
        assertTrue(fold.lifeLines.value.needsSetUp(LifeTile.KIDS))

        assertEquals("Saved · shoes 13", fold.setKidSize("Alex", "shoes", "13").line)
        assertTrue(fold.setKidSize("Alex", "tops", "huge").refused)
        assertEquals("Alex · shoes 13", fold.lifeLines.value.of(LifeTile.KIDS)?.text)
        fold.syncNow(); mac.syncNow()
        assertEquals("Shoes 13", mac.kidsView.value.kids.first().sizesLine)
        assertEquals("Sizes checked today", mac.kidsView.value.kids.first().checkedLine)

        // Cleared on the Mac with Undo's value; put back.
        val cleared = mac.setKidSize("Alex", "shoes", " ")
        assertEquals("13", cleared.undo)
        assertNull(mac.kidsView.value.kids.first().sizesLine)
        mac.setKidSize("Alex", "shoes", cleared.undo!!)

        // Three months on, both apps ask for a check; All still fit puts it away.
        now += 92L * 86_400_000L
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertEquals("Check Alex's sizes", fold.lifeLines.value.of(LifeTile.KIDS)?.text)
        assertTrue(fold.lifeLines.value.of(LifeTile.KIDS)!!.lit)
        assertEquals(KidsRules.fitLine("Alex"), fold.kidSizesFit("Alex"))
        assertNull(fold.kidSizesFit("Sam")) // nothing kept for Sam
        assertEquals("Alex · shoes 13", fold.lifeLines.value.of(LifeTile.KIDS)?.text)
    }

    @Test
    fun theSizeCheckHeadsUpComesOnSaturdayAndAllStillFitAnswersItOnBothApps() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.addAboutYouChild("Alex, U7, Oakfield School")
        fold.setKidSize("Alex", "shoes", "13")
        fold.syncNow(); mac.syncNow()
        fun kids(r: os.meka.core.domain.GovernorResult) = r.post.filter { it.source == os.meka.core.domain.NoticeSource.KIDS }

        // Tue 12 Jan 2027: due, but the heads-up waits for Saturday.
        now += 92L * 86_400_000L
        mac.tick()
        assertTrue(kids(mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        // Sat 16 Jan, 09:30.
        now += 4L * 86_400_000L + 30 * 60_000L
        mac.tick()
        val gov = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val n = kids(gov).single()
        assertEquals("Time to check Alex's sizes", n.title)
        assertEquals("Last checked Mon 12 Oct · Shoes 13 · do they still fit?", n.text)
        assertEquals(listOf(os.meka.core.domain.NoticeAction.ALL_STILL_FIT), n.actions)
        assertTrue(kids(mac.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty()) // once

        // All still fit on the Mac's heads-up: the check is stamped, gone from the Fold's governor too.
        assertNull(mac.kidsStillFit("date-night:1:nudge"))
        assertEquals(KidsRules.fitLine("Alex"), mac.kidsStillFit(n.key))
        assertEquals("Sizes checked today", mac.kidsView.value.kids.single().checkedLine)
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertTrue(kids(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        assertEquals("Alex · shoes 13", fold.lifeLines.value.of(LifeTile.KIDS)?.text)
    }

    @Test
    fun clubFeesGoOnTheRadarWithPaidRemoveAndUndo() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.addAboutYouChild("Alex, U7, Oakfield School")
        assertTrue(fold.addKidFee("Sam", "Football £35 monthly from 1 Nov").refused) // not in About you
        assertEquals(KidsRules.FEE_NO_DATE, fold.addKidFee("Alex", "Football £35 monthly").line)
        assertEquals(
            "Added Football · £35 · every month · due Sun 1 Nov · reminds you 3 days before",
            fold.addKidFee("Alex", "Football £35 monthly from 1 Nov").line,
        )
        val fee = fold.kidsView.value.kids.single().fees.single()
        assertEquals("£35 · every month · next Sun 1 Nov", fee.line)
        // It's a bill on the radar, for Alex.
        val onRadar = fold.listsView.value.renewals.all.single { it.id == fee.id }
        assertEquals("Alex", onRadar.subject)
        fold.syncNow(); mac.syncNow()
        assertEquals("Football", mac.kidsView.value.kids.single().fees.single().title)

        // Paid moves it to December; Undo puts November back.
        val paid = mac.kidFeePaid(fee.id)
        assertEquals("Paid Football · next Tue 1 Dec", paid.line)
        assertEquals("£35 · every month · next Tue 1 Dec", mac.kidsView.value.kids.single().fees.single().line)
        assertTrue(mac.undoKidFee(paid.feeUndo!!))
        assertEquals("£35 · every month · next Sun 1 Nov", mac.kidsView.value.kids.single().fees.single().line)
        assertFalse(mac.undoKidFee(fee.id)) // nothing left to undo

        // Remove takes it off the card and the radar; Undo brings it back.
        val removed = mac.removeKidFee(fee.id)
        assertEquals("Removed Football · Undo puts it back", removed.line)
        assertTrue(mac.kidsView.value.kids.single().fees.isEmpty())
        assertTrue(mac.listsView.value.renewals.all.none { it.id == fee.id })
        assertTrue(mac.undoKidFee(fee.id))
        assertEquals(1, mac.kidsView.value.kids.single().fees.size)

        // Three days before, the tile leads with it, lit.
        now = 1_793_354_400_000L // Fri 30 Oct 2026, 10:00 in London (GMT)
        mac.tick()
        val tile = mac.lifeLines.value.of(LifeTile.KIDS)!!
        assertEquals("Alex's Football · £35 due Sun 1 Nov", tile.text)
        assertTrue(tile.lit)
    }

    @Test
    fun thisWeekGathersSchoolAndFeesOnBothApps() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        assertNull(fold.kidsView.value.week) // no children, no week
        fold.addAboutYouChild("Alex, U7, Oakfield School")
        assertEquals(os.meka.core.domain.KidsWeekRules.EMPTY, fold.kidsView.value.week?.line)
        fold.addSchool("PE Fridays")
        fold.addKidFee("Alex", "Swimming £60 on the 14th every month")
        fold.syncNow(); mac.syncNow()
        val week = mac.kidsView.value.week!!
        // Mon 12 Oct: PE on Friday, the fee on Wednesday.
        assertEquals(listOf("Wed 14 Oct", "Fri 16 Oct"), week.days.map { it.label })
        assertEquals("Alex's Swimming · £60 due", week.days[0].items.single().text)
        assertEquals("PE kit", week.days[1].items.single().text)
        assertEquals("2 things in the next 7 days", week.line)
    }

    @Test
    fun choresAndPocketMoneySyncTickAndPayWithUndo() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.addAboutYouChild("Alex, U7, Oakfield School")
        assertTrue(fold.addKidChore("Sam", "Tidy room £1").refused) // not in About you
        assertFalse(fold.kidsView.value.kids.single().money.shown)
        assertEquals("Added Tidy room · £1 each time", fold.addKidChore("Alex", "Tidy room £1").line)
        assertEquals("Alex's pocket money · £5 a week", fold.setKidPocketMoney("Alex", "£5").line)
        assertTrue(fold.setKidPocketMoney("Alex", "loads").refused)
        val chore = fold.kidsView.value.kids.single().money.chores.single()
        // Mon 12 Oct: Monday ticks; Tuesday can't be ticked yet.
        val monday = chore.days[0].day
        assertEquals("Tidy room · done today", fold.tickKidChore(chore.id, monday).line)
        assertTrue(fold.tickKidChore(chore.id, monday + 1).refused)
        fold.syncNow(); mac.syncNow()
        val m = mac.kidsView.value.kids.single().money
        assertEquals("This week · £5 pocket money + £1 for chores = £6 · 1 chore done", m.weekLine)
        assertEquals("Paid £6", m.payLabel)

        // Paid on the Mac, with Undo.
        val paid = mac.kidPocketMoneyPaid("Alex")
        assertEquals("Marked £6 paid to Alex for this week", paid.line)
        assertEquals("Paid £6 this week", mac.kidsView.value.kids.single().money.paidLine)
        assertTrue(mac.kidPocketMoneyPaid("Alex").refused) // nothing more to pay
        assertTrue(mac.undoKidMoney(paid.moneyUndo!!))
        assertEquals("Paid £6", mac.kidsView.value.kids.single().money.payLabel)
        assertFalse(mac.undoKidMoney(paid.moneyUndo!!))

        // Removing a chore, and Undo.
        val removed = mac.removeKidChore(chore.id)
        assertEquals("Removed Tidy room · Undo puts it back", removed.line)
        assertTrue(mac.kidsView.value.kids.single().money.chores.isEmpty())
        assertTrue(mac.undoKidMoney(removed.moneyUndo!!))
        assertEquals("£1 each · 1 this week", mac.kidsView.value.kids.single().money.chores.single().line)

        // Sunday, still owed: the tile asks.
        now += 6L * 86_400_000L
        mac.tick()
        val tile = mac.lifeLines.value.of(LifeTile.KIDS)!!
        assertEquals("Alex's pocket money · £6 to pay", tile.text)
        assertTrue(tile.lit)
        // Stopping pocket money, and Undo.
        val stopped = mac.setKidPocketMoney("Alex", "")
        assertEquals("Stopped Alex's pocket money · Undo puts it back", stopped.line)
        assertNull(mac.kidsView.value.kids.single().money.pocketValue)
        assertTrue(mac.undoKidMoney(stopped.moneyUndo!!))
        assertEquals("£5", mac.kidsView.value.kids.single().money.pocketValue)
    }
}
