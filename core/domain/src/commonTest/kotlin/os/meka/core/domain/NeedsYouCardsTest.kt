package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mac presentation pass, items 6 and 7: one strong line per card, numbers spaced, likely scams set aside, the chip. */
class NeedsYouCardsTest {
    private val rules = NeedsYouCardRules

    private fun card(
        from: String, gist: String, lane: TriageLane = TriageLane.FYI, group: String? = null, askedBy: String? = null,
        app: CaptureApp? = CaptureApp.SMS, at: Long = 1L, id: String = from,
    ) = TriageCard(id, lane, People.key(from), from, gist, null, app, group != null, at, "spoken", group, askedBy)

    @Test
    fun aNumberIsWrittenTheWayPeopleWriteIt() {
        assertEquals("07700 900123", rules.who("+447700900123"))
        assertEquals("07700 900123", rules.who("07700900123"))
        assertEquals("020 7946 0000", rules.who("02079460000"))
        assertEquals("Tunde", rules.who(" Tunde "))
        assertEquals("JPMC Alert", rules.who("JPMC Alert"))
    }

    @Test
    fun aTriagedTextFromANumberShowsTheNumberSpaced() {
        val world = SyncWorld()
        val fold = world.device("android")
        val cards = TriageCards(fold.replica, { world.clock.nowMs }, LocalCalendar.UTC)
        val item = CapturedItem("sms-1", CaptureApp.SMS, CaptureKind.MESSAGE, "+447700900123", "hi", null, world.clock.nowMs)
        val c = cards.save(item, MessageTriage(TriageLane.FYI, summary = "Says hello"))!!
        assertTrue(c.from.startsWith("07700 900123 · "), c.from)
        assertEquals("07700 900123", rules.number(c))
    }

    @Test
    fun oneStrongLineAndAMutedOne() {
        val c = card("Tunde · 14:02", "Asks if you're coming Saturday.", TriageLane.NEEDS_REPLY, app = CaptureApp.WHATSAPP)
        assertEquals("Tunde · Asks if you're coming Saturday", rules.headline(c))
        assertEquals("14:02 · Needs a reply · WhatsApp", rules.meta(c))
        val g = card("Femi in Barça lads · 14:04", "Kick-off time", group = "Barça lads", app = CaptureApp.WHATSAPP)
        assertEquals("Femi · Kick-off time", rules.headline(g))
        assertEquals("in Barça lads · 14:04 · FYI · WhatsApp", rules.meta(g))
        assertNull(rules.number(g))
    }

    @Test
    fun theAisScamVerdictKeepsATextOutOfNeedsYou() {
        val scam = card("JPMC Alert · 09:12", "Unverified 'JPMC Alert' asks you to confirm a payment; likely a scam")
        val friend = card("Tunde · 14:02", "Asks if you're coming Saturday", TriageLane.NEEDS_REPLY)
        assertTrue(rules.likelyScam(scam))
        assertFalse(rules.likelyScam(friend))
        assertEquals(listOf(friend), rules.shown(listOf(scam, friend)))
        assertEquals(listOf(scam), rules.scams(listOf(scam, friend)))
        assertNull(rules.number(scam))
        assertEquals("LIKELY SCAMS · 1", rules.scamHeading(1))
        assertEquals("Deleted the likely scam from JPMC Alert", rules.deletedLine(scam))
    }

    @Test
    fun aStrangersNumberWithAScamPhraseIsSetAsideButANamedSenderIsNot() {
        val parcel = card("07700 900123 · 10:00", "Says your parcel is held and asks for a redelivery fee")
        assertTrue(rules.likelyScam(parcel))
        assertEquals("07700 900123", rules.number(parcel))
        val mum = card("Sam · 10:00", "Says congratulations on the new job")
        assertFalse(rules.likelyScam(mum))
    }

    @Test
    fun aGroupOrAWatchedAskOrNotAScamIsNeverSetAside() {
        assertFalse(rules.likelyScam(card("Femi in Lads · 14:04", "Jokes the ref is a scam", group = "Lads")))
        assertFalse(rules.likelyScam(card("Alex · 14:04", "Says the email was not a scam, it's from school")))
        assertFalse(rules.likelyScam(card("Alex · 14:04", TriageCard.LOCAL_LINE)))
    }

    @Test
    fun theTimelineAsksWithAChipAndThePlaceOnItsOwnLine() {
        val ask = ClubChildRules.ASK
        assertTrue(ClubChildRules.asking(ask))
        assertTrue(ClubChildRules.asking("$ask · Oakfield School"))
        assertFalse(ClubChildRules.asking("Alex · Oakfield School"))
        assertFalse(ClubChildRules.asking(null))
        assertEquals("Oakfield School", ClubChildRules.withoutAsk("$ask · Oakfield School"))
        assertNull(ClubChildRules.withoutAsk(ask))
        assertEquals("Oakfield School", ClubChildRules.withoutAsk("Oakfield School"))
        assertEquals("Whose is it? ›", ClubChildRules.WHOSE)
    }
}
