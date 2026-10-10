package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant's low-balance guard (Meka, 2026-10-08: Twilio's auto-recharge is off by choice). */
class CallCreditTest {
    private val t = 1_791_540_000_000L
    private val hour = 60 * 60_000L
    private fun reading(balance: String?, active: Boolean? = true, currency: String? = "GBP") = CallCreditReading(active, balance, currency)

    @Test
    fun amountsAreReadAsPence() {
        assertEquals(420L, CallCreditRules.pence("4.20"))
        assertEquals(420L, CallCreditRules.pence(" 4.2 "))
        assertEquals(500L, CallCreditRules.pence("5"))
        assertEquals(1908L, CallCreditRules.pence("19.0812"))
        assertEquals(-12L, CallCreditRules.pence("-0.125"))
        assertNull(CallCreditRules.pence(null))
        assertNull(CallCreditRules.pence("£4.20"))
        assertNull(CallCreditRules.pence("4,20"))
        assertNull(CallCreditRules.pence(""))
        assertEquals("£4.20", CallCreditRules.money(420, "GBP"))
        assertEquals("−£0.12", CallCreditRules.money(-12, null))
        assertEquals("$3.05", CallCreditRules.money(305, "USD"))
        assertEquals("4.20 CHF", CallCreditRules.money(420, "CHF"))
    }

    @Test
    fun theBalanceSetsTheStateAndAChangeRestartsItsTime() {
        val ok = CallCreditRules.assess(reading("19.08"), null, t, null)!!
        assertEquals(CallCredit(CallCreditState.OK, 1908, "GBP", t), ok)
        // Still fine later: the state's time stays.
        assertEquals(t, CallCreditRules.assess(reading("12.00"), ok, t + hour, null)!!.sinceMs)
        val low = CallCreditRules.assess(reading("4.99"), ok, t + 2 * hour, null)!!
        assertEquals(CallCreditState.LOW, low.state)
        assertEquals(t + 2 * hour, low.sinceMs)
        assertEquals(CallCreditState.VERY_LOW, CallCreditRules.assess(reading("1.99"), low, t, null)!!.state)
        assertEquals(CallCreditState.VERY_LOW, CallCreditRules.assess(reading("0.50"), low, t, null)!!.state)
        val empty = CallCreditRules.assess(reading("0.49"), low, t, null)!!
        assertTrue(empty.paused)
        assertEquals(CallCreditPause.EMPTY, empty.reason)
        assertEquals(CallCreditState.PAUSED, CallCreditRules.assess(reading("-1.20"), low, t, null)!!.state)
        // Topped up: back to fine.
        assertEquals(CallCreditState.OK, CallCreditRules.assess(reading("25.00"), empty, t, null)!!.state)
    }

    @Test
    fun aSuspendedAccountPausesAndAnUnreadableBalancePausesOnlyAfterADay() {
        val ok = CallCredit(CallCreditState.OK, 1908, "GBP", t)
        val suspended = CallCreditRules.assess(reading(null, active = false), ok, t, t)!!
        assertEquals(CallCreditState.PAUSED, suspended.state)
        assertEquals(CallCreditPause.SUSPENDED, suspended.reason)
        assertEquals(CallCreditPause.SUSPENDED, CallCreditRules.assess(reading("20.00", active = false), ok, t, null)!!.reason)
        // A failed read keeps what the devices have, for a day.
        assertNull(CallCreditRules.assess(null, ok, t + 23 * hour, t))
        assertNull(CallCreditRules.assess(reading("n/a"), ok, t + hour, t))
        assertNull(CallCreditRules.assess(null, ok, t, null))
        val unreadable = CallCreditRules.assess(null, ok, t + 24 * hour, t)!!
        assertTrue(unreadable.paused)
        assertEquals(CallCreditPause.UNREADABLE, unreadable.reason)
        assertNull(unreadable.pence)
        assertEquals("GBP", unreadable.currency)
    }

    @Test
    fun needsYouGetsACardWhileTheAssistantIsOn() {
        assertNull(CallCreditRules.card(CallCredit(CallCreditState.OK, 1908, "GBP", t), true))
        assertNull(CallCreditRules.card(null, true))
        val lowCredit = CallCredit(CallCreditState.LOW, 420, "GBP", t)
        assertNull(CallCreditRules.card(lowCredit, assistantOn = false))
        val low = CallCreditRules.card(lowCredit, true)!!
        assertEquals("Call assistant credit low", low.title)
        assertEquals("£4.20 left · top up so callers can leave a message", low.why)
        assertEquals("Top up", low.yesLabel)
        assertEquals(DecisionEffect.OPEN_LINK, low.yes)
        assertEquals(DecisionEffect.SET_ASIDE, low.later)
        assertEquals(DecisionEffect.OPEN_LINK, low.open)
        assertEquals(CallCreditRules.TOP_UP_URL, low.link)
        assertEquals(DecisionKind.CREDIT, low.kind)
        assertFalse(low.urgent)
        assertTrue(CallCreditRules.card(CallCredit(CallCreditState.VERY_LOW, 180, "GBP", t), true)!!.urgent)
        val paused = CallCreditRules.card(CallCredit(CallCreditState.PAUSED, 12, "GBP", t, CallCreditPause.EMPTY), true)!!
        assertEquals("Call assistant paused · Twilio credit", paused.title)
        assertEquals("Out of credit · £0.12 left · calls ring as usual at work until it's sorted", paused.why)
        assertTrue(paused.urgent)
        assertEquals(
            "Twilio has suspended the account · calls ring as usual at work until it's sorted",
            CallCreditRules.card(CallCredit(CallCreditState.PAUSED, null, "GBP", t, CallCreditPause.SUSPENDED), true)!!.why,
        )
        assertEquals("Call assistant paused · Twilio credit", NeedsYouStackRules.message(paused, DecisionMove.YES))
    }

    @Test
    fun aLitCardLeadsTheStackAndALowOneFollowsTheTasks() {
        val world = SyncWorld()
        val fold = world.device("android")
        val day = CivilDate.toEpochDay(2026, 10, 7)
        world.clock.nowMs = day * CivilDate.DAY_MS + 10 * hour
        fold.tasks.create(NewTask("Book MOT", dueAtMs = (day - 2) * CivilDate.DAY_MS))
        val today = TodayProjection.project(fold.tasks.all(), world.clock.nowMs, DayWindow(day * CivilDate.DAY_MS, (day + 1) * CivilDate.DAY_MS))
        val now = world.clock.nowMs
        val cal = LocalCalendar.UTC
        val low = CallCreditRules.card(CallCredit(CallCreditState.LOW, 420, "GBP", t), true)!!
        val lit = CallCreditRules.card(CallCredit(CallCreditState.VERY_LOW, 120, "GBP", t), true)!!
        assertEquals(
            listOf("Book MOT", "Call assistant credit low", "From your lists"),
            NeedsYouStackRules.build(today, "1 renewal due", now, cal, low).cards.map { it.title },
        )
        assertEquals(
            listOf("Call assistant credit low", "Book MOT", "From your lists"),
            NeedsYouStackRules.build(today, "1 renewal due", now, cal, lit).cards.map { it.title },
        )
        assertEquals(listOf("Book MOT"), NeedsYouStackRules.build(today, null, now, cal).cards.map { it.title })
    }

    @Test
    fun aHeadsUpPostsOnceWhenItDropsUnderTwoPoundsAndWhenItPauses() {
        assertEquals(emptyList(), CallCreditRules.notices(CallCredit(CallCreditState.LOW, 420, "GBP", t), true))
        val veryLow = CallCreditRules.notices(CallCredit(CallCreditState.VERY_LOW, 180, "GBP", t), true).single()
        assertEquals("callcredit:VERY_LOW:$t", veryLow.key)
        assertEquals(NoticeSource.CALL_CREDIT, veryLow.source)
        assertEquals(NoticeTier.HEADS_UP, veryLow.tier)
        assertEquals("Call assistant credit low", veryLow.title)
        assertEquals("£1.80 left · top up in Twilio", veryLow.text)
        assertEquals(NoticeTarget.NEEDS_YOU, veryLow.target)
        val paused = CallCreditRules.notices(CallCredit(CallCreditState.PAUSED, null, "GBP", t + hour, CallCreditPause.UNREADABLE), true).single()
        assertEquals("Call assistant paused", paused.title)
        assertEquals("MEKA couldn't read the Twilio balance for a day · calls ring as usual at work", paused.text)
        assertEquals(emptyList(), CallCreditRules.notices(CallCredit(CallCreditState.VERY_LOW, 180, "GBP", t), assistantOn = false))
        assertEquals("Call assistant credit", NoticeSource.CALL_CREDIT.label)
    }

    @Test
    fun pausedScreeningLetsCallsRingButStillBlocks() {
        val lists = PeopleLists()
        val stranger = CallScreeningRules.decide(true, true, "07700 900999", lists, emptyList(), t, paused = true)
        assertTrue(stranger.rings)
        assertEquals(CallReason.PAUSED, stranger.reason)
        // Unpaused, the same call goes to the assistant.
        assertEquals(CallVerdict.DECLINE, CallScreeningRules.decide(true, true, "07700 900999", lists, emptyList(), t).verdict)
        val blocked = CallScreeningRules.decide(
            true, true, "07700 900999", lists, emptyList(), t, blocked = setOf(CallScreeningRules.callerKey("07700 900999")), paused = true,
        )
        assertEquals(CallVerdict.BLOCK, blocked.verdict)
        assertEquals(
            "Paused · Twilio credit · calls ring as usual until it's topped up",
            CallScreeningRules.statusLine(switchedOn = true, atWork = true, screeningAllowed = true, paused = true),
        )
        assertEquals("Off · calls ring as usual", CallScreeningRules.statusLine(false, true, true, paused = true))
    }

    @Test
    fun healthSaysTheCreditWhenItNeedsALook() {
        assertNull(CallCreditRules.healthLine(null))
        assertNull(CallCreditRules.healthLine(CallCredit(CallCreditState.OK, 1908, "GBP", t)))
        assertEquals("On · £4.20 left · top up soon" to HealthState.WARN, CallCreditRules.healthLine(CallCredit(CallCreditState.LOW, 420, "GBP", t)))
        assertEquals(
            "Paused · Out of credit · £0.12 left · calls ring as usual" to HealthState.BAD,
            CallCreditRules.healthLine(CallCredit(CallCreditState.PAUSED, 12, "GBP", t, CallCreditPause.EMPTY)),
        )
    }

    @Test
    fun theServersEntityReachesEveryDevice() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val credit = CallCredit(CallCreditState.PAUSED, 12, "GBP", t, CallCreditPause.EMPTY)
        // What the server writes; written on a device replica here to stand in for it.
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, CallCreditRules.ENTITY_ID, CallCreditRules.fields(credit))
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals(credit, CallCreditStore(m.replica).current())
        assertNull(CallCreditStore(world.device("other").replica).current())
        val back = CallCreditRules.assess(CallCreditReading(true, "20.00", "GBP"), credit, t + hour, null)!!
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, CallCreditRules.ENTITY_ID, CallCreditRules.fields(back))
        a.syncWithRetry(); m.syncWithRetry()
        assertNotNull(CallCreditStore(m.replica).current()).let { assertFalse(it.paused); assertNull(it.reason) }
    }
}
