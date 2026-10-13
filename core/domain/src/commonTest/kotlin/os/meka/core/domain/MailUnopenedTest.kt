package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailUnopenedTest {
    private val cal = LocalCalendar.UTC
    private val day = 86_400_000L
    private val saturday = CivilDate.toEpochDay(2026, 10, 17)
    private fun at(epochDay: Long, h: Int, m: Int = 0) = cal.toEpochMs(epochDay, h * 60 + m)
    private val satMorning = at(saturday, 9, 30)

    private fun s(
        id: String, unopened: Int = 5, method: UnsubscribeMethod = UnsubscribeMethod.ONE_CLICK, countedAt: Long? = satMorning - 3_600_000,
        lastAt: Long = satMorning - day, asked: Long? = null, tried: Long? = null, state: UnsubscribeState? = null, kept: Long? = null,
        account: String = "Personal",
    ) = MailSender(
        id = id, account = account, name = "Sender $id", domain = "$id.example", method = method, lastAtMs = lastAt, count = 9,
        askedAtMs = asked, triedAtMs = tried, state = state, note = null, link = null,
        unopened = unopened, unopenedAtMs = countedAt, keptAtMs = kept,
    )

    @Test
    fun theWeekStartsOnSaturdayAtNine() {
        assertEquals(at(saturday, 9), MailUnopenedRules.weekStartMs(satMorning, cal))
        // Saturday 08:59 is still last week's.
        assertEquals(at(saturday - 7, 9), MailUnopenedRules.weekStartMs(at(saturday, 8, 59), cal))
        // Tuesday and Friday belong to the Saturday before.
        assertEquals(at(saturday - 7, 9), MailUnopenedRules.weekStartMs(at(saturday - 4, 14), cal))
        assertEquals(at(saturday, 9), MailUnopenedRules.weekStartMs(at(saturday + 6, 23, 59), cal))
    }

    @Test
    fun onlyNotOpenedSendersTheServerCanUnsubscribeFromAreOffered() {
        val now = satMorning
        assertTrue(MailUnopenedRules.candidate(s("a"), now))
        assertTrue(MailUnopenedRules.candidate(s("a", method = UnsubscribeMethod.MAILTO), now))
        assertTrue(MailUnopenedRules.candidate(s("a", method = UnsubscribeMethod.CHECK), now))
        // Fewer than three, or one opened (0).
        assertFalse(MailUnopenedRules.candidate(s("a", unopened = 2), now))
        assertFalse(MailUnopenedRules.candidate(s("a", unopened = 0), now))
        // A page to open, or no way out: they stay in Mail.
        assertFalse(MailUnopenedRules.candidate(s("a", method = UnsubscribeMethod.LINK), now))
        assertFalse(MailUnopenedRules.candidate(s("a", method = UnsubscribeMethod.NONE), now))
        // A stale count (the server stopped checking), never counted, asked, tried or kept.
        assertFalse(MailUnopenedRules.candidate(s("a", countedAt = now - 9 * day), now))
        assertFalse(MailUnopenedRules.candidate(s("a", countedAt = null), now))
        assertFalse(MailUnopenedRules.candidate(s("a", asked = now - 1), now))
        assertFalse(MailUnopenedRules.candidate(s("a", tried = now - 1, state = UnsubscribeState.FAILED), now))
        assertFalse(MailUnopenedRules.candidate(s("a", kept = now - day), now))
    }

    @Test
    fun theCardComesOnSaturdayUntilAnsweredThatWeek() {
        val senders = listOf(s("a", unopened = 4), s("b", unopened = 9), s("c", unopened = 1))
        // Friday evening, answered last Saturday: not yet.
        val friday = at(saturday - 1, 19)
        assertNull(MailUnopenedRules.card(senders, seenAtMs = at(saturday - 7, 10), friday, cal))
        // Saturday 09:30: busiest first, only the not-opened ones.
        val card = assertNotNull(MailUnopenedRules.card(senders, seenAtMs = at(saturday - 7, 10), satMorning, cal))
        assertEquals(MailUnopenedRules.TITLE, card.title)
        assertEquals("Unsubscribe from 2?", card.headline)
        assertEquals("Unsubscribe from all 2", card.unsubscribeLabel)
        assertEquals(listOf("b", "a"), card.ids)
        assertEquals("Personal · 9 emails, none opened · last yesterday", card.rows.first().line)
        assertNull(card.moreLine)
        assertEquals("Not opened in 30 days. Unsubscribe from 2?", card.spoken)
        // Answered this week (the button or Not now, either app): gone until next Saturday.
        assertNull(MailUnopenedRules.card(senders, seenAtMs = satMorning, satMorning + 60_000, cal))
        assertNotNull(MailUnopenedRules.card(senders.map { it.copy(unopenedAtMs = at(saturday + 7, 8)) }, seenAtMs = satMorning, at(saturday + 7, 9), cal))
        // Never answered: shows from the first Saturday it can.
        assertNotNull(MailUnopenedRules.card(senders, seenAtMs = null, satMorning, cal))
        // None left to offer.
        assertNull(MailUnopenedRules.card(listOf(s("c", unopened = 1)), null, satMorning, cal))
    }

    @Test
    fun oneSenderAndMoreThanEight() {
        val one = assertNotNull(MailUnopenedRules.card(listOf(s("a")), null, satMorning, cal))
        assertEquals("Unsubscribe from 1 sender?", one.headline)
        assertEquals("Unsubscribe", one.unsubscribeLabel)
        val many = assertNotNull(MailUnopenedRules.card((1..11).map { s("s$it", unopened = 3 + it) }, null, satMorning, cal))
        assertEquals(8, many.rows.size)
        assertEquals(11, many.ids.size)
        assertEquals("s11", many.ids.first())
        assertEquals("+ 3 more · the button unsubscribes from them too", many.moreLine)
        assertEquals("Unsubscribe from all 11", many.unsubscribeLabel)
    }

    @Test
    fun theServerWritesTheCountOnlyWhenItChangesOrADayHasPassed() {
        val now = satMorning
        val fresh = MailUnopenedRules.serverFields(null, total = 4, opened = 0, nowMs = now)
        assertEquals(mapOf(MailSenderFields.UNOPENED to FieldValue.Int64(4), MailSenderFields.UNOPENED_AT to FieldValue.Int64(now)), fresh)
        // One opened: 0.
        assertEquals(FieldValue.Int64(0), MailUnopenedRules.serverFields(null, 4, 1, now)?.get(MailSenderFields.UNOPENED))
        // Same count within a day: nothing to write; a day later it's refreshed.
        val prev = s("a", unopened = 4, countedAt = now - 3_600_000)
        assertNull(MailUnopenedRules.serverFields(prev, 4, 0, now))
        assertNotNull(MailUnopenedRules.serverFields(prev, 5, 0, now))
        assertNotNull(MailUnopenedRules.serverFields(prev.copy(unopenedAtMs = now - day), 4, 0, now))
    }

    @Test
    fun theFieldsReadBackAndMailsRowSaysNoneOpened() {
        val f = MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", "digest.example", UnsubscribeMethod.ONE_CLICK, satMorning - day, 6) +
            MailUnopenedRules.serverFields(null, 6, 0, satMorning)!! + MailUnopenedRules.keepFields(satMorning)
        val r = assertNotNull(MailUnsubscribeRules.read("ms1", f))
        assertEquals(6, r.unopened)
        assertEquals(satMorning, r.unopenedAtMs)
        assertEquals(satMorning, r.keptAtMs)
        val row = MailSenderPanelRules.rows(listOf(r), "Personal", satMorning, cal).single()
        assertEquals("digest.example · 6 emails · last yesterday · none opened in 30 days", row.line)
        // Keep cleared (Undo writes 0).
        assertNull(MailUnsubscribeRules.read("ms1", f + (MailSenderFields.KEPT_AT to FieldValue.Int64(0)))?.keptAtMs)
    }

    @Test
    fun undoClearsAnAskOnlyBeforeTheServerActed() {
        val now = satMorning
        assertEquals(mapOf(MailSenderFields.ASKED_AT to FieldValue.Int64(0)), MailUnopenedRules.undoAsk(s("a", asked = now)))
        assertNull(MailUnopenedRules.undoAsk(s("a", asked = now, tried = now + 1, state = UnsubscribeState.DONE)))
        assertNull(MailUnopenedRules.undoAsk(s("a")))
        assertNull(MailUnopenedRules.undoAsk(null))
        // After the undo the sender is offered again.
        val cleared = MailUnsubscribeRules.read("ms1", MailUnsubscribeRules.senderFields("Personal", "X", null, UnsubscribeMethod.ONE_CLICK, now - day, 5) +
            MailUnopenedRules.serverFields(null, 5, 0, now)!! + (MailSenderFields.ASKED_AT to FieldValue.Int64(0)))!!
        assertTrue(MailUnopenedRules.candidate(cleared, now))
    }

    @Test
    fun lines() {
        assertEquals("Unsubscribing from 11 senders at the next check", MailUnopenedRules.askedLine(11))
        assertEquals("Unsubscribing from 1 sender at the next check", MailUnopenedRules.askedLine(1))
        assertEquals(MailUnopenedRules.NONE_LEFT, MailUnopenedRules.askedLine(0))
        assertEquals("Keeping Weekly Digest · not offered again", MailUnopenedRules.keptLine("Weekly Digest"))
    }
}
