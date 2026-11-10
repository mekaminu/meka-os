package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.BoardArea
import os.meka.core.domain.DayListRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * The Day list crash (Meka 2026-11-10 06:34): Android's regex engine is ICU, not the JVM's, and it read DayList's
 * `[:.]` with a later `:]` as a POSIX class, so opening the Day list or "+ Add" for today threw
 * PatternSyntaxException on the Fold. Robolectric runs the JVM's engine and never saw it; this runs on the emulator
 * (the motion workflow), where every DayList regex compiles with ICU: the typed lines parse, and the Day list opens
 * folded and open and adds "Call Sam 14:30 30m" at 14:30 for 30 min. Invented data.
 */
class DayListAddOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_570_200_000L // Wed 21 Oct 2026, 09:10 in London

    @Test
    fun theTypedLinesParseWithTheDevicesRegexEngine() {
        val call = DayListRules.parse("Call Sam 14:30 30m")
        assertNotNull("\"Call Sam 14:30 30m\" didn't parse", call)
        assertEquals("Call Sam", call!!.title)
        assertEquals(14 * 60 + 30, call.startMinute)
        assertEquals(30, call.minutes)

        val dot = DayListRules.parse("Clear the gutters 9.15 1h30 #house")!!
        assertEquals(9 * 60 + 15, dot.startMinute)
        assertEquals(90, dot.minutes)
        assertEquals(BoardArea.HOUSE, dot.area)

        val list = DayListRules.pasted("- Book the MOT 15m\n2) Pay the nursery #money\n[x] Email Alex 10:00")
        assertEquals(listOf("Book the MOT", "Pay the nursery", "Email Alex"), list.map { it.title })
        assertEquals(10 * 60, list.last().startMinute)
    }

    @Test
    fun opensFoldedAndAddsCallSam() = opensAndAdds(344.dp)

    @Test
    fun opensOpenAndAddsCallSam() = opensAndAdds(720.dp)

    private fun opensAndAdds(width: Dp) {
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(31), timeZone = { london }, nowMs = { now },
        )
        val today = runBlocking {
            core.tick()
            core.addToDayList("Complete feedbacks 30m #work", core.todayEpochDay())
            core.tick()
            core.todayEpochDay()
        }
        // The clock is driven by hand (as TaskTitleAutosaveTest does): the pane's endless motion never lets it idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.requiredWidth(width).fillMaxHeight()) { DayListPane(core, onClose = {}) }
            }
        }
        until { shows("Complete feedbacks") }
        assertTrue("the Day list didn't open", shows("Complete feedbacks"))

        compose.onNode(hasSetTextAction()).performTextInput("Call Sam 14:30 30m")
        compose.onNode(hasSetTextAction()).performImeAction()

        fun added() = core.weekBoard.value.day(today)?.cards?.firstOrNull { it.title == "Call Sam" }
        until { added() != null && shows("Call Sam") }
        assertNotNull("\"Call Sam 14:30 30m\" wasn't added", added())
        assertTrue("the new row isn't shown", shows("Call Sam"))
        val task = added()!!.task
        assertEquals(30, task.estimateMinutes)
        // 14:30 the same day: now is 09:10, so 5 h 20 min on.
        assertEquals(now + (14 * 60 + 30 - (9 * 60 + 10)) * 60_000L, task.scheduledAtMs)
    }

    /** Up to 10 s: frames on the test's clock while the core's coroutines run on their own threads. */
    private fun until(what: () -> Boolean) {
        repeat(100) {
            if (what()) return
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
        }
    }

    private fun shows(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}
