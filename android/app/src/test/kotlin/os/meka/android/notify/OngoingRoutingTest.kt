package os.meka.android.notify

import os.meka.android.shell.ShellDestination
import os.meka.core.domain.OngoingItem
import os.meka.core.domain.OngoingKind
import os.meka.core.domain.OngoingView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OngoingRoutingTest {
    private fun item(key: String, text: String = "Starts 14:00", short: String = "12 min") = OngoingItem(
        OngoingKind.MEETING, key, "Call", text, "Next event", 1_000L, true, null, true, null, null, short,
    )

    @Test
    fun idsAreStableAndNeverClashWithTheGovernors() {
        assertEquals(OngoingRouting.notificationId("fast-F1"), OngoingRouting.notificationId("fast-F1"))
        assertNotEquals(OngoingRouting.notificationId("fast-F1"), OngoingRouting.notificationId("meeting-c"))
        for (k in listOf("fast-F1", "meeting-c", "meeting-x")) {
            assertTrue(OngoingRouting.notificationId(k) and 0x40000000 != 0) // the governor's ids never set this bit
            assertTrue(OngoingRouting.notificationId(k) > 0)
            assertTrue(NotifyRouting.notificationId(k) and 0x40000000 == 0)
        }
    }

    @Test
    fun endFastReadsTheFastIdFromItsKeyOnly() {
        assertEquals("F1", OngoingRouting.fastId("fast-F1"))
        assertEquals(null, OngoingRouting.fastId("meeting-c"))
        assertEquals(null, OngoingRouting.fastId("fast-"))
        assertNotEquals(OngoingRouting.endedId, OngoingRouting.notificationId("fast-F1"))
    }

    @Test
    fun tappingOpensTodayForAnEventAndGoalsForTheFast() {
        assertEquals(ShellDestination.TODAY, OngoingRouting.destination(OngoingKind.MEETING))
        assertEquals(ShellDestination.GOALS, OngoingRouting.destination(OngoingKind.FAST))
    }

    @Test
    fun theMinuteByMinuteMenuBarWordsDoNotCauseARepost() {
        val a = OngoingView(listOf(item("meeting-c", short = "12 min")), "12 min", 5L)
        val b = OngoingView(listOf(item("meeting-c", short = "11 min")), "11 min", 5L)
        assertEquals(OngoingRouting.signature(a), OngoingRouting.signature(b))
        val c = OngoingView(listOf(item("meeting-c", text = "Started 14:00 · ends 15:00")), "Now", 5L)
        assertNotEquals(OngoingRouting.signature(a), OngoingRouting.signature(c))
    }
}
