package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls, slice 4: the "Obi on calls" page. Invented data only. */
class CallsSettingsTest {
    private val clock = LocalClock(6, 11 * 60)
    private val nov7 = CivilDate.toEpochDay(2026, 11, 7) * CivilDate.DAY_MS + 11 * 3_600_000L

    private fun card(id: String, state: BookingCallState, endedAt: Long?, cancelled: Boolean = false) = BookingCall(
        id = id, business = "Rosa's Kitchen", number = "+441234567890", what = "Table for 4", day = 0, fromMin = 19 * 60, toMin = 20 * 60,
        people = 4, note = null, state = state, askedAtMs = 0, approvedAtMs = null, result = null, calledAtMs = endedAt, endedAtMs = endedAt,
        cancelled = cancelled,
    )

    @Test
    fun thePageReadsTheMonthTheSwitchesAndTheChips() {
        val work = WorkModeState(
            atWork = false, source = WorkSource.SCHEDULE, schedule = WorkSchedule.DEFAULT, until = null, line = "Off work",
            callAssistant = true, callLive = true, callLiveCapGbp = 20,
            callLiveMeter = LiveCallRules.Meter("2026-11", 340, 14),
        )
        val calls = listOf(
            card("a", BookingCallState.BOOKED, nov7),
            card("b", BookingCallState.BOOKED, nov7 - 40L * CivilDate.DAY_MS), // last month
            card("c", BookingCallState.NOT_BOOKED, nov7),
            card("d", BookingCallState.BOOKED, nov7, cancelled = true),
        )
        val v = CallsSettingsRules.view(work, calls, nov7)
        assertEquals("cap £20", v.capLine)
        assertEquals(listOf("£3.40", "14", "1"), v.tiles.map { it.value })
        assertEquals(0.17f, v.fraction)
        assertFalse(v.atCap)
        assertEquals(listOf("£10", "£20", "£30", "£50"), v.caps.map { it.label })
        assertEquals(listOf(false, true, false, false), v.caps.map { it.on })
        assertEquals(listOf(true, true, true), v.toggles.map { it.on })
        assertNull(v.toggles[0].line) // THIS MONTH already says it
        assertEquals("On · £3.40 of £20 this month · 14 calls", v.entryLine)
        assertNull(v.toggles[1].line)
        assertNull(v.toggles[2].line)
        assertEquals(listOf("You're busy", "When you'll call back", "Your first name", "Where you work", "Your work email"), v.knows.map { it.label })
        assertEquals(listOf(true, true, true, false, false), v.knows.map { it.on })
        assertEquals(listOf("09:00–20:00", "5 min", "Businesses only", "Never"), v.limits.map { it.value })
        assertEquals(listOf("Don't keep", "7 days"), v.keeps.map { it.label })
        assertEquals(listOf(false, true), v.keeps.map { it.on })

        // A month gone: nothing spent; at the cap the bar is full.
        val dec = CallsSettingsRules.view(work, calls, nov7 + 30L * CivilDate.DAY_MS)
        assertEquals(listOf("£0", "0", "0"), dec.tiles.map { it.value })
        val full = CallsSettingsRules.view(work.copy(callLiveCapGbp = 10, callLiveMeter = LiveCallRules.Meter("2026-11", 1_200, 30)), calls, nov7)
        assertTrue(full.atCap)
        assertEquals(1f, full.fraction)
        assertEquals("At this month's £10 cap · callers hear the script until the 1st", full.toggles[0].line)
        assertEquals("Off · callers hear the short script and leave a message", CallsSettingsRules.view(work.copy(callLive = false), calls, nov7).toggles[0].line)

        // The slot switch says why it does nothing; Booking off says what happens.
        val noBack = CallsSettingsRules.view(work.copy(callKnows = setOf(LiveCallRules.Knows.NAME), callBooking = false), calls, nov7)
        assertEquals("Needs When you'll call back under What callers may know", noBack.toggles[1].line)
        assertEquals("Off · call cards wait and nothing rings", noBack.toggles[2].line)
        assertEquals("Only on live calls · turn Live conversation on", CallsSettingsRules.view(work.copy(callLive = false), calls, nov7).toggles[1].line)
    }

    @Test
    fun chipsToggleAndSlotsNeedBoth() {
        val k = CallsSettingsRules.toggledKnows(LiveCallRules.DEFAULT_KNOWS, "work")
        assertTrue(LiveCallRules.Knows.WORK in k)
        assertEquals(LiveCallRules.DEFAULT_KNOWS, CallsSettingsRules.toggledKnows(k, "work"))
        assertEquals(k, CallsSettingsRules.toggledKnows(k, "address")) // unknown: unchanged
        assertTrue(CallsSettingsRules.offersSlots(true, LiveCallRules.DEFAULT_KNOWS))
        assertFalse(CallsSettingsRules.offersSlots(false, LiveCallRules.DEFAULT_KNOWS))
        assertFalse(CallsSettingsRules.offersSlots(true, setOf(LiveCallRules.Knows.NAME)))
    }

    @Test
    fun theSettingsSyncBetweenTheFoldAndTheMac() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val onFold = WorkMode(fold.replica) { world.clock.nowMs }
        val onMac = WorkMode(mac.replica) { world.clock.nowMs }
        val first = onMac.state(clock)
        assertEquals(20, first.callLiveCapGbp)
        assertTrue(first.callSlots && first.callBooking)
        assertEquals(LiveCallRules.DEFAULT_KNOWS, first.callKnows)

        assertFalse(onFold.setCallLiveCapGbp(25)) // not a choice
        assertTrue(onFold.setCallLiveCapGbp(30))
        assertFalse(onFold.setCallLiveCapGbp(30))
        assertFalse(onFold.setCallKnows(LiveCallRules.DEFAULT_KNOWS)) // the defaults, never written
        assertTrue(onFold.setCallKnows(setOf(LiveCallRules.Knows.NAME, LiveCallRules.Knows.EMAIL)))
        assertTrue(onFold.setCallSlots(false))
        assertFalse(onFold.setCallSlots(false))
        assertTrue(onFold.setCallBooking(false))
        fold.sync(); mac.sync()
        val s = onMac.state(clock)
        assertEquals(30, s.callLiveCapGbp)
        assertEquals(setOf(LiveCallRules.Knows.NAME, LiveCallRules.Knows.EMAIL), s.callKnows)
        assertFalse(s.callSlots)
        assertFalse(s.callBooking)

        // Nothing allowed at all is kept as nothing, not read back as the defaults.
        world.clock.nowMs += 1_000
        assertTrue(onMac.setCallKnows(emptySet()))
        mac.sync(); fold.sync()
        assertEquals(emptySet(), onFold.state(clock).callKnows)
    }
}
