package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailScamTest {
    private fun sort(from: String?, name: String?, subject: String?, labels: List<String> = listOf("INBOX"), unsub: String? = null) =
        MailSortRules.sort(MailSortFacts(from, name, subject, labels, unsub, null))

    @Test
    fun aCompanyNamedFromAnotherDomainWithAThreatIsAPossibleScam() {
        val s = sort("service@mail-notify.example", "Notification via PayPal", "Your account will be closed")
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.SCAM, true), s)
        val f = MailScamRules.check("service@mail-notify.example", "Notification via PayPal", "Your account will be closed")!!
        assertEquals("⚠ Possible scam · sent from mail-notify.example, not paypal.com", MailScamRules.line(f))
        assertEquals(MailScamRules.line(f), MailScamRules.itemWarning(MailSortReason.SCAM, "service@mail-notify.example", "Notification via PayPal", "Your account will be closed"))
        // The company named only in the subject counts too; a free mailbox is never the company's.
        assertEquals(MailSortReason.SCAM, sort("alex.help@gmail.com", "Support", "Netflix: payment failed, update your payment").reason)
        assertEquals(MailSortReason.SCAM, sort("support@outlook.com", "Microsoft account team", "Verify your account").reason)
    }

    @Test
    fun theSameWordsFromTheRealDomainSayLogInYourself() {
        val s = sort("service@paypal.co.uk", "PayPal", "Your account is limited")
        assertEquals(MailSortReason.BRAND_NOTICE, s.reason)
        assertEquals(MailSort.NEEDS_YOU, s.sort)
        assertEquals("From PayPal's real address · log in at paypal.com yourself, not through the email's link",
            MailScamRules.itemWarning(s.reason, "service@paypal.co.uk", "PayPal", "Your account is limited"))
        // The real company's marketing sorts as usual.
        assertEquals(MailSort.PROMO, sort("offers@netflix.com", "Netflix", "Final notice: 50% off", unsub = "<https://x>").sort)
    }

    @Test
    fun noThreatOrNoCompanyIsNotAScam() {
        assertNull(MailScamRules.check("orders@shop.example", "PayPal Here", "Thanks for shopping"))
        assertNull(MailScamRules.check("sam@example.com", "Sam", "Your account will be closed"))
        assertEquals("", MailScamRules.itemWarning(MailSortReason.PERSON, "sam@example.com", "Sam", "Hi"))
        assertFalse(MailScamRules.scamLine("From PayPal's real address · …"))
        assertTrue(MailScamRules.scamLine("⚠ Possible scam · …"))
    }

    @Test
    fun prizeDrawsArePromosAndDigestsNotifications() {
        assertEquals(MailSorted(MailSort.PROMO, MailSortReason.PRIZE, true), sort("hello@prizes.example", "Prize Club", "4 HOURS LEFT to enter the draw"))
        assertEquals(MailSortReason.PRIZE, sort("news@lotto.example", "Lotto", "You've won a prize!").reason)
        assertEquals(MailSorted(MailSort.NOTIFICATION, MailSortReason.DIGEST, true), sort("team@clubfees.example", "Club Fees", "Your weekly activity digest"))
        // A person's ordinary email stays theirs.
        assertEquals(MailSortReason.PERSON, sort("sam@example.com", "Sam", "Are you free Saturday?").reason)
        assertEquals(MailSortReason.PERSON, sort("sam@example.com", "Sam", "You won't believe this").reason)
    }

    @Test
    fun aPrizeOrDigestFromSomeoneHeWritesToStaysHis() {
        val digest = sort("sam@example.com", "Sam", "Weekly summary of the project")
        assertEquals(MailSortReason.KNOWN_PERSON, MailSortRules.known(digest, "sam@example.com", setOf("sam@example.com")).reason)
        assertEquals(MailSortReason.DIGEST, MailSortRules.known(digest, "sam@example.com", emptySet()).reason)
    }

    @Test
    fun hisOwnAddressIsANoteToSelf() {
        val own = setOf("me@example.com")
        val s = MailSortRules.mine(sort("Me@Example.com", "Me", "https://example.com/article"), "Me@Example.com", own)
        assertEquals(MailSorted(MailSort.FYI, MailSortReason.OWN, true), s)
        assertEquals(MailTidy.KEEP, MailTidyRules.plan(MailTidyFacts(fromAddress = "me@example.com", inInbox = true, sorted = s)))
        // Spam and security sorts are left alone; someone else is unchanged.
        val spam = MailSorted(MailSort.JUNK, MailSortReason.SPAM, true)
        assertEquals(spam, MailSortRules.mine(spam, "me@example.com", own))
        assertEquals(MailSortReason.PERSON, MailSortRules.mine(sort("sam@example.com", "Sam", "Hi"), "sam@example.com", own).reason)
    }

    @Test
    fun alreadySortedNeedsYouIsSortedAgainOnlyForSomethingSureAndDifferent() {
        val ai = MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_NEEDS_YOU, true)
        val prize = MailSorted(MailSort.PROMO, MailSortReason.PRIZE, true)
        assertEquals(prize, MailScamRules.resort(ai, prize))
        val scam = MailSorted(MailSort.NEEDS_YOU, MailSortReason.SCAM, true)
        assertEquals(scam, MailScamRules.resort(MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false), scam))
        // A person stays a person; his correspondents, public bodies and FYI are left as they are.
        assertNull(MailScamRules.resort(ai, MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false)))
        assertNull(MailScamRules.resort(MailSorted(MailSort.NEEDS_YOU, MailSortReason.KNOWN_PERSON, true), prize))
        assertNull(MailScamRules.resort(MailSorted(MailSort.NEEDS_YOU, MailSortReason.PUBLIC_BODY, true), prize))
        assertNull(MailScamRules.resort(MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true), prize))
    }

    @Test
    fun theCardShowsTheWarning() {
        val fields = MailNeedsRules.serverFields("Personal", "Notification via PayPal", "service@mail-notify.example", "Your account will be closed",
            "Dear customer", 1_000L, 1, warning = "⚠ Possible scam · sent from mail-notify.example, not paypal.com")
        assertEquals(os.meka.core.sync.FieldValue.Text("⚠ Possible scam · sent from mail-notify.example, not paypal.com"), fields[MailItemFields.WARNING])
        assertNull(MailNeedsRules.serverFields("Personal", "Sam", "sam@example.com", "Hi", null, 1_000L, 1)[MailItemFields.WARNING])
    }
}
