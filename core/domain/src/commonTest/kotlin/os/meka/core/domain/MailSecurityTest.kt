package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailSecurityTest {
    private fun sort(from: String?, subject: String?, labels: List<String> = listOf("INBOX", "CATEGORY_UPDATES"), unsubscribe: String? = null) =
        MailSortRules.sort(MailSortFacts(from, "Google", subject, labels, unsubscribe, null))

    @Test
    fun googleSecurityAlertsNeedYouAndAreSure() {
        for (subject in listOf("Security alert", "Critical security alert", "Your Google Account was recovered successfully",
            "2-Step Verification turned on", "New sign-in to your account", "Your password was changed")) {
            val s = sort("no-reply@accounts.google.com", subject)
            assertEquals(MailSort.NEEDS_YOU, s.sort, subject)
            assertEquals(MailSortReason.SECURITY, s.reason, subject)
            assertTrue(s.sure, subject)
        }
        assertEquals(MailSecurityKind.ALERT, MailSecurityRules.kind("account-security-noreply@accountprotection.microsoft.com", "Microsoft account unusual sign-in activity"))
    }

    @Test
    fun aCodeIsKeptAsFyi() {
        val s = sort("no-reply@accounts.google.com", "Your Google verification code")
        assertEquals(MailSort.FYI, s.sort)
        assertEquals(MailSortReason.SECURITY_CODE, s.reason)
    }

    @Test
    fun theSameWordsFromAnotherDomainAreNotTheProvider() {
        // A spoof (or anyone on a free mailbox) is not the provider: ordinary rules, and the scam rules in Junk.
        assertNull(MailSecurityRules.kind("security@g00gle-alerts.com", "Security alert"))
        assertNull(MailSecurityRules.kind("someone@outlook.com", "Your password was changed"))
        assertNull(MailSecurityRules.kind("someone@gmail.com", "New sign-in"))
        assertEquals(MailSort.NOTIFICATION, sort("no-reply@alerts-paypal.example", "Security alert").sort)
        // The provider's ordinary mail is not security mail.
        assertNull(MailSecurityRules.kind("no-reply@accounts.google.com", "Your storage is 80% full"))
        assertEquals(MailSort.NOTIFICATION, sort("noreply@google.com", "Your monthly Maps timeline").sort)
    }

    @Test
    fun securityMailIsNeverTidiedEvenWithAlwaysTidy() {
        val s = sort("no-reply@accounts.google.com", "Security alert")
        for (rule in listOf(null, MailSenderRule.TIDY)) {
            assertEquals(MailTidy.KEEP, MailTidyRules.plan(MailTidyFacts("no-reply@accounts.google.com", true, s, rule = rule)))
        }
        val code = sort("no-reply@accounts.google.com", "Your sign-in code")
        assertEquals(MailTidy.KEEP, MailTidyRules.plan(MailTidyFacts("no-reply@accounts.google.com", true, code, rule = MailSenderRule.TIDY)))
        // The backlog clean-up keeps it too, however old.
        assertEquals(MailBacklogKind.KEEP, MailBacklogRules.plan(MailBacklogFacts("no-reply@accounts.google.com", true, s, 0L), 400 * 86_400_000L))
    }

    @Test
    fun aRealSecurityEmailInJunkIsNeverTrashed() {
        val real = MailJunkFacts("no-reply@accounts.google.com", "Google", "Security alert: unusual sign-in", receivedAtMs = 0L, nowMs = 10 * 86_400_000L)
        assertEquals(MailJunkVerdict.LEAVE, MailJunkRules.verdict(real))
        val spoof = real.copy(fromAddress = "alert@secure-google-login.example")
        assertEquals(MailJunkVerdict.TRASH, MailJunkRules.verdict(spoof))
    }

    @Test
    fun theCardSaysCheckItWasYou() {
        val f = MailNeedsRules.serverFields("Personal", "Google", "no-reply@accounts.google.com", "Security alert", "A new sign-in on Mac", 1_000L, 1,
            security = MailSecurityRules.itemProvider(MailSortReason.SECURITY, "no-reply@accounts.google.com"))
        val item = MailNeedsRules.read("mi1", f)!!
        assertEquals("Google", item.security)
        val cal = LocalCalendar.UTC
        val row = MailNeedsRules.card(listOf(item), 2_000L, cal)!!.rows.single()
        assertEquals("Security alert from Google · check it was you", row.security)
        assertTrue(row.spoken.startsWith("Security alert from Google"))
        // Anything else carries no line.
        assertEquals("", MailSecurityRules.itemProvider(MailSortReason.NO_REPLY, "no-reply@accounts.google.com"))
        val plain = MailNeedsRules.read("mi2", MailNeedsRules.serverFields("Personal", "Sam", "sam@example.com", "Hi", null, 1_000L, 1))!!
        assertNull(MailNeedsRules.card(listOf(plain), 2_000L, cal)!!.rows.single().security)
    }
}
