package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailJunkTest {
    private fun v(
        from: String? = "someone@random.example", name: String? = null, subject: String? = "Hello",
        keep: Set<String> = emptySet(), replied: Boolean = false, known: Boolean = false,
    ) = MailJunkRules.verdict(MailJunkFacts(from, name, subject, keep, replied, known))

    @Test
    fun obviousScamsGoToTrash() {
        assertEquals(MailJunkVerdict.TRASH, v(subject = "Congratulations! You have WON £1,000,000"))
        assertEquals(MailJunkVerdict.TRASH, v(subject = "Your account has been suspended - verify your account"))
        assertEquals(MailJunkVerdict.TRASH, v(subject = "Bitcoin investment opportunity"))
        assertEquals(MailJunkVerdict.TRASH, v(subject = "Your parcel is held: pay the customs fee"))
        assertEquals(MailJunkVerdict.TRASH, v(name = "Lottery Desk", subject = "Hi"))
    }

    @Test
    fun aNameClaimingACompanyFromSomeoneElsesAddressIsAScam() {
        assertEquals(MailJunkVerdict.TRASH, v(from = "alerts@secure-pp-login.example", name = "PayPal Service", subject = "Notice"))
        assertEquals(MailJunkVerdict.TRASH, v(from = "noreply@hmrc-refunds.example", name = "HMRC", subject = "Notice"))
        assertEquals("claims to be royal mail", MailJunkRules.scamSignal("x@track-parcel.example", "Royal Mail", "Your item"))
        // Their own address: not a claim (and an unclear email stays put).
        assertEquals(MailJunkVerdict.LEAVE, v(from = "service@paypal.co.uk", name = "PayPal", subject = "Your receipt"))
        assertNull(MailJunkRules.scamSignal("hello@amazon.co.uk", "Amazon.co.uk", "Your order"))
        // A word that merely contains a name isn't a claim.
        assertNull(MailJunkRules.scamSignal("x@y.example", "Deep Applesauce", "Recipes"))
    }

    @Test
    fun hisPartnerAThreadHeWroteInAndKnownSendersAreRescued() {
        assertEquals(MailJunkVerdict.RESCUE, v(from = "Sam@Example.com", keep = setOf("sam@example.com"), subject = "Congratulations!"))
        assertEquals(MailJunkVerdict.RESCUE, v(replied = true, subject = "Re: the invoice"))
        assertEquals(MailJunkVerdict.RESCUE, v(known = true, subject = "Training on Saturday"))
        // Someone who writes to him but reads as a scam (a hacked account): never trashed, left in Junk.
        assertEquals(MailJunkVerdict.LEAVE, v(known = true, subject = "Gift card needed urgently, act now"))
    }

    @Test
    fun banksPublicBodiesAndSchoolsStayInJunk() {
        assertEquals(MailJunkVerdict.LEAVE, v(from = "alerts@barclays.co.uk", subject = "Your account has been suspended"))
        assertEquals(MailJunkVerdict.LEAVE, v(from = "office@oakfield.sch.uk", subject = "Trip letter"))
        assertEquals(MailJunkVerdict.LEAVE, v(from = "noreply@tax.service.gov.uk", subject = "Tax refund"))
        assertEquals(MailJunkVerdict.LEAVE, v(from = "alerts@barclays.co.uk", known = true, subject = "Statement"))
    }

    @Test
    fun anythingUnclearStaysPut() {
        assertEquals(MailJunkVerdict.LEAVE, v(subject = "Meeting notes"))
        assertEquals(MailJunkVerdict.LEAVE, v(from = null, subject = null))
        assertEquals(MailTidy.KEEP, MailJunkRules.tidyOf(MailJunkVerdict.LEAVE))
        assertEquals(MailTidy.JUNK_TRASH, MailJunkRules.tidyOf(MailJunkVerdict.TRASH))
        assertEquals(MailTidy.RESCUE, MailJunkRules.tidyOf(MailJunkVerdict.RESCUE))
    }

    @Test
    fun onceADay() {
        assertTrue(MailJunkRules.due(null, 1_000L))
        assertFalse(MailJunkRules.due(1_000L, 1_000L + MailJunkRules.REVIEW_EVERY_MS - 1))
        assertTrue(MailJunkRules.due(1_000L, 1_000L + MailJunkRules.REVIEW_EVERY_MS))
    }

    @Test
    fun theReviewIsOneTidyWithUndo() {
        val f = MailJunkRules.batchFields(5L, "Personal", mapOf(MailTidy.JUNK_TRASH to 3, MailTidy.RESCUE to 1))
        val b = MailAutopilotRules.readBatch("mt1", f)!!
        assertEquals("Junk checked · 3 to Trash · 1 rescued · Personal", b.summary)
        assertEquals("3 junk emails → Trash · 1 email rescued from Junk → inbox, flagged", b.detail)
        assertEquals(4, b.count)
        assertEquals(mapOf(MailTidy.JUNK_TRASH to 3, MailTidy.RESCUE to 1), b.kinds)
        assertTrue(b.junk)
        assertFalse(b.backlog)
        assertTrue(b.canUndo)
        assertEquals("1 junk email → Trash", MailJunkRules.lines(1, 0, "Business").second)
        assertEquals("Junk checked · 2 rescued · Business", MailJunkRules.lines(0, 2, "Business").first)
        assertEquals("→ Trash", MailTidy.JUNK_TRASH.destination)
        assertEquals("→ inbox, flagged", MailTidy.RESCUE.destination)
        assertEquals("Navilife/Rescued", MailTidy.RESCUE.label)
        assertTrue(MailJunkRules.batchId("acc", 1L).startsWith("mt"))
        assertEquals("Put back 3 emails in Junk", MailAutopilotRules.undoneNote(3, 3, "in Junk"))
    }

    @Test
    fun archivedForYouCountsTheReview() {
        val f = MailJunkRules.batchFields(5L, "Personal", mapOf(MailTidy.JUNK_TRASH to 2, MailTidy.RESCUE to 1))
        val b = MailAutopilotRules.readBatch("mt1", f)!!
        assertEquals("Tidied 3 · 2 junk emails · 1 email rescued from Junk", ArchivedForYouRules.headline(listOf(b)))
    }
}
