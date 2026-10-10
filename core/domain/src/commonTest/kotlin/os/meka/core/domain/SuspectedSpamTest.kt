package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Suspected spam (call assistant polish 8b c): the AI's flag, the list, Block · Not spam, and screening. */
class SuspectedSpamTest {
    private val t = 1_791_360_000_000L
    private val lists = PeopleLists(family = setOf("Jeanette")).withNumber("Jeanette", "+447700900111")
    private val scamKey = BlockedCallerRules.keyOf("+441904618691")!!

    private fun decide(number: String?, on: Boolean = true, atWork: Boolean = false, signals: CallSignals = CallSignals.NONE, recent: List<ScreenedCall> = emptyList()) =
        CallScreeningRules.decide(on, atWork, number, lists, recent, t, emptySet(), signals, suspected = setOf(scamKey, CallScreeningRules.callerKey("07700900111"), CallScreeningRules.callerKey("07700900333")))

    @Test
    fun aSuspectGoesToTheAssistantAnyTimeWhileItIsOnButNeverFamilyContactsOrPeopleMekaCalled() {
        val d = decide("01904 618691")
        assertEquals(CallVerdict.DECLINE, d.verdict)
        assertEquals(CallReason.SUSPECTED_SPAM, d.reason)
        assertEquals(CallReason.SUSPECTED_SPAM, decide("01904618691", atWork = true).reason)
        // A redial within 3 minutes still goes to the assistant.
        assertEquals(CallReason.SUSPECTED_SPAM, decide("01904 618691", recent = CallScreeningRules.remember(emptyList(), d, t - 60_000)).reason)
        // Assistant off: it rings (the AI never blocks anything).
        assertTrue(decide("01904 618691", on = false).rings)
        assertEquals(CallReason.OFF_WORK, decide("07700 900111").reason)
        assertTrue(decide("07700900333", signals = CallSignals(knownContact = true)).rings)
        assertTrue(decide("07700900333", signals = CallSignals(calledRecently = true)).rings)
        // Its Activity line, and the phone still offers "Unknown caller · Block?" afterwards.
        val (summary, why) = CallScreeningRules.activityLine(d, "+441904618691")!!
        assertEquals("Sent a call from 01904 618691 to the assistant", summary)
        assertTrue(why.startsWith("It is on Suspected spam"))
        assertTrue(UnknownCallRules.watch(d, "+441904618691", CallSignals.NONE))
    }

    @Test
    fun theServerAsksOnlyAboutANewNumberWithRealWords() {
        val words = "This is the police, you owe money"
        assertTrue(SuspectedSpamRules.shouldCheck(scamKey, null, null, words))
        assertTrue(SuspectedSpamRules.shouldCheck(scamKey, false, null, words)) // unblocked since
        assertFalse(SuspectedSpamRules.shouldCheck(null, null, null, words)) // withheld
        assertFalse(SuspectedSpamRules.shouldCheck(scamKey, true, null, words)) // already blocked
        assertFalse(SuspectedSpamRules.shouldCheck(scamKey, null, true, words)) // already a suspect
        assertFalse(SuspectedSpamRules.shouldCheck(scamKey, null, false, words)) // Meka said Not spam
        assertFalse(SuspectedSpamRules.shouldCheck(scamKey, null, null, "Of the"))
        assertFalse(SuspectedSpamRules.shouldCheck(scamKey, null, null, null))
    }

    @Test
    fun theReasonIsOneTidyLine() {
        assertEquals("Claims to be the police and asks for payment", SuspectedSpamRules.cleanWhy("  Claims to be the police\nand asks for payment. "))
        assertEquals("Says click now", SuspectedSpamRules.cleanWhy("Says <click> now"))
        assertNull(SuspectedSpamRules.cleanWhy("Visit https://pay.example now"))
        assertNull(SuspectedSpamRules.cleanWhy("   "))
        assertEquals(SuspectedSpamRules.MAX_WHY, SuspectedSpamRules.cleanWhy("x".repeat(200))!!.length)
        assertEquals("Suspected scam · Claims to be HMRC", SuspectedSpamRules.blockWhy("Claims to be HMRC"))
        assertEquals("Suspected scam", SuspectedSpamRules.blockWhy(null))
        val a = SuspectedSpamRules.activity("+441904618691", "Claims to be the police", t)
        assertEquals(FieldValue.Text("Added 01904 618691 to Suspected spam"), a[ActivityFields.SUMMARY])
        assertEquals(FieldValue.Text("Claims to be the police"), a[ActivityFields.DETAIL])
        assertEquals(FieldValue.Text(ActivityKind.CALL.name), a[ActivityFields.KIND])
        assertEquals(SuspectedSpamRules.activityId("h1"), SuspectedSpamRules.activityId("h1"))
        assertTrue(SuspectedSpamRules.activityId("h1") != SuspectedSpamRules.activityId("h2"))
    }

    @Test
    fun aFlagShowsOnBothDevicesAndBlockOrNotSpamAnswersIt() {
        val world = SyncWorld()
        val server = world.device("server")
        val fold = world.device("fold")
        val mac = world.device("mac")
        val onFold = BlockedCallers(fold.replica, { world.clock.nowMs })
        val onMac = BlockedCallers(mac.replica, { world.clock.nowMs })

        // The server flags two numbers (as CallAssistant writes them).
        server.replica.commitLocal(EntityTypes.BLOCKED_CALLER, scamKey, SuspectedSpamRules.flagFields("+441904618691", "Claims to be the police", world.clock.nowMs))
        val other = BlockedCallerRules.keyOf("+447700900555")!!
        server.replica.commitLocal(EntityTypes.BLOCKED_CALLER, other, SuspectedSpamRules.flagFields("+447700900555", null, world.clock.nowMs))
        server.sync(); fold.sync(); mac.sync()

        val v = onFold.view()
        assertEquals(setOf(scamKey, other), v.suspectedKeys)
        assertTrue(v.rows.isEmpty()) // a suspect isn't blocked
        assertEquals("Flagged today · Claims to be the police", v.suspects.first { it.key == scamKey }.line)
        assertEquals("07700 900555", v.suspects.first { it.key == other }.number)
        assertEquals("Flagged today", v.suspects.first { it.key == other }.line)

        // Block on the Fold: on the block list with the reason, off Suspected spam, on the Mac too.
        assertTrue(onFold.confirmSuspect(scamKey))
        assertFalse(onFold.confirmSuspect(scamKey))
        fold.sync(); mac.sync()
        assertEquals(listOf("01904 618691"), onMac.view().rows.map { it.number })
        assertTrue(onMac.view().rows.single().line.endsWith("Suspected scam · Claims to be the police"))
        assertEquals(setOf(other), onMac.view().suspectedKeys)

        // Not spam on the Mac: gone from the list on both, not blocked.
        assertTrue(onMac.dismissSuspect(other))
        assertFalse(onMac.dismissSuspect(other))
        assertFalse(onMac.dismissSuspect("tel:0000000000"))
        mac.sync(); fold.sync()
        assertTrue(onFold.view().suspects.isEmpty())
        assertFalse(onFold.view().has("+447700900555"))
    }
}
