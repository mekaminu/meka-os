package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackReminderRulesTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val thu = CivilDate.toEpochDay(2026, 10, 15)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val R = CallbackReminderRules

    private fun item(callbackAt: Long? = at(thu + 1, 17, 30), ended: String? = null) = CapturedItem(
        id = "h1", app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = "Sam", text = "About the boiler",
        conversation = null, atMs = at(thu, 10), callerNumber = "+447700900123", ended = ended,
        callback = callbackAt?.let { "call back Fri 16 Oct, 17:30" }, callbackAtMs = callbackAt,
    )

    @Test
    fun offeredOnlyForAnAgreedCallbackStillAhead() {
        val now = at(thu, 12)
        assertTrue(R.offers(item(), now))
        assertFalse(R.offers(item(callbackAt = null), now))
        assertFalse(R.offers(item(), at(thu + 1, 17, 30)))
        assertFalse(R.offers(item(ended = CallIntroRules.ENDED_SCAM), now))
        // Not a call assistant message: a WhatsApp voice note never gets it.
        assertFalse(R.offers(item().copy(app = CaptureApp.WHATSAPP), now))
    }

    @Test
    fun remindsTenMinutesBeforeOrAtTheTime() {
        val back = at(thu + 1, 17, 30)
        assertEquals(at(thu + 1, 17, 20), R.remindAtMs(back, at(thu, 12)))
        assertEquals(back, R.remindAtMs(back, at(thu + 1, 17, 25)))
        assertNull(R.remindAtMs(back, back))
    }

    @Test
    fun theTaskSaysWhoAndWhy() {
        assertEquals("callback-h1", R.taskId("h1"))
        assertEquals("Call back Sam", R.taskTitle(item()))
        val notes = R.taskNotes(item())
        assertTrue("They said: About the boiler" in notes, notes)
        assertTrue("+447700900123" in notes)
        assertEquals("Call back the caller", R.taskTitle(item().copy(personName = " ")))
    }

    @Test
    fun theChipSaysRemindMeThenWhenItIsSet() {
        val now = at(thu, 12)
        assertEquals(CallbackChip(R.REMIND_ME, done = false), R.chip(item(), null, now, cal))
        assertNull(R.chip(item(callbackAt = null), null, now, cal))
        assertEquals("Reminder set · Tomorrow · 17:20", R.setLine(at(thu + 1, 17, 20), now, cal))
        assertEquals("Reminder set · Today · 09:00", R.setLine(at(thu, 9), at(thu, 8), cal))
    }
}
