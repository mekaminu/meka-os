package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notifications never seen, slice 4: Ask and Talk answer "why didn't I get a notification?" from the trace. */
class NotifyAskTest {
    private val cal = LocalCalendar.UTC
    // Mon 12 Oct 2026 20:41 UTC.
    private val day = CivilDate.toEpochDay(2026, 10, 12)
    private val now = day * CivilDate.DAY_MS + (20 * 60 + 41) * HealthRules.MIN
    private val settings = NotificationSettings.DEFAULT
    private val healthy = NotificationHealth(
        allowed = true,
        channels = listOf(
            NotifyChannelState("meka_heads_up", "Heads-ups", true, now - 30 * HealthRules.MIN),
            NotifyChannelState("meka_digest", "Digests", true),
        ),
        trace = listOf(
            NotifyTraceEntry(now - 10 * HealthRules.MIN, NotifyTraceKind.LOOKED, "nothing due · next look 21:10"),
            NotifyTraceEntry(now - 30 * HealthRules.MIN, NotifyTraceKind.POSTED, "1 notice on Heads-ups"),
        ),
    )

    @Test
    fun onlyNotificationQuestionsAreAboutNotifications() {
        listOf("Why didn't I get a notification?", "are my notifs on", "I wasn't notified", "no heads-up about the bins?", "when's the next digest?", "are alerts working")
            .forEach { assertTrue(NotifyAskRules.isAbout(it), it) }
        listOf("What's on today?", "when are the bins?", "add milk").forEach { assertFalse(NotifyAskRules.isAbout(it), it) }
    }

    @Test
    fun theLinesSayWhatStandsAndWhatHappened() {
        val lines = NotifyAskRules.askLines(healthy, settings, now, cal)
        assertEquals("Notifications on this phone · on for Navilife", lines[0])
        assertEquals("Last notification · sent today at 20:11 on Heads-ups", lines[1])
        assertEquals("Digests · Most things wait for the digests at 12:30 and 18:00, so quiet in between is normal. Decisions and urgent things come at once.", lines[2])
        assertEquals("Quiet hours · 22:00–07:00", lines[3])
        assertEquals("Trace · ${CivilDate.shortLabel(day)} 20:31 · Looked · nothing due · next look 21:10", lines[4])
        assertEquals(6, lines.size)

        val off = NotifyAskRules.askLines(healthy.copy(channels = healthy.channels.map { it.copy(on = it.id != "meka_digest") }), settings, now, cal)
        assertEquals("Categories off in Android's settings: Digests", off[1])
        val blocked = NotifyAskRules.askLines(healthy.copy(allowed = false), settings, now, cal)
        assertTrue(blocked[0].startsWith("Notifications on this phone · off for Navilife"))
        // At night it says it's quiet now.
        val night = day * CivilDate.DAY_MS + 23 * HealthRules.HOUR
        assertEquals("Quiet hours · 22:00–07:00 · quiet now until 07:00 (only urgent things come)", NotifyAskRules.askLines(healthy, settings, night, cal)[3])
        // The trace is cut: at most ASK_TRACE entries.
        val long = healthy.copy(trace = List(20) { NotifyTraceEntry(now - it * HealthRules.MIN, NotifyTraceKind.LOOKED, "nothing due $it") })
        assertEquals(NotifyAskRules.ASK_TRACE, NotifyAskRules.askLines(long, settings, now, cal).count { it.startsWith("Trace · ") })
    }

    @Test
    fun aDeviceWithNoTraceSaysSoAndKeepsTheSettings() {
        val lines = NotifyAskRules.askLines(null, settings, now, cal)
        assertTrue(lines[0].startsWith("Notifications · this device keeps no notification trace"))
        assertTrue(lines.none { it.startsWith("Trace · ") })
        assertNull(NotifyAskRules.localAnswer(null, settings, now, cal))
    }

    @Test
    fun withoutTheAiThePhoneAnswersItself() {
        assertTrue(NotifyAskRules.localAnswer(healthy.copy(allowed = false), settings, now, cal)!!.startsWith("Notifications are off for Navilife on this phone"))
        assertTrue(NotifyAskRules.localAnswer(healthy.copy(phoneOff = true), settings, now, cal)!!.startsWith("This phone is set to Off"))
        assertTrue(NotifyAskRules.localAnswer(healthy.copy(channels = healthy.channels.map { it.copy(on = false) }), settings, now, cal)!!.startsWith("Every Navilife category is switched off"))
        assertEquals(
            "The last one went today at 20:11, on Heads-ups. Most things wait for the digests at 12:30 and 18:00, so quiet in between is normal. Decisions and urgent things come at once.",
            NotifyAskRules.localAnswer(healthy, settings, now, cal),
        )
        val failed = healthy.copy(trace = listOf(NotifyTraceEntry(now - 5 * HealthRules.MIN, NotifyTraceKind.FAILED, "governor · SecurityException")) + healthy.trace)
        assertTrue(NotifyAskRules.localAnswer(failed, settings, now, cal)!!.startsWith("The last try to post failed today at 20:36 (governor · SecurityException)."))
        val never = NotificationHealth(allowed = true, channels = emptyList(), trace = emptyList())
        val night = day * CivilDate.DAY_MS + 23 * HealthRules.HOUR
        assertEquals(
            "Navilife hasn't sent a notification from this phone yet. It's quiet hours until 07:00, so only urgent things come now. Most things wait for the digests at 12:30 and 18:00, so quiet in between is normal. Decisions and urgent things come at once.",
            NotifyAskRules.localAnswer(never, settings, night, cal),
        )
    }
}
