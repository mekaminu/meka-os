package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.CallReason
import os.meka.core.domain.CallSignals
import os.meka.core.domain.CallVerdict
import os.meka.core.domain.PeopleLists
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spam call protection through the facade (call assistant polish 8b): the block list, quiet hours and Activity. */
class CallSpamFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val lists = PeopleLists(family = setOf("Jeanette")).withNumber("Jeanette", "+447700900111")

    @Test
    fun aBlockedNumberIsRejectedAndLoggedOnce() = runTest {
        val c = core()
        assertTrue(c.blockCaller("01632 960691", "Scam call claiming to be the police"))
        assertEquals(listOf("01632 960691"), c.blockedCallers.value.rows.map { it.number })

        val d = c.screenIncomingCall("+441632960691", lists, emptyList(), CallSignals.NONE)
        assertEquals(CallVerdict.BLOCK, d.verdict)
        c.screenIncomingCall("+441632960691", lists, emptyList(), CallSignals.NONE)
        val rows = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.SCREENED }
        assertEquals(listOf("Blocked a call from 01632 960691"), rows.map { it.summary })
        assertEquals("Why: It is on your block list", rows.single().why)
        assertFalse(rows.single().canUndo)

        // Unblocked, it rings like anyone else (the assistant is off).
        assertTrue(c.unblockCaller(c.blockedCallers.value.rows.single().key))
        assertEquals(CallReason.SWITCHED_OFF, c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals.NONE).reason)
        assertEquals(0, c.blockedCallers.value.rows.size)
    }

    @Test
    fun withTheAssistantOnSpamAndWithheldCallsInQuietHoursGoToTheAssistant() = runTest {
        val c = core()
        c.setCallAssistant(true)
        assertTrue(c.blockCaller("07700 900555", "Kept ringing"))
        assertFalse(c.blockCaller("abc", null))
        assertEquals(CallReason.AUTO_BLOCKED, c.screenIncomingCall("01632 960000", lists, emptyList(), CallSignals(verificationFailed = true)).reason)
        // Jeanette is family: never treated as spam.
        assertTrue(c.screenIncomingCall("07700900111", lists, emptyList(), CallSignals(verificationFailed = true)).rings)
        // A withheld number rings by day (a Saturday, off work)…
        assertTrue(c.screenIncomingCall(null, lists, emptyList(), CallSignals.NONE).rings)
        // …and goes to the assistant at 23:00, in the default quiet hours (22:00–07:00).
        now += 13 * 3_600_000L
        assertEquals(CallReason.WITHHELD_QUIET, c.screenIncomingCall(null, lists, emptyList(), CallSignals.NONE).reason)
        assertEquals(CallVerdict.BLOCK, c.screenIncomingCall("+447700900555", lists, emptyList(), CallSignals.NONE).verdict)
        // The auto-block is a change (with Undo), the other two are calls stopped.
        assertTrue(c.activityView.value.weekLine.contains("2 calls stopped"), c.activityView.value.weekLine)
    }

    @Test
    fun blockOnAHeldMessageFromAStrangerPutsItsNumberOnTheList() = runTest {
        val c = core()
        val stranger = os.meka.core.domain.CapturedItem("v1", os.meka.core.domain.CaptureApp.PHONE, os.meka.core.domain.CaptureKind.VOICE_MESSAGE,
            "+441632960691", "This is the police", null, now - 60_000)
        val jeanette = stranger.copy(id = "v2", personName = "+447700900111")
        val summary = os.meka.core.domain.AfterWorkSummaries.build(listOf(stranger, jeanette), lists)
        val known = summary.people.single { it.personName == "Jeanette" }
        assertFalse(c.blockHeldCaller(known))
        val p = summary.people.single { it.personName == "01632 960691" }
        assertTrue(c.blockHeldCaller(p))
        val row = c.blockedCallers.value.rows.single()
        assertEquals("01632 960691", row.number)
        assertEquals("Blocked today · Left a message · today", row.line)
        assertTrue(c.blockedCallers.value.has(p.blockNumber))
    }

    @Test
    fun anUnknownCallerIsOfferedInLondonTimeAndNotOnceBlockedFromTheNotification() = runTest {
        val c = core()
        val at = now - 2 * 60_000L // 09:58 London (BST)
        val o = c.unknownCallOffer("+441632960001", os.meka.core.domain.CallOutcome.MISSED, 0, at, emptyList())!!
        assertEquals("01632 960001 · not in your contacts", o.title)
        assertEquals("Rang · you missed it · 09:58 · Not blocked · it will ring again", o.line)
        assertEquals("Missed call · Sat 10 Oct", o.why)
        // Block on the notification: on the list with how it called, and never offered again.
        assertTrue(c.blockCaller(o.number, o.why))
        assertEquals("Blocked today · Missed call · Sat 10 Oct", c.blockedCallers.value.rows.single().line)
        assertEquals(null, c.unknownCallOffer("01632 960001", os.meka.core.domain.CallOutcome.MISSED, 0, now, emptyList()))
    }

    @Test
    fun aSuspectFromTheServerGoesToTheAssistantUntilMekaBlocksItOrSaysNotSpam() = runTest {
        val c = core()
        c.setCallAssistant(true)
        var seq = 0
        fun flag(number: String) {
            val key = os.meka.core.domain.BlockedCallerRules.keyOf(number)!!
            for ((f, v) in os.meka.core.domain.SuspectedSpamRules.flagFields(number, "Claims to be the police", now)) {
                ops.append(os.meka.core.sync.Op("s${seq++}", "hh", os.meka.core.domain.EntityTypes.BLOCKED_CALLER, key, f, v, os.meka.core.sync.Hlc(now, seq, "server"), emptyList(), "server"))
            }
        }
        flag("+441632960691")
        flag("+447700900555")
        c.syncNow()
        val suspects = c.blockedCallers.value.suspects
        assertEquals(listOf("01632 960691", "07700 900555"), suspects.map { it.number }.sorted())

        val d = c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals.NONE)
        assertEquals(CallReason.SUSPECTED_SPAM, d.reason)
        assertEquals(CallVerdict.DECLINE, d.verdict)
        assertTrue(c.screenIncomingCall("07700 900555", lists, emptyList(), CallSignals(knownContact = true)).rings)

        assertTrue(c.confirmSuspectedCaller(suspects.first { it.number == "01632 960691" }.key))
        assertEquals(listOf("01632 960691"), c.blockedCallers.value.rows.map { it.number })
        assertEquals(CallVerdict.BLOCK, c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals.NONE).verdict)
        assertTrue(c.dismissSuspectedCaller(suspects.first { it.number == "07700 900555" }.key))
        assertTrue(c.blockedCallers.value.suspects.isEmpty())
        assertFalse(c.screenIncomingCall("07700 900555", lists, emptyList(), CallSignals.NONE).verdict == CallVerdict.BLOCK)
    }

    @Test
    fun aNumberThatFailedTheNetworkCheckIsAutoBlockedWithUndoEvenWithTheAssistantOff() = runTest {
        val c = core()
        val d = c.screenIncomingCall("01632 960123", lists, emptyList(), CallSignals(verificationFailed = true))
        assertEquals(CallVerdict.BLOCK, d.verdict)
        assertEquals(CallReason.AUTO_BLOCKED, d.reason)
        val row = c.blockedCallers.value.rows.single()
        assertEquals("01632 960123", row.number)
        assertEquals("Blocked today · Auto-blocked · failed the network's caller check · Sat 10 Oct", row.line)
        val entry = c.activityView.value.days.flatMap { it.rows }.single { it.kind == ActivityKind.CHANGED }
        assertEquals("Blocked 01632 960123 · it won't ring again", entry.summary)
        assertEquals(entry.id, d.autoBlockId)
        assertTrue(entry.canUndo)
        // Its next call is blocked as on the list; Undo takes it off and it rings again.
        assertEquals(CallReason.BLOCKED, c.screenIncomingCall("01632 960123", lists, emptyList(), CallSignals.NONE).reason)
        c.undoActivity(entry.id)
        assertEquals(0, c.blockedCallers.value.rows.size)
        assertEquals(CallReason.SWITCHED_OFF, c.screenIncomingCall("01632 960123", lists, emptyList(), CallSignals.NONE).reason)
    }

    @Test
    fun spamProtectionOffInAdvancedLetsAFailedCheckRingAndBlocksNothingByItself() = runTest {
        val c = core()
        assertTrue(c.workMode.value.spamProtection)
        assertTrue(c.setSpamProtection(false))
        assertFalse(c.setSpamProtection(false))
        assertFalse(c.workMode.value.spamProtection)
        assertTrue(c.screenIncomingCall("01632 960123", lists, emptyList(), CallSignals(verificationFailed = true)).rings)
        assertEquals(null, c.autoBlockRepeatCaller("01632 960124", 3, now))
        assertEquals(0, c.blockedCallers.value.rows.size)
    }

    @Test
    fun anUnknownNumberCallingTwiceInAWeekWithoutAMessageIsBlockedAfterTheCall() = runTest {
        val c = core()
        // Once: not yet.
        assertEquals(null, c.autoBlockRepeatCaller("01632 960200", 1, now))
        // Twice in 7 days with no message: blocked, with Undo.
        val id = c.autoBlockRepeatCaller("01632 960200", 2, now)!!
        assertEquals("Blocked today · Auto-blocked · called again within a week without leaving a message · Sat 10 Oct",
            c.blockedCallers.value.rows.single().line)
        val entry = c.activityView.value.days.flatMap { it.rows }.single { it.id == id }
        assertEquals("Called 2 times in 7 days and never left a message", entry.detail)
        // Already blocked: nothing more.
        assertEquals(null, c.autoBlockRepeatCaller("01632 960200", 3, now))

        // A number that left a voice message this week isn't blocked for calling again.
        val left = os.meka.core.domain.CapturedItem("v9", os.meka.core.domain.CaptureApp.PHONE, os.meka.core.domain.CaptureKind.VOICE_MESSAGE,
            "+441632960300", "Hi, it's the garage about your car", null, now - 3_600_000L)
        c.holdCaptured(listOf(left), lists)
        assertEquals(null, c.autoBlockRepeatCaller("01632 960300", 2, now))

        // The household profile's numbers never are.
        c.setAboutYou(os.meka.core.domain.AboutField.PARTNER_PHONE, "01632 960400")
        assertEquals(null, c.autoBlockRepeatCaller("01632 960400", 4, now))
        assertTrue(c.screenIncomingCall("01632 960400", lists, emptyList(), CallSignals(verificationFailed = true)).rings)
    }

    /** What the server writes when a caller hangs up on the call assistant without a message (slice 3). */
    private var serverSeq = 0
    private fun serverHangup(number: String, atMs: Long) {
        val key = os.meka.core.domain.BlockedCallerRules.keyOf(number)!!
        for ((field, value) in os.meka.core.domain.AssistantHangupRules.fields(number, atMs)) {
            ops.append(
                os.meka.core.sync.Op(
                    "srvhang${serverSeq++}", "hh", os.meka.core.domain.EntityTypes.BLOCKED_CALLER, key, field, value,
                    os.meka.core.sync.Hlc(atMs, serverSeq, "server"), emptyList(), "server",
                ),
            )
        }
    }

    @Test
    fun aStrangerWhoHungUpOnTheAssistantIsBlockedOnceWithUndo() = runTest {
        val c = core()
        serverHangup("+441632960500", now - 60_000L)
        c.syncNow()
        val pending = c.assistantHangups()
        assertEquals(listOf("+441632960500"), pending.map { it.number })
        val id = c.autoBlockAssistantHangup(pending.single(), known = false, lookups = true)!!
        assertEquals("Blocked today · Auto-blocked · hung up on your call assistant without a message · Sat 10 Oct",
            c.blockedCallers.value.rows.single().line)
        val entry = c.activityView.value.days.flatMap { it.rows }.single { it.id == id }
        assertEquals("Blocked 01632 960500 · it won't ring again", entry.summary)
        assertTrue(entry.canUndo)
        // Looked at: not offered again.
        assertEquals(emptyList(), c.assistantHangups())
        // Undo takes it off the list, and the same hang-up doesn't block it again.
        c.undoActivity(id)
        assertEquals(0, c.blockedCallers.value.rows.size)
        assertEquals(emptyList(), c.assistantHangups())
    }

    @Test
    fun aKnownCallerOrAnUncheckablePhoneOrProtectionOffBlocksNobody() = runTest {
        val c = core()
        serverHangup("01632 960601", now - 60_000L)
        serverHangup("01632 960602", now - 50_000L)
        serverHangup("01632 960603", now - 40_000L)
        serverHangup("01632 960604", now - 30_000L)
        c.setAboutYou(os.meka.core.domain.AboutField.PARTNER_PHONE, "01632 960604")
        c.syncNow()
        val (contact, noLookups, protectionOff, partner) = c.assistantHangups()
        // A contact (or anyone the phone knows): looked at, not blocked.
        assertEquals(null, c.autoBlockAssistantHangup(contact, known = true, lookups = true))
        // The phone couldn't read contacts and the call log: nobody is blocked.
        assertEquals(null, c.autoBlockAssistantHangup(noLookups, known = false, lookups = false))
        // About you's numbers never are.
        assertEquals(null, c.autoBlockAssistantHangup(partner, known = false, lookups = true))
        // Spam protection off in Advanced: nothing.
        c.setSpamProtection(false)
        assertEquals(null, c.autoBlockAssistantHangup(protectionOff, known = false, lookups = true))
        assertEquals(0, c.blockedCallers.value.rows.size)
        assertEquals(emptyList(), c.assistantHangups())

        // A caller who left a voice message this week isn't blocked for hanging up another time.
        c.setSpamProtection(true)
        val left = os.meka.core.domain.CapturedItem("v8", os.meka.core.domain.CaptureApp.PHONE, os.meka.core.domain.CaptureKind.VOICE_MESSAGE,
            "+441632960700", "Hi, it's the garage about your car", null, now - 3_600_000L)
        c.holdCaptured(listOf(left), lists)
        serverHangup("01632 960700", now - 20_000L)
        c.syncNow()
        assertEquals(null, c.autoBlockAssistantHangup(c.assistantHangups().single(), known = false, lookups = true))

        // A hang-up older than a week is left alone.
        serverHangup("01632 960800", now - 8 * 86_400_000L)
        c.syncNow()
        assertEquals(emptyList(), c.assistantHangups())
    }

    @Test
    fun activityBlockedCallsCountsStoppedCallsWithUnblockAndBlockAgain() = runTest {
        val c = core()
        assertTrue(c.blockCaller("01632 960691", "Scam call claiming to be the police"))
        assertTrue(c.blockedCallers.value.stopped.isEmpty())
        assertEquals(null, c.blockedCallers.value.stoppedLine)

        c.screenIncomingCall("+441632960691", lists, emptyList(), CallSignals.NONE)
        now += 60_000
        c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals.NONE)
        // A network-check auto-block counts its own call too.
        c.screenIncomingCall("01632 960000", lists, emptyList(), CallSignals(verificationFailed = true))
        // A call that rang isn't counted.
        c.screenIncomingCall("07700 900777", lists, emptyList(), CallSignals.NONE)

        val view = c.blockedCallers.value
        assertEquals(listOf("01632 960000", "01632 960691"), view.stopped.map { it.number })
        val police = view.stopped.single { it.number == "01632 960691" }
        assertEquals("2 calls stopped · last today 10:01", police.line)
        assertEquals("Blocked today · Scam call claiming to be the police", police.why)
        assertTrue(police.blocked)
        assertEquals("2 numbers · 3 calls stopped in the last 30 days", view.stoppedLine)

        // Unblock from Activity: the row stays, saying so, with Block again.
        assertTrue(c.unblockCaller(police.key))
        val after = c.blockedCallers.value.stopped.single { it.key == police.key }
        assertFalse(after.blocked)
        assertEquals(os.meka.core.domain.StoppedCallRules.UNBLOCKED_LINE, after.why)
        // Unblocked, a call rings and isn't counted.
        assertTrue(c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals.NONE).rings)
        assertEquals(2L, c.blockedCallers.value.stopped.single { it.key == police.key }.count)

        assertTrue(c.reblockCaller(police.key))
        assertFalse(c.reblockCaller(police.key))
        val back = c.blockedCallers.value.stopped.single { it.key == police.key }
        assertTrue(back.blocked)
        assertEquals("Blocked today · Scam call claiming to be the police", back.why)
        assertEquals(CallVerdict.BLOCK, c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals.NONE).verdict)

        // After 30 days without a call the row goes.
        now += 31 * 86_400_000L
        c.blockCaller("07700 900888", null)
        assertTrue(c.blockedCallers.value.stopped.isEmpty())
    }
}
