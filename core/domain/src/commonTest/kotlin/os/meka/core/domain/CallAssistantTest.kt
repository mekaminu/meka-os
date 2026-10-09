package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant's voice messages (build plan M1, call assistant slice 2): written by the server, shown on both apps. */
class CallAssistantTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val server = world.device("server")
    private val a = world.device("android")
    private val m = world.device("mac")
    private val t0 = 1_791_360_000_000L

    init { world.clock.nowMs = t0 + 10 * hour }

    /** What the server writes for one call, as server-authored ops. */
    private fun leave(callId: String, from: String?, at: Long, transcript: String? = null, urgent: Boolean = false) {
        val id = CallAssistantRules.heldId("twilio", callId)
        server.replica.commitLocal(EntityTypes.HELD_MESSAGE, id, CallAssistantRules.messageFields(from, at))
        if (urgent) server.replica.commitLocal(EntityTypes.HELD_MESSAGE, id, mapOf(HeldMessageFields.URGENT to true.fv()))
        CallAssistantRules.transcript(transcript)?.let {
            server.replica.commitLocal(EntityTypes.HELD_MESSAGE, id, mapOf(HeldMessageFields.TEXT to it.fv()))
        }
    }

    private fun syncAll() { server.sync(); a.sync(); m.sync() }

    @Test
    fun theGreetingSaysItIsAnAutomatedAssistant() {
        assertTrue("automated assistant" in CallAssistantScript.GREETING)
        assertTrue(CallAssistantScript.GREETING.endsWith("Can I take a message, and is it urgent?"))
    }

    @Test
    fun aVoiceMessageJoinsTheSummaryOnBothAppsAndTheFoldNamesTheCaller() {
        leave("CA1", "+44 7700 900123", t0 + 10 * hour, "Hi it's Mum, call me back about Sunday")
        leave("CA2", null, t0 + 10 * hour + 60_000)
        syncAll()
        val mac = HeldMessages(m.replica) { world.clock.nowMs }.summary()
        assertEquals(listOf("Withheld number", "+44 7700 900123"), mac.people.map { it.personName })
        assertEquals("1 voice message", mac.people[0].line)
        assertEquals("2 people · 2 voice messages", mac.headline)
        assertEquals("Voice message · Transcribing…", mac.people[0].items.single().displayLine)
        assertEquals("Voice message · “Hi it's Mum, call me back about Sunday”", mac.people[1].items.single().displayLine)

        // The Fold knows Mum's number: her message shows under her name and ranks as family.
        val lists = PeopleLists(family = setOf("Mum")).withNumber("Mum", "07700 900123")
        val fold = HeldMessages(a.replica) { world.clock.nowMs }.summary().withLists(lists)
        assertEquals(listOf("Mum", "Withheld number"), fold.people.map { it.personName })
        assertTrue(fold.people[0].isFamily)
        // ...and her missed call from the phone's own notification is the same person.
        val missed = CapturedItem("x", CaptureApp.PHONE, CaptureKind.MISSED_CALL, "Mum", null, null, t0 + 10 * hour - 5_000)
        val both = AfterWorkSummaries.build(HeldMessages(a.replica) { world.clock.nowMs }.items() + missed, lists)
        assertEquals("1 voice message · 1 missed call", both.people.first { it.personName == "Mum" }.line)
        assertEquals("While you were at work", AfterWorkNudge.text(fold)!!.title)
        assertEquals("Mum and Withheld number · 2 voice messages", AfterWorkNudge.text(fold)!!.text)
    }

    @Test
    fun urgentByTheCallersAnswerOrItsWordsAlertsOnceWithinTheHour() {
        leave("CA1", "07700 900111", t0 + 10 * hour, urgent = true)
        leave("CA2", "07700 900222", t0 + 10 * hour, "The school called, it's an emergency")
        leave("CA3", "07700 900333", t0 + 10 * hour, "No rush")
        leave("CA4", "07700 900444", t0 + 8 * hour, urgent = true) // two hours ago: too late to ring now
        syncAll()
        val items = HeldMessages(a.replica) { world.clock.nowMs }.items()
        val now = world.clock.nowMs
        val alert = CallAssistantRules.toAlert(items, emptySet(), now)
        assertEquals(setOf("07700 900111", "07700 900222"), alert.map { it.personName }.toSet())
        assertEquals(BreakThrough.URGENT, Capture.breakThrough(alert[0], PeopleLists()))
        assertTrue(CallAssistantRules.toAlert(items, alert.map { it.id }.toSet(), now).isEmpty())
        // Urgent people lead the summary.
        val summary = HeldMessages(m.replica) { world.clock.nowMs }.summary()
        assertEquals(3, summary.urgentPeople)
        assertEquals("07700 900333", summary.people.last().personName)
    }

    @Test
    fun aRetriedWebhookIsOneMessageAndDoneClearsItOnBoth() {
        leave("CA1", "07700 900123", t0 + 10 * hour)
        leave("CA1", "07700 900123", t0 + 10 * hour)
        syncAll()
        assertEquals(1, HeldMessages(m.replica) { world.clock.nowMs }.items().size)
        assertEquals(1, HeldMessages(m.replica) { world.clock.nowMs }.clear())
        m.sync(); a.sync()
        assertTrue(HeldMessages(a.replica) { world.clock.nowMs }.items().isEmpty())
    }

    @Test
    fun theUrgentAnswer() {
        assertEquals(true, CallAssistantRules.isUrgentAnswer("1", null))
        assertEquals(false, CallAssistantRules.isUrgentAnswer("2", "yes"))
        assertEquals(true, CallAssistantRules.isUrgentAnswer(null, "Yes please."))
        assertEquals(true, CallAssistantRules.isUrgentAnswer("", "it's an emergency"))
        assertEquals(false, CallAssistantRules.isUrgentAnswer(null, "No, it's not urgent"))
        assertEquals(false, CallAssistantRules.isUrgentAnswer(null, "nah it can wait"))
        assertNull(CallAssistantRules.isUrgentAnswer(null, "hello?"))
        assertNull(CallAssistantRules.isUrgentAnswer("9", null))
    }

    @Test
    fun callersAndTranscripts() {
        assertEquals("Withheld number", CallAssistantRules.callerName(null))
        assertEquals("Withheld number", CallAssistantRules.callerName("anonymous"))
        assertEquals("Withheld number", CallAssistantRules.callerName("+266696687"))
        assertEquals("+44 7700 900123", CallAssistantRules.callerName(" +44 7700 900123 "))
        assertEquals("call me back", CallAssistantRules.transcript("  call   me\nback "))
        assertNull(CallAssistantRules.transcript("   "))
        assertEquals(2_000, CallAssistantRules.transcript("a".repeat(5_000))!!.length)
        assertEquals(CallAssistantRules.heldId("twilio", "CA1"), CallAssistantRules.heldId("twilio", "CA1"))
        assertFalse(CallAssistantRules.heldId("twilio", "CA1") == CallAssistantRules.heldId("twilio", "CA2"))
    }

    @Test
    fun outsideWorkTheGreetingAndTitleDontSayAtWork() {
        assertTrue(CallAssistantScript.greeting(atWork = false).startsWith("Hi, you've reached Meka's automated assistant. Meka can't take your call right now"))
        assertFalse("at work" in CallAssistantScript.greeting(atWork = false))
        assertFalse("after work" in CallAssistantScript.thanks(atWork = false))
        assertEquals(CallAssistantScript.GREETING, CallAssistantScript.greeting(atWork = true))

        // Fri 9 Oct 2026 (epoch day 20735): the default schedule (Mon–Fri 09:00–17:30) has Meka at work at 10:00, not at 20:49.
        val fri = 20_735L
        assertEquals(5, CivilDate.isoDayOfWeek(fri))
        val ms = t0
        assertTrue(CallAssistantRules.atWork(null, null, null, fri, 10 * 60, ms))
        assertFalse(CallAssistantRules.atWork(null, null, null, fri, 20 * 60 + 49, ms))
        assertFalse(CallAssistantRules.atWork(null, null, null, fri + 1, 10 * 60, ms)) // Saturday
        // Work switched off by hand (a sick day) and a bank holiday both count as away.
        assertFalse(CallAssistantRules.atWork(null, WorkSwitch(false, ms, true).encode(), null, fri, 10 * 60, ms))
        assertFalse(CallAssistantRules.atWork(null, null, BankHolidays.encode(listOf(BankHoliday(fri, "Test holiday"))), fri, 10 * 60, ms))

        // A message taken away: marked, and a summary of only those is "Messages MEKA took".
        val id = CallAssistantRules.heldId("twilio", "CA9")
        server.replica.commitLocal(EntityTypes.HELD_MESSAGE, id, CallAssistantRules.messageFields("+447700900999", t0 + 10 * hour, atWork = false))
        syncAll()
        val away = HeldMessages(m.replica) { world.clock.nowMs }.summary()
        assertTrue(away.people.single().items.single().away)
        assertEquals("Messages MEKA took", away.title)
        assertEquals("Messages MEKA took", AfterWorkNudge.text(away)!!.title)

        // One held at work as well: back to "While you were at work".
        leave("CA10", "+447700900888", t0 + 10 * hour)
        syncAll()
        assertEquals("While you were at work", HeldMessages(m.replica) { world.clock.nowMs }.summary().title)
        assertNull(CallAssistantRules.messageFields("+447700900888", 0L)[HeldMessageFields.AWAY])
    }

    @Test
    fun aVoiceMessageWithNoWordsReadsTranscribingThenSettles() {
        leave("CA1", "07700 900111", t0 + 10 * hour)
        leave("CA2", "07700 900222", t0 + 10 * hour)
        server.replica.commitLocal(EntityTypes.HELD_MESSAGE, CallAssistantRules.heldId("twilio", "CA2"), mapOf(HeldMessageFields.NO_TRANSCRIPT to true.fv()))
        syncAll()
        val held = HeldMessages(a.replica) { world.clock.nowMs }
        val lines = held.items().associate { it.personName to it.displayLine }
        assertEquals("Voice message · Transcribing…", lines["07700 900111"])
        assertEquals("Voice message · no words came through", lines["07700 900222"])
        // After 15 minutes with no words it stops promising them.
        world.clock.nowMs = t0 + 10 * hour + CallAssistantRules.TRANSCRIBING_MS
        assertEquals("Voice message", held.items().first { it.personName == "07700 900111" }.displayLine)
    }
}
