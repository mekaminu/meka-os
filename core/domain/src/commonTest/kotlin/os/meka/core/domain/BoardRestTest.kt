package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, item (11) part 2: "I'm shattered" to Obi, and Stretched holding the alerts that can wait for a day. */
class BoardRestTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 21:30 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 21 * 3_600_000L + 30 * 60_000L
    private val thu = today + 1

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L
    private fun task(id: String, scheduled: Long) =
        Task(id, id, null, Lifecycle.ACTIVE, null, scheduled, null, 0, null, null, 0, null, false)

    @Test
    fun saysHowHeFeelsOnlyAsAWholeRemark() {
        listOf("I'm shattered", "im knackered", "Hey Obi, I'm absolutely exhausted today", "feeling so drained", "It's been a long day")
            .forEach { assertEquals(Feeling.TIRED, FeelingAskRules.request(it), it) }
        listOf("I'm swamped", "I've got too much on", "I'm really stressed at the moment", "it's all too much", "I'm stretched thin")
            .forEach { assertEquals(Feeling.STRETCHED, FeelingAskRules.request(it), it) }
        listOf("I'm tired of waiting for the bus", "why am I always tired?", "add tired to the list", "what's on tomorrow", "")
            .forEach { assertNull(FeelingAskRules.request(it), it) }
    }

    @Test
    fun stretchedOffersTheHoldUnlessOneIsOn() {
        val board = WeekBoardRules.build(listOf(task("gym", at(thu, 20))), emptyList(), now, cal)
        val o = FeelingRules.offer(board, Feeling.STRETCHED, today, cal, now)
        assertEquals("Hold alerts till tomorrow 21:30", o.holdLabel)
        assertEquals("I can hold mail and call alerts that can wait till tomorrow 21:30.", o.holdLine)
        assertNull(FeelingRules.offer(board, Feeling.STRETCHED, today, cal, now, holding = true).holdLabel)
        assertNull(FeelingRules.offer(board, Feeling.TIRED, today, cal, now).holdLabel)
        // Without the time (part 1's callers) nothing changes.
        assertNull(FeelingRules.offer(board, Feeling.STRETCHED, today, cal).holdLabel)
        val said = FeelingAskRules.answer(o)
        assertTrue(said.startsWith("That's a lot to carry. Let's take some off tomorrow: move “Gym” to "), said)
        assertTrue(said.endsWith("Lighten tomorrow and holding alerts are in Shut down the day, nothing moves till you tap."), said)
        val tired = FeelingAskRules.answer(FeelingRules.offer(WeekBoardRules.build(emptyList(), emptyList(), now, cal), Feeling.TIRED, today, cal, now))
        assertEquals("Sorry to hear it. Tomorrow is light already. Rest well.", tired)
    }

    private fun notice(key: String, source: NoticeSource, atMs: Long = now) =
        Notice(key, source, NoticeTier.HEADS_UP, key, "", atMs, NoticeTarget.TODAY)

    @Test
    fun theHoldKeepsMailAndCallAlertsBackForADayAndNothingElse() {
        val settings = NotificationSettings(quiet = QuietHours(false, 0, 0), digestMinutes = emptyList())
        val ns = listOf(
            notice("inbox", NoticeSource.MAIL_MOMENT), notice("parcel", NoticeSource.PARCELS),
            notice("credit", NoticeSource.CALL_CREDIT), notice("event", NoticeSource.EVENT_REMINDER),
            notice("train", NoticeSource.TRAINS),
        )
        val until = AlertHoldRules.untilFrom(now)
        val held = Governor.evaluate(ns, settings, DeviceAlerts.ALL, GovernorState(), now, cal, holdUntilMs = until)
        assertEquals(listOf("event", "train"), held.post.map { it.key })
        // It wakes when the hold ends, and what still applies then posts.
        assertEquals(until, held.nextWakeMs)
        val after = Governor.evaluate(ns, settings, DeviceAlerts.ALL, held.state, until + 1, cal, holdUntilMs = until)
        assertEquals(listOf("inbox", "parcel", "credit"), after.post.map { it.key })
        // No hold: everything as before.
        assertEquals(3, Governor.evaluate(ns.take(3), settings, DeviceAlerts.ALL, GovernorState(), now, cal).post.size)
    }

    @Test
    fun theHoldsLinesAndItsStore() {
        val until = AlertHoldRules.untilFrom(now)
        assertTrue(AlertHoldRules.active(until, now))
        assertFalse(AlertHoldRules.active(until, until))
        assertFalse(AlertHoldRules.active(null, now))
        assertEquals("Mail and call alerts held till tomorrow 21:30 · urgent calls and Always notify still come through", AlertHoldRules.heldLine(until, now, cal))
        assertEquals("21:30", AlertHoldRules.whenLine(until, until - 60_000L, cal))
        assertEquals(until, AlertHoldRules.decode(AlertHoldRules.encode(until)))
        assertNull(AlertHoldRules.decode(AlertHoldRules.encode(null)))
        assertNull(AlertHoldRules.decode("junk"))
    }
}
