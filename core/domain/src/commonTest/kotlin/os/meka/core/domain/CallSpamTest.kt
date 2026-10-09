package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spam call protection (build plan "Call assistant live — polish" 8b): the decision table, the block list, contact numbers. */
class CallSpamTest {
    private val t = 1_791_360_000_000L
    private val lists = PeopleLists(family = setOf("Jeanette"), alwaysNotify = setOf("Ada"))
        .withNumber("Jeanette", "+447700900111")
        .withNumber("Ada", "07700 900222")
    private val scam = "01904 618691"
    private val blocked = setOf(BlockedCallerRules.keyOf(scam)!!)

    private fun decide(
        number: String?, on: Boolean = true, atWork: Boolean = false, signals: CallSignals = CallSignals.NONE,
        recent: List<ScreenedCall> = emptyList(), block: Set<String> = blocked,
    ) = CallScreeningRules.decide(on, atWork, number, lists, recent, t, block, signals)

    @Test
    fun aBlockedNumberIsRejectedAnyTimeEvenWithTheAssistantOff() {
        for (on in listOf(true, false)) for (atWork in listOf(true, false)) {
            val d = decide("+441904618691", on = on, atWork = atWork)
            assertEquals(CallVerdict.BLOCK, d.verdict, "on=$on atWork=$atWork")
            assertEquals(CallReason.BLOCKED, d.reason)
        }
        // Written any way, it is the same number.
        assertEquals(CallVerdict.BLOCK, decide("01904618691").verdict)
    }

    @Test
    fun familyListedContactsAndPeopleMekaCalledAreNeverBlockedOrTreatedAsSpam() {
        val mine = setOf(CallScreeningRules.callerKey("07700900111"), CallScreeningRules.callerKey("07700900222"), CallScreeningRules.callerKey("07700900333"))
        assertEquals(CallReason.OFF_WORK, decide("07700 900111", block = mine).reason)
        assertEquals(CallReason.OFF_WORK, decide("+447700900222", block = mine, signals = CallSignals(verificationFailed = true)).reason)
        assertTrue(decide("07700900333", block = mine, signals = CallSignals(knownContact = true)).rings)
        assertTrue(decide("07700900333", block = mine, signals = CallSignals(calledRecently = true, verificationFailed = true)).rings)
        assertEquals(CallVerdict.BLOCK, decide("07700900333", block = mine).verdict)
        // At work family still rings as family.
        assertEquals(CallReason.FAMILY, decide("07700900111", atWork = true, block = mine).reason)
    }

    @Test
    fun aNumberThatFailedTheCallerCheckGoesToTheAssistantAnyTimeWhileItIsOn() {
        val off = decide("01632 960000", atWork = false, signals = CallSignals(verificationFailed = true))
        assertEquals(CallVerdict.DECLINE, off.verdict)
        assertEquals(CallReason.LIKELY_SPAM, off.reason)
        assertTrue(CallScreeningRules.remembers(off))
        // A second call doesn't get through on the repeat rule: a spoofer redials.
        val again = decide("01632 960000", signals = CallSignals(verificationFailed = true), recent = listOf(ScreenedCall(off.callerKey, t - 60_000)))
        assertEquals(CallReason.LIKELY_SPAM, again.reason)
        // Assistant off: it rings as usual.
        assertTrue(decide("01632 960000", on = false, signals = CallSignals(verificationFailed = true)).rings)
    }

    @Test
    fun aWithheldNumberInQuietHoursGoesToTheAssistantUnlessItCallsAgain() {
        val first = decide(null, signals = CallSignals(quietHours = true))
        assertEquals(CallReason.WITHHELD_QUIET, first.reason)
        val recent = CallScreeningRules.remember(emptyList(), first, t - 60_000)
        assertTrue(decide(null, signals = CallSignals(quietHours = true), recent = recent).rings)
        assertEquals(CallReason.REPEAT, decide(null, atWork = true, signals = CallSignals(quietHours = true), recent = recent).reason)
        // Outside quiet hours and off work it rings; a withheld number can never be on the block list.
        assertEquals(CallReason.OFF_WORK, decide(null).reason)
        assertNull(BlockedCallerRules.keyOf(null))
        assertNull(BlockedCallerRules.keyOf("Withheld"))
        assertNull(BlockedCallerRules.keyOf("123"))
    }

    @Test
    fun theUsualWorkRulesStillApplyToEveryoneElse() {
        assertEquals(CallReason.AT_WORK, decide("07700 900999", atWork = true).reason)
        assertEquals(CallReason.SWITCHED_OFF, decide("07700 900999", on = false, atWork = true).reason)
    }

    @Test
    fun activityLinesSayWhatHappenedAndWhy() {
        val (summary, why) = CallScreeningRules.activityLine(decide("+441904618691"), "+441904618691")!!
        assertEquals("Blocked a call from 01904 618691", summary)
        assertEquals("It is on your block list", why)
        assertEquals("Sent a withheld call to the assistant", CallScreeningRules.activityLine(decide(null, signals = CallSignals(quietHours = true)), null)!!.first)
        assertNull(CallScreeningRules.activityLine(decide("07700 900999", atWork = true), "07700 900999"))
    }

    @Test
    fun numbersShowTheWayTheyAreWritten() {
        assertEquals("01904 618691", BlockedCallerRules.display("+441904618691"))
        assertEquals("07700 900123", BlockedCallerRules.display("07700900123"))
        assertEquals("020 7946 0000", BlockedCallerRules.display("+44 20 7946 0000"))
        assertEquals("0113 496 0000", BlockedCallerRules.display("01134960000"))
        assertEquals("+1 212 555 0100", BlockedCallerRules.display(" +1 212  555 0100 "))
    }

    @Test
    fun theBlockListSyncsAndAnUnblockedSeedIsNeverAddedBack() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val onFold = BlockedCallers(fold.replica, { world.clock.nowMs })
        val onMac = BlockedCallers(mac.replica, { world.clock.nowMs })

        assertTrue(onFold.seed())
        assertFalse(onFold.seed())
        assertEquals("1 blocked number · rejected silently, any time", onFold.view().line)
        assertEquals("01904 618691", onFold.view().rows.single().number)
        assertTrue(onFold.view().rows.single().line.endsWith(BlockedCallerRules.SEED_WHY))
        assertFalse(onFold.block("not a number", null))
        assertTrue(onFold.block("+44 7700 900555", "Kept ringing"))
        fold.sync(); mac.sync()
        assertEquals(2, onMac.view().rows.size)
        assertEquals(onFold.view().keys, onMac.view().keys)

        assertTrue(onMac.unblock(BlockedCallerRules.keyOf(BlockedCallerRules.SEED_NUMBER)!!))
        assertFalse(onMac.unblock("tel:0000000000"))
        mac.sync(); fold.sync()
        assertEquals(listOf("07700 900555"), onFold.view().rows.map { it.number })
        assertFalse(onFold.seed())
        assertEquals(listOf("07700 900555"), onFold.view().rows.map { it.number })
    }

    @Test
    fun aContactsNumbersAreAllKeptAndA07CallerMatchesTheStoredPlus44() {
        assertEquals("+447700900111", ContactNumbers.e164("07700 900111"))
        assertEquals("+447700900111", ContactNumbers.e164("0044 7700 900111"))
        assertEquals("+12125550100", ContactNumbers.e164("+1 (212) 555-0100"))
        assertNull(ContactNumbers.e164(""))
        assertNull(ContactNumbers.e164("12"))
        assertNull(ContactNumbers.e164("Mum mobile"))

        val l = PeopleLists(family = setOf("Jeanette")).withNumbers("Jeanette", listOf("07700 900444", "+44 7700 900444", null, "01767 600000"))
        assertEquals(setOf("+447700900444", "+441767600000"), l.numbers["Jeanette"])
        assertEquals("Jeanette", l.nameForNumber("07700900444"))
        assertEquals("Jeanette", l.nameForNumber("01767 600000"))
        assertFalse(PeopleLists(family = setOf("Jeanette")).withNumbers("Jeanette", listOf(null, "")).hasNumber("Jeanette"))
    }

    private fun voice(from: String, at: Long = t, family: Boolean = false) =
        CapturedItem("v$from$at", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, from, "Your account is suspended", null, at, family = family)

    @Test
    fun onlyACallerNobodyKnowsGetsBlockAndReport() {
        val names = CallerNames({ n -> if (People.key(n) == People.key("07700900333")) "Tunde" else null }, ownNumbers = setOf("07700 900999"))
        val s = AfterWorkSummaries.build(
            listOf(voice("+441904618691"), voice("+447700900111"), voice("+447700900222"), voice("+447700900333"), voice("07700900999"),
                CapturedItem("m1", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Bola", "hi", null, t)),
            lists, names,
        )
        val by = s.people.associate { it.personName to it.blockNumber }
        assertEquals("+441904618691", by["01904 618691"])
        assertNull(by["Jeanette"]); assertNull(by["Ada"]); assertNull(by["Tunde"]); assertNull(by[CallerNames.TEST_CALL]); assertNull(by["Bola"])
        // The Mac (no lists, no contacts) still knows the number, and a family flag from the Fold keeps Block off.
        val mac = AfterWorkSummaries.build(listOf(voice("+441904618691"), voice("+447700900111", family = true)), PeopleLists())
        assertEquals("+441904618691", mac.people.single { !it.isFamily }.blockNumber)
        assertNull(mac.people.single { it.isFamily }.blockNumber)
        // Re-applying the Fold's lists to a synced summary keeps the caller's number.
        assertEquals("+441904618691", mac.withLists(lists, names).people.first { it.personName == "01904 618691" }.blockNumber)
        // A withheld caller can't be blocked.
        assertNull(AfterWorkSummaries.build(listOf(voice("Unknown")), PeopleLists()).people.single().blockNumber)
    }

    @Test
    fun reportingToSevenSevenTwoSixAndWhyItWasBlocked() {
        assertEquals("Call 01904618691", BlockedCallerRules.reportText("+44 1904 618691"))
        assertEquals("Call 07700900123", BlockedCallerRules.reportText("07700 900123"))
        assertNull(BlockedCallerRules.reportText("Unknown"))
        assertEquals("To report it, text \u201cCall 01904618691\u201d to 7726 from your phone.", BlockedCallerRules.macReportHint("01904618691"))
        val cal = LocalCalendar.UTC
        assertEquals("Left a message · today", BlockedCallerRules.whyFromHeld(CaptureKind.VOICE_MESSAGE, t, t + 60_000, cal))
        assertEquals("Missed call · yesterday", BlockedCallerRules.whyFromHeld(CaptureKind.MISSED_CALL, t - 86_400_000, t, cal))
        val world = SyncWorld()
        val d = world.device("fold")
        val list = BlockedCallers(d.replica, { world.clock.nowMs })
        assertFalse(list.view().has("01904 618691"))
        assertTrue(list.block("+441904618691", "Left a message · today"))
        assertTrue(list.view().has("01904 618691"))
        assertFalse(list.view().has(null))
        assertEquals(3, CallScreeningRules.ONE_SCREENER_LINES.size)
        assertTrue(CallScreeningRules.ONE_SCREENER_LINES.last().contains("Caller ID & spam app"))
    }
}
