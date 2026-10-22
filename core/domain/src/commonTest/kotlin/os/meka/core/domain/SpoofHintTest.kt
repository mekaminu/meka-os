package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spoofed-number hint (scam call fix, slice 4): a number in the same exchange as an official switchboard gets
 * "Could be spoofed" on the in-call warning. Invented numbers only (Ofcom's drama range, 01632 960xxx).
 */
class SpoofHintTest {
    private val t = 1_791_360_000_000L
    private val typed = SpoofHintRules.typedLines("police 01632 960000, bank 0306 999 0000")

    @Test
    fun aNumberInTheSameExchangeAsAListedSwitchboardLooksLikeIt() {
        val exact = assertNotNull(SpoofHintRules.lookalike("+44 1632 960000", typed))
        assertTrue(exact.exact)
        assertEquals("your police force's number", exact.line.whose)
        assertEquals(OfficialClaim.POLICE, exact.line.claim)
        val near = assertNotNull(SpoofHintRules.lookalike("01632 961234", typed))
        assertFalse(near.exact)
        assertEquals("Could be spoofed · looks like your police force's number", near.title)
        assertEquals("your bank's number", SpoofHintRules.lookalike("03069990999", typed)?.line?.whose)
        // A different exchange, a mobile, a withheld or foreign number: nothing.
        assertNull(SpoofHintRules.lookalike("01632 970000", typed))
        assertNull(SpoofHintRules.lookalike("07700 900123", typed))
        assertNull(SpoofHintRules.lookalike(null, typed))
        assertNull(SpoofHintRules.lookalike("+1 415 555 0100", typed))
    }

    @Test
    fun theNationalSwitchboardsScammersCopyNeedNothingTyped() {
        assertEquals("the Met Police's switchboard", SpoofHintRules.lookalike("020 7230 1212")?.line?.whose)
        assertEquals("the Met Police's switchboard", SpoofHintRules.lookalike("+442072304000")?.line?.whose)
        assertEquals("HMRC's number", SpoofHintRules.lookalike("0300 200 3500")?.line?.whose)
        // Action Fraud's exchange is shared by other public bodies: only its own number counts.
        assertEquals("Action Fraud's number", SpoofHintRules.lookalike("0300 123 2040")?.line?.whose)
        assertNull(SpoofHintRules.lookalike("0300 123 1231"))
    }

    @Test
    fun aboutYouKeepsTheOfficialNumbersTidyAndRefusesNonsense() {
        assertEquals("police 01632 960000, bank 0306 999 0000", SpoofHintRules.cleanTyped("Police 01632960000; bank +44 306 999 0000"))
        assertEquals("01632 960000", SpoofHintRules.cleanTyped("01632 960000"))
        assertNull(SpoofHintRules.cleanTyped("police"))
        assertNull(SpoofHintRules.cleanTyped("police 123"))
        assertNull(SpoofHintRules.cleanTyped("police +1 415 555 0100"))
        assertNull(SpoofHintRules.cleanTyped((1..9).joinToString(", ") { "01632 96000$it" }))
        assertEquals("", AboutYouRules.clean(AboutField.OFFICIAL_NUMBERS, "  "))
        assertEquals(AboutYouRules.NOT_OFFICIAL, AboutYouRules.refusal(AboutField.OFFICIAL_NUMBERS))
        // A line typed with no word is still watched, named as one he listed.
        assertEquals("a number you listed as official", SpoofHintRules.typedLines("01632 960000").single().whose)
    }

    @Test
    fun theWarningSaysCouldBeSpoofedEvenForASavedContact() {
        val lists = PeopleLists(family = setOf("Alex")).withNumber("Alex", "01632 960000")
        val spoof = SpoofHintRules.lookalike("01632 960001", typed)
        fun decided(number: String, signals: CallSignals) =
            CallScreeningRules.decide(true, false, number, lists, emptyList(), t, signals = signals)
                .let { it.copy(spoof = SpoofHintRules.lookalike(number, typed)) }

        // Saved as a contact (a spoofed bank number shows the bank's name): still warned, and the title says why.
        val contact = CallSignals(knownContact = true)
        assertTrue(InCallWarningRules.shouldWarn(decided("01632 960001", contact), contact))
        val w = InCallWarningRules.warning(contact, withheld = false, spoof = spoof)
        assertEquals("Could be spoofed · looks like your police force's number", w.title)
        assertTrue(w.big.startsWith("Could be spoofed: this looks like your police force's number"))
        assertTrue(w.big.contains(InCallWarningRules.ADVICE))
        assertTrue(w.spoken.startsWith("Careful: this number looks like your police force's number"))
        // A claimed body keeps its title and adds the hint.
        val claimed = CallSignals(networkName = "POLICE")
        val c = InCallWarningRules.warning(claimed, withheld = false, spoof = spoof)
        assertEquals("Says it's the police · unknown number", c.title)
        assertTrue(c.big.startsWith("Could be spoofed"))
        // Family (even on a number like a switchboard's), the household and his call assistant: never.
        assertFalse(InCallWarningRules.shouldWarn(decided("01632 960000", CallSignals.NONE), CallSignals.NONE))
        val house = CallSignals(household = true)
        assertFalse(InCallWarningRules.shouldWarn(decided("01632 960002", house), house))
        // Without a lookalike a contact is never warned (slice 2 unchanged).
        assertFalse(InCallWarningRules.shouldWarn(CallScreeningRules.decide(true, false, "01632 970000", lists, emptyList(), t, signals = contact), contact))
    }
}
