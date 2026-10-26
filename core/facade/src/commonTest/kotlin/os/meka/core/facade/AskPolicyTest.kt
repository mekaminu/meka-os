package os.meka.core.facade

import os.meka.core.domain.AskContext
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskRawAction
import os.meka.core.domain.AskRules
import os.meka.core.domain.BookingEntry
import os.meka.core.domain.BookingKind
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.ReadingChange
import os.meka.core.domain.TrustKind
import os.meka.core.domain.TrustRules
import os.meka.core.policy.ActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The companion, slice 6: the policy gate on every action a model can propose (ADR-006). Whatever Ask, Talk, Hello
 * or open conversation propose, each kind is a card that waits for Meka's tap, none can send, pay, trade or share,
 * and only the four earned-trust kinds can ever run on their own: under his rule, from trusted lines, not paused.
 */
class AskPolicyTest {
    /** One of every proposal kind. [kindName] is exhaustive, so a new kind fails to compile until it is listed here. */
    private val every: List<AskProposal> = listOf(
        AskProposal.AddTask("Call the dentist", null, null),
        AskProposal.CompleteTask("task-1", "Book MOT"),
        AskProposal.MoveTask("task-1", "Book MOT", 20_000, 9 * 60),
        AskProposal.MoveEvent("event-1", "Standup", 20_000, 10 * 60, "google"),
        AskProposal.StartFast(36),
        AskProposal.Timer(20),
        AskProposal.Alarm(6 * 60 + 30),
        AskProposal.AddShopping(listOf("milk", "eggs")),
        AskProposal.PlanDinner(20_000, "Chilli", null),
        AskProposal.SkipDateNight(20_000),
        AskProposal.SkipLifeAdmin(19_998, 20_003, "Sat 10:00–11:00"),
        AskProposal.AddGiftIdea("occ-1", "Alex's birthday", "silk scarf"),
        AskProposal.AddPromise("Alex", "fix his bike", null),
        AskProposal.AddBooking(BookingEntry("trip-1", BookingKind.entries.first(), "Flight BA478", 20_010, null, 7 * 60 + 40, null), "Spain"),
        AskProposal.AddTripItems("trip-1", "Spain trip", listOf(os.meka.core.domain.TripListEntry(os.meka.core.domain.TripList.BUY, "Sun cream", "Pharmacy", null, os.meka.core.domain.TripWho.EVERYONE))),
        AskProposal.RenewalDone("ren-1", "Smoke alarms", "Done", 20_000, 20_090),
        AskProposal.AddWin("Closed the Oakfield deal"),
        AskProposal.QueueDone("q-1", "The BBC one"),
        AskProposal.BookLeave("Mon 2 Nov", 20_030, 20_030, 1),
        AskProposal.AddFact("Shoe size", "10", null),
        AskProposal.AddErrand("Tesco", listOf("batteries")),
        AskProposal.AddChecklist("task-2", "Party", listOf("balloons")),
        AskProposal.AddGoal("Run 5k", GoalHorizon.MONTH, null),
        AskProposal.ReadingUpdate(ReadingChange.ADD, null, "Dune", "Dune", "Dune"),
        AskProposal.SetName("Alex"),
    )

    private fun kindName(p: AskProposal): String = when (p) {
        is AskProposal.AddTask -> "add_task"
        is AskProposal.CompleteTask -> "complete_task"
        is AskProposal.MoveTask -> "move_task"
        is AskProposal.MoveEvent -> "move_event"
        is AskProposal.StartFast -> "start_fast"
        is AskProposal.Timer -> "set_timer"
        is AskProposal.Alarm -> "set_alarm"
        is AskProposal.AddShopping -> "add_shopping"
        is AskProposal.PlanDinner -> "plan_dinner"
        is AskProposal.SkipDateNight -> "skip_date_night"
        is AskProposal.SkipLifeAdmin -> "skip_life_admin"
        is AskProposal.AddGiftIdea -> "add_gift_idea"
        is AskProposal.AddPromise -> "add_promise"
        is AskProposal.AddBooking -> "add_booking"
        is AskProposal.AddTripItems -> "add_trip_item"
        is AskProposal.RenewalDone -> "done_renewal"
        is AskProposal.AddWin -> "add_win"
        is AskProposal.QueueDone -> "queue_done"
        is AskProposal.BookLeave -> "book_leave"
        is AskProposal.AddFact -> "add_fact"
        is AskProposal.AddErrand -> "add_errand"
        is AskProposal.AddChecklist -> "add_checklist"
        is AskProposal.AddGoal -> "add_goal"
        is AskProposal.ReadingUpdate -> "reading"
        is AskProposal.SetName -> "name"
    }

    @Test
    fun everyKindIsListedAndIsACardThatWaitsForHisTap() {
        // One sample per kind, and every model-proposable wire kind is among them.
        val names = every.map(::kindName)
        assertEquals(names.size, names.toSet().size, names.toString())
        assertEquals(25, names.size)
        assertTrue(names.containsAll(AskRules.KINDS), (AskRules.KINDS - names.toSet()).toString())
        for (p in every) {
            // Shown as a card from his own lines and from lines with other people's words alike; never run untapped.
            assertTrue(AskPolicy.allowed(p, untrusted = false), kindName(p))
            assertTrue(AskPolicy.allowed(p, untrusted = true), kindName(p))
            // Nothing a model proposes is a message, an email, a payment, a trade or a share.
            assertFalse(AskPolicy.action(p).first in AskPolicy.NEVER, kindName(p))
        }
        // Only moving his own calendar event touches the outside world, and that is always a tap.
        assertEquals(listOf("move_event"), every.filter { AskPolicy.action(it).first.external }.map(::kindName))
    }

    @Test
    fun onlyEarnedTrustKindsRunOnTheirOwnAndNeverFromOtherPeoplesWordsOrWhenPaused() {
        for (p in every) {
            val kind = TrustRules.kindOf(p)
            for (k in TrustKind.entries) {
                val mine = AskPolicy.auto(p, k, untrusted = false, paused = false)
                // Only under the rule for its own kind.
                assertEquals(kind == k, mine, "${kindName(p)} under ${k.key}")
                // Never from a question that carried untrusted lines, never while "What Navilife may do" is paused.
                assertFalse(AskPolicy.auto(p, k, untrusted = true, paused = false), kindName(p))
                assertFalse(AskPolicy.auto(p, k, untrusted = false, paused = true), kindName(p))
            }
        }
        assertEquals(
            setOf("add_task", "complete_task", "add_shopping", "add_win"),
            every.filter { p -> TrustKind.entries.any { AskPolicy.auto(p, it, untrusted = false, paused = false) } }.map(::kindName).toSet(),
        )
        // A move of his calendar event, a fast, an alarm or a fact can never be earned trust.
        assertNull(TrustRules.kindOf(every.first { it is AskProposal.MoveEvent }))
    }

    @Test
    fun aKindThatWouldSendPayTradeOrShareIsNeverACard() {
        // The model can name anything; only MEKA's own kinds become cards, so "send", "pay" and "trade" come to nothing.
        val ctx = AskContext.EMPTY
        for (kind in listOf("send_message", "send_email", "reply_email", "pay", "transfer", "trade", "buy", "book_table", "share_document", "archive_email", "delete_email", "call")) {
            assertFalse(kind in AskRules.KINDS, kind)
            assertNull(AskRules.card(AskRawAction(kind, title = "Alex: on my way"), ctx, emptyMap(), 20_000L * 86_400_000L, LocalCalendar.UTC), kind)
        }
        // And the gate refuses those types outright, whatever a future mapping might say.
        assertTrue(
            AskPolicy.NEVER.containsAll(
                listOf(ActionType.SEND_EMAIL, ActionType.SEND_PERSONAL_MESSAGE, ActionType.FINANCIAL_TRANSACTION, ActionType.TRADING_APPROVAL, ActionType.SHARE_DOCUMENT),
            ),
        )
    }
}
