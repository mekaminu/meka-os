package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailDraft
import os.meka.core.domain.MailDraftRules
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.MailDraftCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6b-2b: Draft it through the facade — one call, his tone, checked, nothing written or sent. */
class MailDraftFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()

    private inner class Server : SyncTransport, AiApi, AccountsApi {
        var calendars = emptyList<ConnectedAccount>()
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = calendars
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = calendars
        private val sync = os.meka.core.testing.FaultyTransport(SyncService(serverOps))
        val sent = mutableListOf<MailDraftCodec.Request>()
        var reply = MailDraftCodec.Response("answered", "Hi Sam,\n\nYes, I can drive on Saturday.\n\nM")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun mailDraft(request: MailDraftCodec.Request): MailDraftCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server()
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmd${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, from: String, subject: String, account: String = "Personal"): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields(account, from, "sam@example.com", subject, "Are you still OK to drive on Saturday?", now - hour, 1))
        return id
    }

    @Test
    fun theChaseIsWrittenFromWhatTheRowKnowsAndNothingIsSent() = runTest {
        val sentDay = os.meka.core.domain.CivilDate.toEpochDay(2026, 10, 12)
        val fu = os.meka.core.domain.MailFollowUpRules.read(
            os.meka.core.domain.MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote", "Could you send the quote?", now - 5 * 86_400_000L),
            setOf("alex@example.com"), false,
        )!!
        val fid = os.meka.core.domain.MailFollowUpRules.findId("acc1", "s1")
        os.meka.core.domain.MailFollowUpRules.serverFields(fu, "Business", "Quote for the website", sentDay).forEach { (f, v) ->
            serverOps.append(Op("srvmd${k++}", "hh", EntityTypes.MAIL_FIND, fid, f, v, clock.now(), emptyList(), "server"))
        }
        val fold = core()
        assertTrue(fold.syncNow())
        fold.setAboutYou(os.meka.core.domain.AboutField.SHOWN_NAME, "Alex")
        fold.addMailFind(fid)
        val row = fold.mailChases.value!!.rows.single()
        assertTrue(os.meka.core.domain.MailChaseDraftRules.autoDraft(row))
        server.reply = MailDraftCodec.Response("answered", "Hi Sam,\n\nJust checking in on the website quote — any news?\n\nThanks,\nAlex")
        assertTrue(fold.syncNow())
        val opsBefore = serverOps.after("hh", 0, 100_000).size

        val d = fold.draftMailChase(fid)
        assertEquals("Hi Sam,\n\nJust checking in on the website quote — any news?\n\nThanks,\nAlex", d.text)
        assertEquals(os.meka.core.domain.MailChaseDraftRules.DRAFTED_LINE, d.line)
        val asked = server.sent.single()
        assertEquals(MailDraftCodec.CHASE_FOLLOW_UP, asked.chase)
        assertEquals("Sam Taylor", asked.from)
        assertEquals("Quote for the website", asked.subject)
        assertEquals("Business", asked.account)
        assertEquals(row.business, asked.business)
        assertEquals(row.title, asked.what)
        assertEquals("Mon 12 Oct", asked.since)
        assertEquals("Alex", asked.sign)
        assertNull(asked.preview)
        // Nothing written or sent by drafting: the row is still there and the server has no new op.
        assertTrue(fold.syncNow())
        assertEquals(opsBefore, serverOps.after("hh", 0, 100_000).size)
        assertEquals(listOf(fid), fold.mailChases.value!!.rows.map { it.id })

        // An email he received, on Waiting for and due: chased in reply to their email, with its preview.
        val a = email("m1", "Jo Bloggs", "Signed contract")
        assertTrue(fold.syncNow())
        fold.waitingFromMailItem(a)
        now += 4 * 24 * hour
        assertTrue(fold.syncNow())
        fold.tick()
        assertTrue(fold.mailChases.value!!.rows.any { it.id == a })
        fold.draftMailChase(a)
        val waiting = server.sent.last()
        assertEquals(MailDraftCodec.CHASE_WAITING, waiting.chase)
        assertEquals("Jo Bloggs", waiting.from)
        assertEquals("Signed contract", waiting.subject)
        assertEquals("Are you still OK to drive on Saturday?", waiting.preview)
        assertEquals("Sat 17 Oct", waiting.since)

        // A usable answer only: a link drops it whole and the plain chase stays; AI off and offline say why.
        server.reply = MailDraftCodec.Response("answered", "Hi Sam, see https://example.com")
        fold.draftMailChase(fid).let { assertNull(it.text); assertEquals(os.meka.core.domain.MailChaseDraftRules.UNUSABLE_LINE, it.line) }
        server.reply = MailDraftCodec.Response("off", reason = "Navilife's AI is off")
        assertNull(fold.draftMailChase(fid).text)
        server.down = true
        fold.draftMailChase(fid).let { assertNull(it.text); assertEquals(AskRules.OFFLINE_LINE, it.line) }
        assertEquals(os.meka.core.domain.MailChaseRules.UNKNOWN, fold.draftMailChase("nothing").line)
    }

    @Test
    fun draftItSendsTheCardsWordsAndHisEarlierRepliesAndWritesNothing() = runTest {
        val old = email("m0", "Alex Reed", "Dinner")
        val a = email("m1", "Sam Taylor", "Saturday's game", "Business")
        val fold = core()
        assertTrue(fold.syncNow())
        // An earlier reply he sent becomes a tone example once the server says it went.
        fold.replyToMailItem(old, "Sounds good, see you at 7.")
        assertTrue(fold.syncNow())
        now += 60_000
        serverWrite(old, MailNeedsRules.replySentFields(now))
        assertTrue(fold.syncNow())

        val draft = fold.draftMailReply(a)
        assertEquals("Hi Sam,\n\nYes, I can drive on Saturday.\n\nM", draft.text)
        assertEquals(MailDraftRules.DRAFTED_LINE, draft.line)
        val sent = server.sent.single()
        assertEquals("Sam Taylor", sent.from)
        assertEquals("Saturday's game", sent.subject)
        assertEquals("Business", sent.account)
        assertTrue(sent.business)
        assertEquals("Are you still OK to drive on Saturday?", sent.preview)
        assertEquals(listOf("Sounds good, see you at 7."), sent.examples)
        assertEquals("2026-10-17", sent.date)
        // Never the sender's address.
        assertTrue("sam@example.com" !in MailDraftCodec.encodeRequest(sent))
        // Nothing written, nothing sent: the card is still there and the phone wrote nothing on that email.
        assertTrue(fold.syncNow())
        assertTrue(serverOps.after("hh", 0, 100_000).map { it.op }.none { it.entityId == a && it.deviceId != "server" })
        assertEquals(listOf(a), fold.mailNeeds.value?.rows?.map { it.id })
    }

    @Test
    fun anUnsafeDraftOfflineAndAiOffGiveNoText() = runTest {
        val a = email("m1", "Sam Taylor", "Saturday's game")
        val fold = core()
        assertTrue(fold.syncNow())
        server.reply = MailDraftCodec.Response("answered", "Pay here: https://example.com/pay")
        assertEquals(MailDraft(null, MailDraftRules.UNUSABLE_LINE), fold.draftMailReply(a).let { MailDraft(it.text, it.line) })
        server.reply = MailDraftCodec.Response("off", reason = "Navilife's AI is off")
        val off = fold.draftMailReply(a)
        assertNull(off.text)
        assertEquals("Navilife's AI is off", off.line)
        server.down = true
        assertEquals(AskRules.OFFLINE_LINE, fold.draftMailReply(a).line)
        assertEquals(MailNeedsRules.UNKNOWN, fold.draftMailReply("nope").line)
        assertEquals(3, server.sent.size)
    }

    @Test
    fun sendAllSendsEachFromItsOwnAccountAfterTheHoldAndOneUndoStopsThemAll() = runTest {
        val a = email("m1", "Sam Taylor", "Saturday's game", "Business")
        val b = email("m2", "Alex Reed", "Dinner")
        val c = email("m3", "Pat Lee", "Kit")
        val fold = core()
        assertTrue(fold.syncNow())
        val card = fold.mailNeeds.value!!
        val all = os.meka.core.domain.MailSendAllRules.sendAll(card.rows, mapOf(a to "Yes, I'll drive.", b to "Sounds good, 7 it is."))!!
        assertEquals(os.meka.core.domain.MailSendAllRules.sendingLine(2), fold.replyToMailItems(all.ids, all.texts))
        // Both leave the card, the third stays; each carries its own words and is held for Undo's window.
        assertEquals(listOf(c), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow())
        val mac = MekaCore(
            householdId = "hh", deviceId = "mac", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        assertTrue(mac.syncNow())
        assertEquals(listOf(c), mac.mailNeeds.value!!.rows.map { it.id })
        // One Undo brings both back.
        now += 1_000
        fold.undoMailItem()
        assertEquals(setOf(a, b, c), fold.mailNeeds.value?.rows?.map { it.id }?.toSet())
        // A blank reply is never sent; with nothing sendable, nothing is written.
        assertEquals(MailNeedsRules.REPLY_EMPTY, fold.replyToMailItems(listOf(a), listOf("  ")))
        assertEquals(MailNeedsRules.UNKNOWN, fold.replyToMailItems(listOf("nope"), listOf("Hi")))
        assertEquals(3, fold.mailNeeds.value?.rows?.size)
        now += 1_000
        // One that can go and one that can't: the good one goes, with the one-reply line from its own account.
        assertEquals(MailNeedsRules.replySendingLine("Business"), fold.replyToMailItems(listOf(a, b), listOf("Yes.", "")))
        assertEquals(setOf(b, c), fold.mailNeeds.value?.rows?.map { it.id }?.toSet())
        assertTrue(server.sent.isEmpty())
    }

    /** Drafted replies come to him: the server's draft shows on the card; Not needed puts it away (Undo); nothing is sent. */
    @Test
    fun theServersDraftShowsOnTheCardAndNotNeededPutsItAway() = runTest {
        val a = email("m1", "Sam Taylor", "Saturday")
        serverWrite(a, os.meka.core.domain.MailAutoDraftRules.serverFields(true, "Are you still OK to drive on Saturday?") +
            os.meka.core.domain.MailAutoDraftRules.draftedFields("Yes, still fine. See you at 9.", now))
        val fold = core()
        assertTrue(fold.syncNow())
        val card = fold.mailNeeds.value!!
        assertEquals("1 email needs a reply · 1 drafted", card.replyLine)
        // Slice 2: Today says so too, and Not needed takes it off at once.
        assertEquals("1 email needs a reply · 1 drafted", fold.today.value.mailReplies?.text)
        val row = card.rows.single()
        assertEquals("Yes, still fine. See you at 9.", row.autoDraft)
        assertEquals("Are you still OK to drive on Saturday?", row.wants)
        // Not needed: the draft goes, the email stays; Undo brings it back.
        assertEquals(os.meka.core.domain.MailAutoDraftRules.NOT_NEEDED_LINE, fold.dismissMailDraft(a))
        assertNull(fold.mailNeeds.value!!.rows.single().autoDraft)
        assertNull(fold.mailNeeds.value!!.replyLine)
        assertNull(fold.today.value.mailReplies)
        now += 1_000
        fold.undoMailItem()
        assertEquals("Yes, still fine. See you at 9.", fold.mailNeeds.value!!.rows.single().autoDraft)
        assertEquals(MailNeedsRules.UNKNOWN, fold.dismissMailDraft("nope"))
        // Send is his tap: the draft's words go through the reply path (held for Undo), never by themselves.
        assertEquals(MailNeedsRules.replySendingLine("Personal"), fold.replyToMailItem(a, row.autoDraft!!))
        assertTrue(fold.mailNeeds.value == null)
        assertTrue(server.sent.isEmpty())
    }

    /** Drafted replies, slice 4: the draft on the watch; Send goes through the reply path with Undo; Not needed too. */
    @Test
    fun theWatchShowsTheDraftAndSendsItOnlyOnHisTap() = runTest {
        val W = os.meka.core.domain.WatchDraftRules
        val a = email("m1", "Sam Taylor", "Saturday")
        serverWrite(a, os.meka.core.domain.MailAutoDraftRules.serverFields(true, "Are you still OK to drive on Saturday?") +
            os.meka.core.domain.MailAutoDraftRules.draftedFields("Yes, still fine. See you at 9.", now))
        val watch = core()
        assertTrue(watch.syncNow())
        val d = watch.watchHome().draft!!
        assertEquals(a, d.mailId)
        assertEquals("Sam Taylor · Personal", d.from)
        assertEquals("Yes, still fine. See you at 9.", d.draft)
        assertTrue(d.canSend)
        // Showing it sends nothing.
        assertTrue(server.sent.isEmpty())
        assertTrue(serverOps.after("hh", 0, 100_000).map { it.op }.none { it.entityId == a && it.deviceId != "server" })

        // Not needed: put away (the email stays); Undo brings it back.
        assertEquals(W.PUT_AWAY, watch.watchDismissDraft(a))
        assertNull(watch.watchHome().draft)
        assertEquals(1, watch.mailNeeds.value!!.rows.size)
        watch.watchDraftUndo()
        assertEquals(a, watch.watchHome().draft?.mailId)

        // Send: his tap sends the draft exactly as shown, held 20 s; the draft leaves the wrist; Undo stops it.
        now += 1_000
        assertEquals(W.sendingLine("Sam Taylor") to true, watch.watchSendDraft(a))
        assertNull(watch.watchHome().draft)
        assertTrue(watch.mailNeeds.value?.rows.isNullOrEmpty())
        watch.watchDraftUndo()
        assertEquals(a, watch.watchHome().draft?.mailId)
        now += 1_000
        assertEquals(W.sendingLine("Sam Taylor") to true, watch.watchSendDraft(a))
        assertTrue(watch.syncNow())
        val written = serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityId == a && it.deviceId != "server" }
        assertTrue(written.any { it.field == os.meka.core.domain.MailItemFields.REPLY_TEXT })
        // Once it has gone, or for an unknown email, nothing more is written.
        assertEquals(W.GONE to false, watch.watchSendDraft(a))
        assertEquals(W.GONE to false, watch.watchSendDraft("nope"))
    }

    private data class MailDraft(val text: String?, val line: String)

    /** Scheduling assistant, slice 1: Suggest times writes his free times as a reply; nothing is written, booked or sent. */
    @Test
    fun suggestTimesOffersFreeTimesFromHisCalendarsAndWritesNothing() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        val id = MailNeedsRules.itemId("acc1", "meet")
        serverWrite(id, MailNeedsRules.serverFields("Business", "Sam Taylor", "sam@example.com", "Catch up?", "Can we meet next week to go over the plan?", now - hour, 1))
        // Monday morning is taken until 11:00 on one calendar.
        mapOf(
            os.meka.core.domain.EventFields.TITLE to FieldValue.Text("Planning"),
            os.meka.core.domain.EventFields.START_AT to FieldValue.Int64(at(19, 9)),
            os.meka.core.domain.EventFields.END_AT to FieldValue.Int64(at(19, 11)),
            os.meka.core.domain.EventFields.ALL_DAY to FieldValue.Bool(false),
            os.meka.core.domain.EventFields.PROVIDER to FieldValue.Text("google"),
            os.meka.core.domain.EventFields.REMOVED to FieldValue.Bool(false),
        ).forEach { (f, v) -> serverOps.append(Op("srvmd${k++}", "hh", EntityTypes.EVENT, "ev1", f, v, clock.now(), emptyList(), "server")) }
        val fold = core()
        assertTrue(fold.syncNow())
        fold.setAboutYou(os.meka.core.domain.AboutField.SHOWN_NAME, "Alex")
        assertTrue(fold.syncNow())
        assertTrue(fold.mailNeeds.value!!.rows.single().asksToMeet)
        val opsBefore = serverOps.after("hh", 0, 100_000).size

        val d = fold.suggestMeetingTimes(id)
        assertEquals(os.meka.core.domain.MeetingRequestRules.SUGGESTED_LINE, d.line)
        assertEquals(
            "Hi Sam,\n\nHappy to meet. I'm free at any of these times (UK time):\n\n" +
                "• Monday 19 October, 11:30–12:30\n• Tuesday 20 October, 13:00–14:00\n• Wednesday 21 October, 09:00–10:00\n\n" +
                "Let me know which suits you and I'll put it in the diary.\n\nAlex",
            d.text,
        )
        // No AI asked, nothing written: the card is still there and the server has no new op.
        assertTrue(server.sent.isEmpty())
        assertTrue(fold.syncNow())
        assertEquals(opsBefore, serverOps.after("hh", 0, 100_000).size)
        assertEquals(listOf(id), fold.mailNeeds.value?.rows?.map { it.id })
        assertEquals(os.meka.core.domain.MeetingRequestRules.UNKNOWN, fold.suggestMeetingTimes("nothing").line)
    }

    /** Scheduling assistant, slice 2: their answer picks one of his offered times; Book it adds it to his calendar, Undo takes it back. */
    @Test
    fun theirPickIsBookedOnHisTapAndUndoTakesItBack() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        val offer = MailNeedsRules.itemId("acc1", "ask")
        serverWrite(offer, MailNeedsRules.serverFields("Business", "Sam Taylor", "sam@example.com", "Catch up?", "Can we meet next week?", at(15, 9), 1, threadKey = "th1"))
        serverWrite(offer, mapOf(
            os.meka.core.domain.MailItemFields.DONE_ASKED_AT to FieldValue.Int64(at(16, 10)),
            os.meka.core.domain.MailItemFields.REPLY_ASKED_AT to FieldValue.Int64(at(16, 10)),
            os.meka.core.domain.MailItemFields.REPLY_TEXT to FieldValue.Text(
                "Hi Sam,\n\n• Monday 19 October, 11:30–12:30\n• Tuesday 20 October, 13:00–14:00\n\nLet me know which suits you.\n\nAlex",
            ),
            os.meka.core.domain.MailItemFields.REPLY_SENT_AT to FieldValue.Int64(at(16, 10, 1)),
        ))
        val answer = MailNeedsRules.itemId("acc1", "answer")
        serverWrite(answer, MailNeedsRules.serverFields("Business", "Sam Taylor", "sam@example.com", "Re: Catch up?", "Tuesday at 1pm works for me, thanks!", at(17, 9), 2, threadKey = "th1"))
        val fold = core()
        assertTrue(fold.syncNow())
        val row = fold.mailNeeds.value!!.rows.single()
        assertEquals(answer, row.id)
        assertEquals("Sam picked Tue 20 Oct, 13:00–14:00 · one of the times you offered", row.pickedLine)
        assertTrue(row.canBook)

        // No calendar he lets Navilife edit yet: said in words, nothing written.
        assertEquals(os.meka.core.domain.MeetingPickRules.NO_ACCOUNT, fold.bookMeetingPick(answer))
        server.calendars = listOf(ConnectedAccount("google", "alex@example.com", "ok", null, canEdit = true))
        fold.refreshCalendarAccounts()

        assertEquals("Adding “Meeting with Sam Taylor” to Google · Tue 20 Oct, 13:00 · write back to confirm", fold.bookMeetingPick(answer))
        assertEquals("Adding “Meeting with Sam Taylor” to Google", fold.calendarEditLines.value.single().text)
        val booked = fold.mailNeeds.value!!.rows.single()
        assertEquals("Booked · Tue 20 Oct, 13:00–14:00 · in your calendar", booked.pickedLine)
        assertEquals(false, booked.canBook)
        // Undo inside the five seconds: no event, and Book it is back.
        fold.undoMailItem()
        assertTrue(fold.calendarEditLines.value.isEmpty())
        assertTrue(fold.mailNeeds.value!!.rows.single().canBook)
        // Booked again and synced: the email carries the booking for his other devices; nothing was asked of the AI.
        fold.bookMeetingPick(answer)
        now += 10_000
        assertTrue(fold.syncNow())
        val ops = serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityId == answer && it.field == os.meka.core.domain.MailItemFields.MEETING_EDIT_ID }
        assertTrue((ops.last().value as FieldValue.Text).value.isNotEmpty())
        assertTrue(server.sent.isEmpty())
    }

    /** Scheduling assistant, slice 3: they couldn't make his times; Suggest times offers others, in their time too. */
    @Test
    fun aDeclinedOfferSuggestsOtherDaysInTheirTime() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        val offer = MailNeedsRules.itemId("acc1", "ask")
        serverWrite(offer, MailNeedsRules.serverFields("Business", "Sam Taylor", "sam@example.com", "Catch up?", "Can we meet next week? I'm based in New York.", at(15, 9), 1, threadKey = "th1"))
        serverWrite(offer, mapOf(
            os.meka.core.domain.MailItemFields.DONE_ASKED_AT to FieldValue.Int64(at(15, 10)),
            os.meka.core.domain.MailItemFields.REPLY_ASKED_AT to FieldValue.Int64(at(15, 10)),
            os.meka.core.domain.MailItemFields.REPLY_TEXT to FieldValue.Text(
                "Hi Sam,\n\n• Monday 19 October, 11:30–12:30\n• Tuesday 20 October, 13:00–14:00\n\nLet me know which suits you.\n\nAlex",
            ),
            os.meka.core.domain.MailItemFields.REPLY_SENT_AT to FieldValue.Int64(at(15, 10, 1)),
        ))
        val answer = MailNeedsRules.itemId("acc1", "answer")
        serverWrite(answer, MailNeedsRules.serverFields("Business", "Sam Taylor", "sam@example.com", "Re: Catch up?", "Sorry, none of those work for me. Any other times?", at(15, 11), 2, threadKey = "th1"))
        val fold = core()
        assertTrue(fold.syncNow())
        val row = fold.mailNeeds.value!!.rows.single()
        assertEquals("Sam can't make the times you offered · Suggest times finds others", row.pickedLine)
        assertTrue(row.asksToMeet)

        val d = fold.suggestMeetingTimes(answer)
        val text = d.text!!
        assertTrue(text.startsWith("Hi Sam,\n\nNo problem. How about one of these instead (UK time):"), text)
        // Not Monday 19 or Tuesday 20 again, and each time also in New York's (five hours behind London this week).
        assertFalse(text.contains("19 October") || text.contains("20 October"), text)
        val lines = text.lines().filter { it.startsWith("• ") }
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.endsWith(" New York time)") }, text)
        // Read back as he'd send it, their time picks it.
        val offered = os.meka.core.domain.MeetingPickRules.offered(text, now, ZoneCalendar { london })
        assertEquals(3, offered.size)
        assertTrue(offered.all { it.theirStartMin != null && it.theirStartMin!! >= 8 * 60 })
        assertTrue(server.sent.isEmpty())
    }
}
