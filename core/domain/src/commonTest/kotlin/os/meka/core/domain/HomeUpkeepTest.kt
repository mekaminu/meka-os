package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUpkeepTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "H${n++}"

    private val d = world.device("android")
    private val r = Renewals(d.replica, ::ids, { world.clock.nowMs })

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun at(y: Int, m: Int, day: Int) { world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + 10 * 3_600_000L }
    private fun row(v: HomeUpkeepView, id: String) = v.rows.single { it.presetId == id }

    @Test
    fun theSuggestedJobsCarryTheirIntervalNudgeAndFirstDate() {
        at(2026, 10, 10)
        val v = HomeUpkeepRules.view(r.items(), today())
        assertEquals(listOf("alarms", "boiler", "gutters", "filters", "home-insurance", "tv-licence"), v.rows.map { it.presetId })
        assertEquals("6 jobs a home needs · add the ones that apply", v.summary)
        assertEquals(0, v.trackedCount)

        val alarms = row(v, "alarms")
        assertEquals("Every month · a nudge on the day", alarms.line)
        assertEquals(CivilDate.toEpochDay(2026, 11, 1), alarms.firstDueDay)
        assertTrue(alarms.dateKnown)
        // Gutters: the next 1 Nov or 1 Apr; in October that's November.
        assertEquals("Every 6 months · shown a week before", row(v, "gutters").line)
        assertEquals(CivilDate.toEpochDay(2026, 11, 1), row(v, "gutters").firstDueDay)
        assertEquals("Every 3 months · shown 3 days before", row(v, "filters").line)
        assertEquals("Every year · shown 4 weeks before · you set the date", row(v, "boiler").line)
        assertFalse(row(v, "home-insurance").dateKnown)
        assertEquals("Every year · shown 3 weeks before · you set the date", row(v, "home-insurance").line)

        // After the November one the gutters come round in April; December wraps the year.
        at(2026, 11, 1)
        assertEquals(CivilDate.toEpochDay(2027, 4, 1), HomeUpkeepRules.firstDueDay(HomeUpkeepRules.preset("gutters")!!, today()))
        at(2026, 12, 20)
        assertEquals(CivilDate.toEpochDay(2027, 1, 1), HomeUpkeepRules.firstDueDay(HomeUpkeepRules.preset("alarms")!!, today()))
    }

    @Test
    fun addingAJobPutsItOnTheRadarOnceWithItsRhythm() {
        at(2026, 10, 10)
        val id = r.addHomeUpkeep("alarms", today())
        val item = r.items().single()
        assertEquals(id, item.id)
        assertEquals("Test the smoke and CO alarms", item.title)
        assertEquals(ObligationKind.HOME, item.kind)
        assertEquals(RenewalRepeat.MONTHLY, item.repeats)
        assertEquals(0, item.leadDays)
        assertEquals("Done", item.doneLabel)
        assertEquals(RenewalState.LATER, item.state)

        val v = HomeUpkeepRules.view(r.items(), today())
        assertEquals(id, row(v, "alarms").trackedId)
        assertEquals("On the radar · due Sun 1 Nov", row(v, "alarms").line)
        assertEquals("1 of 6 on the radar", v.summary)
        assertFailsWith<ValidationException> { r.addHomeUpkeep("alarms", today()) }
        assertFailsWith<ValidationException> { r.addHomeUpkeep("nope", today()) }
        assertEquals("Added “Clear the gutters” · Sun 1 Nov", HomeUpkeepRules.addedLine("Clear the gutters", CivilDate.toEpochDay(2026, 11, 1), today()))
    }

    @Test
    fun oneNudgeOnTheDayThenGoneUntilNextTime() {
        at(2026, 10, 10)
        val id = r.addHomeUpkeep("alarms", today())
        assertTrue(r.view().attention.isEmpty())

        at(2026, 11, 1)
        assertEquals(listOf(id), r.view().attention.map { it.id })
        val lists = ListsView.EMPTY.copy(renewals = r.view())
        val todayView = Today(emptyList(), null, emptyList(), emptyList())
        val notices = NoticeSources.collect(lists, FastingView.EMPTY, ShutdownView.EMPTY, todayView, world.clock.nowMs, LocalCalendar.UTC)
            .filter { it.source == NoticeSource.RENEWAL }
        assertEquals(listOf("renewal:$id:${today()}"), notices.map { it.key })

        r.done(id)
        assertTrue(r.view().attention.isEmpty())
        assertEquals(CivilDate.toEpochDay(2026, 12, 1), r.items().single().dueDay)
    }

    @Test
    fun aJobAlreadyTrackedByHandCountsAndSixMonthlyRoundTrips() {
        at(2026, 10, 10)
        val boiler = r.add("Worcester boiler", ObligationKind.BOILER, today() + 50, RenewalRepeat.YEARLY)
        val insurance = r.add("Aviva Home Insurance", ObligationKind.INSURANCE, today() + 90, RenewalRepeat.YEARLY)
        r.add("Netflix", ObligationKind.SUBSCRIPTION, today() + 5, RenewalRepeat.MONTHLY)
        val v = HomeUpkeepRules.view(r.items(), today())
        assertEquals(boiler, row(v, "boiler").trackedId)
        assertEquals(insurance, row(v, "home-insurance").trackedId)
        assertNull(row(v, "tv-licence").trackedId)
        assertFailsWith<ValidationException> { r.addHomeUpkeep("boiler", today()) }

        val g = r.addHomeUpkeep("gutters", today())
        val item = r.items().single { it.id == g }
        assertEquals(RenewalRepeat.HALF_YEARLY, item.repeats)
        assertEquals("Every 6 months", item.repeatLabel)
        r.done(g)
        assertEquals(CivilDate.toEpochDay(2027, 5, 1), r.items().single { it.id == g }.dueDay)
        assertEquals("Home upkeep", RenewalRules.kindLabel(ObligationKind.HOME))
    }
}
