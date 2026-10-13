package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spam calls still ring, slice 1: official claims, the repeat-caller rule, the Health row and the household's numbers. */
class SpamProtectionTest {
    @Test
    fun aNetworkNameClaimingAnOfficialBodyIsSpotted() {
        assertEquals(OfficialClaim.POLICE, OfficialCallerRules.claim("Bedfordshire Police"))
        assertEquals(OfficialClaim.POLICE, OfficialCallerRules.claim("ACTION FRAUD"))
        assertEquals(OfficialClaim.BANK, OfficialCallerRules.claim("Barclays Fraud Dept"))
        assertEquals(OfficialClaim.NHS, OfficialCallerRules.claim("Oakfield Surgery"))
        assertEquals(OfficialClaim.SCHOOL, OfficialCallerRules.claim("Oakfield School"))
        assertEquals(OfficialClaim.GOVERNMENT, OfficialCallerRules.claim("HMRC"))
        assertNull(OfficialCallerRules.claim("Alex Smith"))
        assertNull(OfficialCallerRules.claim(""))
        assertNull(OfficialCallerRules.claim(null))
        // Whole words only: "Banksy" isn't a bank.
        assertNull(OfficialCallerRules.claim("Banksy Prints"))
        assertEquals(
            "Calls claiming to be the police can be spoofed · call 101 to check, not this number back",
            OfficialCallerRules.headsUp(OfficialClaim.POLICE),
        )
        assertTrue(OfficialCallerRules.isEmergency("999"))
        assertTrue(OfficialCallerRules.isEmergency(" 111 "))
        assertFalse(OfficialCallerRules.isEmergency("01632 960999"))
        assertFalse(OfficialCallerRules.isEmergency(null))
    }

    @Test
    fun anOfficialClaimsHeadsUpReplacesBlockIt() {
        val t = 1_791_590_400_000L
        val o = UnknownCallRules.offer("01632 960555", CallOutcome.ANSWERED, 95, t, t + 60_000, emptySet(), emptyList(), LocalCalendar.UTC, OfficialClaim.BANK)!!
        assertTrue(o.line.endsWith(" · Calls claiming to be a bank can be spoofed · call the number on the back of your card, not this one"), o.line)
        assertTrue(UnknownCallRules.offer("01632 960555", CallOutcome.MISSED, 0, t, t + 60_000, emptySet(), emptyList(), LocalCalendar.UTC)!!.line.endsWith(" · Block it?"))
    }

    @Test
    fun twoCallsInAWeekWithoutAMessageMeansBlock() {
        assertFalse(AutoBlockRules.repeatCaller(1, leftVoiceMessage = false))
        assertTrue(AutoBlockRules.repeatCaller(2, leftVoiceMessage = false))
        assertTrue(AutoBlockRules.repeatCaller(5, leftVoiceMessage = false))
        assertFalse(AutoBlockRules.repeatCaller(5, leftVoiceMessage = true))
        assertEquals("Blocked 01632 960123 · it won't ring again", AutoBlockRules.blockedLine("+441632960123"))
        val tue = 1_791_590_400_000L // Tue 13 Oct 2026 (UTC)
        assertEquals(
            "Auto-blocked · failed the network's caller check · " + CivilDate.shortLabel(LocalCalendar.UTC.epochDayOf(tue)),
            AutoBlockRules.why(AutoBlockTrigger.NETWORK_CHECK, tue, LocalCalendar.UTC),
        )
    }

    @Test
    fun healthShowsSpamCallsRedWhenTheRoleIsLost() {
        val lost = SpamProtectionRules.healthRow(roleHeld = false, protectionOn = true, mac = false)!!
        assertEquals(HealthState.BAD, lost.state)
        assertEquals(HealthFix.CALL_ROLE, lost.fix)
        assertEquals("Fix", lost.fixLabel)
        assertEquals("can ring · Navilife isn't your Caller ID & spam app", lost.line)
        assertEquals(HealthState.WARN, SpamProtectionRules.healthRow(true, protectionOn = false, mac = false)!!.state)
        assertEquals(HealthState.OK, SpamProtectionRules.healthRow(true, protectionOn = true, mac = false)!!.state)
        assertNull(SpamProtectionRules.healthRow(false, protectionOn = true, mac = true))
        assertNull(SpamProtectionRules.healthRow(null, protectionOn = true, mac = false))
    }

    @Test
    fun theHouseholdProfilesNumbersAreKeys() {
        val a = AboutYou.EMPTY.copy(partnerPhone = "07700 900111", assistantNumber = "+44 1632 960000", voicemailNumber = "not a number")
        assertEquals(
            setOf(CallScreeningRules.callerKey("07700900111"), CallScreeningRules.callerKey("01632 960000")),
            SpamProtectionRules.householdKeys(a),
        )
    }
}
