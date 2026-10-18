package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchEveningTest {
    private val evening = ShutdownView.EMPTY.copy(
        offered = true, evening = true, doneCount = 6, laterOffered = true,
        firstLine = "Tomorrow starts at 09:00 · Standup",
    )

    @Test
    fun theGlanceCountsTheDayAndOffersShutDownAndLater() {
        val v = WatchEveningRules.view(evening)!!
        assertEquals("DONE FOR THE DAY", v.label)
        assertTrue(v.lit)
        assertEquals("6 done · nothing left", v.line)
        assertEquals("Tomorrow starts at 09:00 · Standup", v.note)
        assertTrue(v.canShutDown && v.canLater)
        assertEquals("Done for the day. 6 done · nothing left. Tomorrow starts at 09:00 · Standup.", v.spoken)
        assertFalse(WatchEveningRules.view(evening.copy(laterOffered = false))!!.canLater)
        // No first commitment: the glance says what tomorrow holds.
        assertEquals(ShutdownRules.GLANCE_EMPTY, WatchEveningRules.view(evening.copy(firstLine = null))!!.note)
    }

    @Test
    fun theLineIsKindAndCountsWhatsLeft() {
        assertEquals("6 done · 2 left for tomorrow", WatchEveningRules.line(6, 2))
        assertEquals("Nothing ticked off · 2 left for tomorrow", WatchEveningRules.line(0, 2))
        assertEquals(ShutdownRules.NOTHING_DONE, WatchEveningRules.line(0, 0))
    }

    @Test
    fun nothingBeforeTheEveningWithTheCardOffOrOnLeaveAndQuietOnceShutDown() {
        assertNull(WatchEveningRules.view(evening.copy(evening = false)))
        assertNull(WatchEveningRules.view(evening.copy(offered = false)))
        assertNull(WatchEveningRules.view(evening.copy(cardOff = true)))
        assertNull(WatchEveningRules.view(evening.copy(onLeave = true)))
        val done = WatchEveningRules.view(evening.copy(offered = false, doneToday = true, doneLine = "Day shut down at 21:04"))!!
        assertEquals("DAY SHUT DOWN", done.label)
        assertFalse(done.lit || done.canShutDown || done.canLater)
        assertEquals("Day shut down at 21:04", done.note)
    }

    @Test
    fun shutDownTheDaySaidToTalkOpensTheShutdown() {
        listOf(
            "shut down the day", "shut down my day", "shutdown", "ok Navilife, shut down the day please", "close the day",
            "end my day", "wrap up the day", "call it a day", "I'm done for the day", "time to shut down", "let's shut down for the day",
            "done for today",
        ).forEach {
            assertEquals(TalkPlace.SHUTDOWN, TalkOpenRules.request(it, TalkOpenRules.FOLD), it)
            assertEquals(TalkPlace.SHUTDOWN, TalkOpenRules.request(it, TalkOpenRules.MAC), it)
        }
        listOf("shut down the laptop", "when does my day end", "wind down", "done", "end").forEach {
            assertNull(TalkOpenRules.request(it, TalkOpenRules.FOLD), it)
        }
        assertNull(TalkOpenRules.request("shut down the day", TalkOpenRules.WATCH))
        assertEquals(TalkPlace.SHUTDOWN, (TalkRules.reply("shut down the day", 0, TalkOpenRules.FOLD) as? TalkReply.Open)?.place)
        assertEquals(TalkOpenRules.OPENING_SHUTDOWN, TalkOpenRules.opening(TalkPlace.SHUTDOWN))
        assertEquals(TalkOpenRules.OPENED_SHUTDOWN, TalkOpenRules.remembered(TalkPlace.SHUTDOWN))
    }
}
