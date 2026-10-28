package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.MoneyChat
import os.meka.core.domain.MoneyRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Money redesign, Ask Obi (slice 1), through the facade: the money chat answers only while Money is open, on the device. */
class MoneyChatFacadeTest {
    private var now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val statement = "Date,Description,Amount\n" +
        "10/09/2026,TESCO STORES 2041,-60.00\n" +
        "02/10/2026,TESCO STORES 2041,-40.00\n" +
        "03/10/2026,COSTA COFFEE,-3.20\n" +
        "05/10/2026,SALARY OAKFIELD LTD,2500.00\n"

    @Test
    fun theChatAnswersWhileMoneyIsOpenAndMakesAGoal() = runTest(timeout = 5.minutes) {
        val fold = core("android")
        val mac = core("mac")
        // Locked: refused, nothing worked out.
        val locked = fold.moneyChat("What if I save £300 a month for 6 months?")
        assertTrue(locked.refused)
        assertEquals(MoneyRules.LOCKED, locked.text)
        assertTrue(fold.moneyChatSuggestions().isEmpty())

        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        val s = fold.moneyChatSuggestions()
        assertTrue(s.size >= 3, s.toString())
        assertEquals("Can I afford £300 on Saturday?", s[1])

        val t = fold.moneyChat("What if I save £300 a month for 6 months?")
        assertFalse(t.refused)
        assertTrue(t.text.startsWith("Saving £300 a month for 6 months puts by £1,800 by Apr 2027."), t.text)
        val goal = t.actions.single { it.kind == MoneyChat.ACT_GOAL }
        val made = fold.moneyChatGoal(goal.amountPence, goal.months)
        assertFalse(made.refused)
        assertTrue(made.line.startsWith("Made a goal: Save £1,800 by Apr 2027"), made.line)
        assertTrue(fold.goalsView.value.goals.any { it.title == "Save £1,800 by Apr 2027" })

        // The goal syncs to the Mac; Undo takes it away on both.
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertTrue(mac.goalsView.value.goals.any { it.title == "Save £1,800 by Apr 2027" })
        assertTrue(fold.undoMoneyCategory(made.undo!!))
        assertFalse(fold.goalsView.value.goals.any { it.title == "Save £1,800 by Apr 2027" })

        // Ask's own money rules still answer in the chat.
        val spent = fold.moneyChat("how much have I spent on coffee this month?")
        assertTrue(spent.text.startsWith("You spent £3.20 on coffee this month so far, 1 payment."), spent.text)

        // Closed again: locked, and Make it a goal is refused.
        fold.closeMoney()
        assertTrue(fold.moneyChat("when's payday?").refused)
        assertTrue(fold.moneyChatGoal(30_000, 6).refused)
    }
}
