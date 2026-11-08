package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phishing that hides its threat in the body (Meka, 2026-11-08): caught by the headers, the body, or the sender check. */
class MailScamBodyTest {
    private val cal = LocalCalendar.UTC
    private fun sort(from: String?, name: String?, subject: String?) =
        MailSortRules.sort(MailSortFacts(from, name, subject, listOf("INBOX"), null, null))

    // The email Meka sent (his own address swapped for an invented one).
    private val from = "formazione@formlab.it"
    private val name = "Outlook Session"
    private val subject = "Is Your Account Still Active?"
    private val text = "Microsoft\n\nHi alex@hotmail.com,\n\nWe noticed your account has not been verified recently. " +
        "Please confirm within 7 days or your account will be closed.\n\nConfirm Account"
    private val html = "<p><img alt=\"Microsoft\" data-blocked src=\"\"></p><p>Hi alex@hotmail.com,</p>" +
        "<p>We noticed your account has not been verified recently. Please confirm within 7 days or your account will be closed.</p>" +
        "<p><a href=\"https://formlab-login.example/ms/confirm?u=1\">Confirm Account</a></p>"

    @Test
    fun theOutlookSessionEmailIsAScamFromItsHeadersAlone() {
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.SCAM, true), sort(from, name, subject))
        val f = MailScamRules.check(from, name, subject)!!
        assertEquals(MailScamKind.SCAM, f.kind)
        assertEquals("⚠ Possible scam · sent from formlab.it, not microsoft.com", MailScamRules.line(f))
        assertEquals(MailScamRules.line(f), MailScamRules.itemWarning(MailSortReason.SCAM, from, name, subject))
        // Already in the inbox as Needs you: sorted again as a scam.
        val again = sort(from, name, subject)
        assertEquals(again, MailScamRules.resort(MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false), again))
    }

    @Test
    fun productsCountAsTheirCompanyAndAnAccountSubjectIsEnoughFromTheWrongDomain() {
        // No threat word: a display name claiming a product, from a domain that isn't the company's, about the account.
        assertEquals(MailSortReason.SCAM, sort("admin@mailer.example", "Outlook Team", "Your mailbox").reason)
        assertEquals(MailSortReason.SCAM, sort("help@gmail.com", "Gmail Support", "Account notice").reason)
        assertEquals(MailSortReason.SCAM, sort("no-reply@cloud.example", "iCloud", "Storage update").reason)
        assertEquals(MailSortReason.SCAM, sort("x@desk.example", "Office 365 Admin", "Password expires today").reason)
        // New account-check threats with a product named.
        assertEquals(MailSortReason.SCAM, sort("a@b.example", "Mail Desk", "OneDrive: session expired, reactivate now").reason)
        // The real company's own mail is not a scam; nor is a person without a company named.
        assertNull(MailScamRules.check("account-security-noreply@accountprotection.microsoft.com", "Microsoft account team", "Your account"))
        assertNull(MailScamRules.check("sam@example.com", "Sam", "My account"))
        // A company named but nothing about the account: left alone.
        assertNull(MailScamRules.check("news@shop.example", "Outlook Weekly", "Ten picks for the weekend"))
    }

    @Test
    fun theBodyGivesItAwayWhenTheHeadersDont() {
        // Plain headers: a sender and subject that say nothing.
        val f = MailScamRules.bodyCheck(MailBodyFacts("notice@formlab.it", "Mail Admin", "Notice", text, html))!!
        assertEquals(MailScamRules.BODY_BRAND, f.why)
        assertEquals("⚠ Possible scam · says Microsoft, sent from formlab.it", MailScamRules.line(f))
        assertEquals(listOf("formlab-login.example"), MailScamRules.linkHosts(html, null))
        // Greeted by his own address, even with no company named.
        val hi = MailScamRules.bodyCheck(MailBodyFacts("x@post.example", "Admin", "Notice", "Hello alex@hotmail.com, see attached.", null, listOf("Alex@Hotmail.com")))
        assertEquals(MailScamRules.BODY_GREETING, hi?.why)
        assertEquals("⚠ Possible scam · greets you by your email address, sent from post.example", MailScamRules.line(hi!!))
        // The sender check failed: a spoof, even from the company's own domain.
        val spoof = MailScamRules.bodyCheck(MailBodyFacts("service@paypal.co.uk", "PayPal", "Hello", "Hi", null,
            authResults = "mx.example; spf=fail smtp.mailfrom=paypal.co.uk; dkim=none; dmarc=fail header.from=paypal.co.uk"))
        assertEquals(MailScamRules.BODY_AUTH, spoof?.why)
        assertEquals("⚠ Possible scam · failed the sender check, says PayPal, sent from paypal.co.uk", MailScamRules.line(spoof!!))
        assertTrue(MailScamRules.authFailed("spf=fail; dkim=fail"))
        assertFalse(MailScamRules.authFailed("spf=pass; dkim=pass; dmarc=pass"))
        // The whole email in: the headers' own find wins.
        assertEquals("⚠ Possible scam · sent from formlab.it, not microsoft.com",
            MailScamRules.line(MailScamRules.bodyCheck(MailBodyFacts(from, name, subject, text, html, listOf("alex@hotmail.com")))!!))
    }

    @Test
    fun realSecurityMailAndOrdinaryMailAreLeftAlone() {
        // A real Microsoft security alert, links on Microsoft's own sites, pressing words and all.
        val real = MailBodyFacts(
            "account-security-noreply@accountprotection.microsoft.com", "Microsoft account team", "Microsoft account security alert",
            "We detected something unusual. If this wasn't you, review your activity within 24 hours.",
            "<a href=\"https://account.live.com/activity\">Review</a> <a href=\"https://go.microsoft.com/fwlink\">Help</a>",
            listOf("alex@hotmail.com"), "spf=pass; dkim=pass; dmarc=pass",
        )
        assertNull(MailScamRules.bodyCheck(real))
        // A friend sharing a news story about a company: no pressing, no scam.
        assertNull(MailScamRules.bodyCheck(MailBodyFacts("sam@example.com", "Sam", "Saw this",
            "Amazon is opening a new warehouse near us: https://news.example/story", null)))
        // A person who presses but names no company and doesn't greet him by address.
        assertNull(MailScamRules.bodyCheck(MailBodyFacts("sam@example.com", "Sam", "Saturday",
            "Can you reply immediately? Tickets here: https://tickets.example", null, listOf("alex@hotmail.com"))))
    }

    @Test
    fun theReaderOpensAScamWithTheBannerPicturesHiddenAndLinksThatDontOpen() {
        val fallback = MailReaderFallback(name, subject, null, 1_000L, "Hotmail")
        val answer = MailMessageAnswer(
            MailMessageAnswer.OK, from = MailPerson("Mail Admin", "notice@formlab.it"), to = listOf(MailPerson(null, "alex@hotmail.com")),
            atMs = 1_000L, subject = "Notice", html = html, text = null, blockedImages = 1,
        )
        val v = MailReaderRules.view(answer, fallback, 2_000L, cal, images = true)
        assertEquals("⚠ Possible scam · says Microsoft, sent from formlab.it", v.scam)
        assertFalse(v.canShowImages)
        assertEquals(MailReaderRules.SCAM_IMAGES, v.imagesLine)
        assertTrue(v.page!!.contains("img-src data:;"), "pictures stay blocked even when asked for")
        assertTrue(v.spoken.startsWith("⚠ Possible scam"))
        // A link in it says where it goes and doesn't open.
        val l = MailReaderLinks.scamCheck("https://formlab-login.example/ms/confirm?u=1")!!
        assertFalse(l.openable)
        assertEquals("Goes to formlab-login.example · ${MailReaderLinks.SCAM_LINK}", l.title)
        assertTrue(MailReaderLinks.check("https://bbc.co.uk")!!.openable)
        // The card's own scam warning shows when the whole email couldn't be read.
        val offline = MailReaderRules.view(null, fallback, 2_000L, cal, false, warning = "⚠ Possible scam · sent from formlab.it, not microsoft.com")
        assertEquals("⚠ Possible scam · sent from formlab.it, not microsoft.com", offline.scam)
        assertNull(MailReaderRules.view(null, fallback, 2_000L, cal, false, warning = "From PayPal's real address · …").scam)
        // An ordinary email has no banner and its pictures can be shown.
        val plain = MailReaderRules.view(answer.copy(from = MailPerson("Sam", "sam@example.com"), html = "<p>See you Saturday</p>"), fallback, 2_000L, cal, false)
        assertNull(plain.scam)
        assertTrue(plain.canShowImages)
    }
}
