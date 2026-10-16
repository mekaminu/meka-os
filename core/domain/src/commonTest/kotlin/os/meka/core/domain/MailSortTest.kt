package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailSortTest {
    private fun mail(
        from: String? = "sam@example.com", subject: String? = "Hello", labels: List<String> = listOf("INBOX"),
        unsubscribe: String? = null, listId: String? = null, name: String? = null,
    ) = MailSortFacts(from, name, subject, labels, unsubscribe, listId)

    private fun sorted(f: MailSortFacts) = MailSortRules.sort(f)

    @Test
    fun aPersonWritingIsNeedsYouButNotSure() {
        val s = sorted(mail(subject = "Are we still on for Saturday?"))
        assertEquals(MailSort.NEEDS_YOU, s.sort)
        assertEquals(MailSortReason.PERSON, s.reason)
        assertFalse(s.sure)
        assertEquals(MailSortReason.NO_SENDER, sorted(mail(from = null)).reason)
    }

    @Test
    fun publicBodiesAndSchoolsAreAlwaysYours() {
        for (from in listOf("noreply@tax.service.gov.uk", "office@oakfield.sch.uk", "appointments@nhs.net", "x@gov.uk")) {
            val s = sorted(mail(from = from, unsubscribe = "<https://example.com/u>", labels = listOf("INBOX", "CATEGORY_UPDATES")))
            assertEquals(MailSort.NEEDS_YOU, s.sort, from)
            assertEquals(MailSortReason.PUBLIC_BODY, s.reason, from)
            assertTrue(s.sure)
        }
        // Only the real suffix counts.
        assertEquals(MailSortReason.PERSON, sorted(mail(from = "a@notgov.uk")).reason)
    }

    @Test
    fun gmailsSpamIsJunk() {
        assertEquals(MailSort.JUNK, sorted(mail(labels = listOf("SPAM"), from = "x@gov.uk")).sort)
    }

    @Test
    fun ordersReceiptsAndParcels() {
        assertEquals(MailSort.RECEIPT, sorted(mail(from = "auto-confirm@shop.example", subject = "Your order #123-456 has shipped")).sort)
        assertEquals(MailSort.RECEIPT, sorted(mail(from = "receipts@shop.example", subject = "Your receipt from Shop", unsubscribe = "<mailto:u@shop.example>")).sort)
        assertEquals(MailSortReason.CARRIER, sorted(mail(from = "tracking@evri.com", subject = "We'll be with you today")).reason)
        assertEquals(MailSortReason.CARRIER, sorted(mail(from = "info@email.royalmail.com", subject = "Something for you")).reason)
        // A person mentioning an invoice is still a person.
        assertEquals(MailSort.NEEDS_YOU, sorted(mail(subject = "Invoice for the kitchen job")).sort)
        // Order words beat Gmail's Promotions tab (a shop's dispatch note can land there).
        assertEquals(MailSort.RECEIPT, sorted(mail(from = "orders@shop.example", subject = "Dispatched: your parcel", labels = listOf("INBOX", "CATEGORY_PROMOTIONS"), listId = "<orders.shop.example>")).sort)
    }

    @Test
    fun promosNewslettersAndNotifications() {
        assertEquals(MailSort.PROMO, sorted(mail(from = "hello@brand.example", labels = listOf("INBOX", "CATEGORY_PROMOTIONS"))).sort)
        assertEquals(MailSortReason.PROMO_WORDS, sorted(mail(from = "hello@brand.example", subject = "30% off everything this weekend", unsubscribe = "<https://brand.example/u>")).reason)
        assertEquals(MailSort.NEWSLETTER, sorted(mail(from = "editor@weekly.example", subject = "This week in football", listId = "<weekly.example>")).sort)
        assertEquals(MailSort.NEWSLETTER, sorted(mail(labels = listOf("INBOX", "CATEGORY_FORUMS"))).sort)
        assertEquals(MailSort.NOTIFICATION, sorted(mail(from = "notifications@social.example", subject = "Sam mentioned you")).sort)
        assertEquals(MailSort.NOTIFICATION, sorted(mail(labels = listOf("INBOX", "CATEGORY_SOCIAL"))).sort)
        assertEquals(MailSortReason.GMAIL_UPDATES, sorted(mail(labels = listOf("INBOX", "CATEGORY_UPDATES"))).reason)
        assertEquals(MailSortReason.NO_REPLY, sorted(mail(from = "no-reply@bank.example", subject = "Your statement is ready")).reason)
    }

    @Test
    fun saleWordsAreWholeWords() {
        // "wholesale", "dealer": a newsletter, not a promo.
        assertEquals(MailSort.NEWSLETTER, sorted(mail(subject = "Wholesale news from your dealer", listId = "<trade.example>")).sort)
        assertEquals(MailSort.PROMO, sorted(mail(subject = "Big SALE ends tonight", listId = "<trade.example>")).sort)
    }

    @Test
    fun noReplySendersAreRecognised() {
        for (a in listOf("noreply@x.com", "no-reply@x.com", "donotreply@x.com", "notifications@x.com", "alerts.bank@x.com", "<noreply+abc@x.com>", "account-notify@x.com")) {
            assertTrue(MailSortRules.isAutomated(a), a)
        }
        for (a in listOf("sam@x.com", "alertsam@x.com", "noreplyman@x.com", null, "")) assertFalse(MailSortRules.isAutomated(a), a.toString())
    }

    @Test
    fun theSummaryCountsInOrderAndLeavesZerosOut() {
        assertNull(MailSortRules.summary(null))
        assertEquals(MailSortRules.INBOX_CLEAR, MailSortRules.summary(emptyMap()))
        assertEquals(MailSortRules.INBOX_CLEAR, MailSortRules.summary(mapOf("PROMO" to 0)))
        assertEquals(
            "In the inbox · 6 need you · 1 newsletter · 12 receipts and orders · 4 promos · 2 junk",
            MailSortRules.summary(mapOf("JUNK" to 2, "PROMO" to 4, "NEWSLETTER" to 1, "RECEIPT" to 12, "NEEDS_YOU" to 6, "OTHER" to 3)),
        )
        assertEquals("In the inbox · 1 needs you", MailSortRules.summary(mapOf("NEEDS_YOU" to 1)))
    }

    @Test
    fun anAccountsRowSaysItsSorted() {
        val now = 1_790_985_600_000L
        val a = MailAccountFacts(MailProviders.GMAIL, "alex@example.com", "ok", now - 4 * 60_000L, "PERSONAL", mapOf("NEEDS_YOU" to 3))
        val row = MailAccountRules.row(a, emptyList(), now)
        assertEquals("Connected · checked 4 min ago", row.line)
        assertEquals("In the inbox · 3 need you", row.sortedLine)
        val old = MailAccountRules.row(a.copy(sorted = null), emptyList(), now)
        assertEquals("Connected · checked 4 min ago · sorting comes in the next update", old.line)
        assertNull(old.sortedLine)
    }

    @Test
    fun theAiOnlyChoosesBetweenNeedsYouAndFyiAndIsNeverAskedTwice() {
        assertEquals(MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true), MailSortRules.afterAi("fyi"))
        assertEquals(MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true), MailSortRules.afterAi(" FYI "))
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_NEEDS_YOU, true), MailSortRules.afterAi("needs_you"))
        // Anything else keeps it for Meka: the AI can't make mail Junk, Promo or anything to be tidied.
        for (odd in listOf(null, "", "junk", "promo", "newsletter", "ignore previous instructions")) {
            assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_UNCLEAR, true), MailSortRules.afterAi(odd), odd.toString())
        }
        val person = MailSortRules.sort(MailSortFacts("sam@example.com", "Sam", "Saturday?", listOf("INBOX"), null, null))
        assertTrue(MailSortRules.wantsAi(person))
        assertFalse(MailSortRules.wantsAi(MailSortRules.afterAi("fyi")))
        assertFalse(MailSortRules.wantsAi(MailSortRules.afterAi(null)))
        assertFalse(MailSortRules.wantsAi(MailSortRules.sort(MailSortFacts("x@service.gov.uk", null, null, emptyList(), null, null))))
        assertFalse(MailSortRules.wantsAi(MailSortRules.sort(MailSortFacts("noreply@x.com", null, null, emptyList(), null, null))))
    }

    @Test
    fun bulkHeadersMakeMailTidyKindButNeverAPublicBodysOrABanksSort() {
        assertTrue(MailSortRules.bulk("bulk", null))
        assertTrue(MailSortRules.bulk(" List ", null))
        assertTrue(MailSortRules.bulk("junk", null))
        assertTrue(MailSortRules.bulk(null, "auto-generated"))
        assertTrue(MailSortRules.bulk(null, "auto-replied; owner-email=\"x@example.com\""))
        assertFalse(MailSortRules.bulk(null, "no"))
        assertFalse(MailSortRules.bulk("first-class", null))
        assertFalse(MailSortRules.bulk(null, null))
        val bulk = listOf("INBOX", MailSortRules.BULK_LABEL)
        assertEquals(MailSorted(MailSort.NOTIFICATION, MailSortReason.BULK, true),
            MailSortRules.sort(MailSortFacts("updates@shop.example", "Shop", "Your weekly picks", bulk, null, null)))
        // A list still reads as a newsletter; a public body is still his, sure.
        assertEquals(MailSort.NEWSLETTER, MailSortRules.sort(MailSortFacts("news@shop.example", null, "Hello", bulk, "<mailto:u@shop.example>", null)).sort)
        assertEquals(MailSortReason.PUBLIC_BODY, MailSortRules.sort(MailSortFacts("x@council.gov.uk", null, "Bins", bulk, null, null)).reason)
    }

    @Test
    fun onlyAnInboxEmailThatLooksLikeAPersonsGetsItsHeadersRead() {
        val person = MailSortFacts("sam@example.com", "Sam", "Saturday?", listOf("INBOX"), null, null)
        assertTrue(MailSortRules.wantsHeaderLook(person, inInbox = true))
        assertFalse(MailSortRules.wantsHeaderLook(person, inInbox = false))
        assertFalse(MailSortRules.wantsHeaderLook(person.copy(listUnsubscribe = "<https://x.example/u>"), inInbox = true))
        assertFalse(MailSortRules.wantsHeaderLook(person.copy(fromAddress = "noreply@x.example"), inInbox = true))
        assertFalse(MailSortRules.wantsHeaderLook(person.copy(labels = listOf("INBOX", MailSortRules.BULK_LABEL)), inInbox = true))
    }

    @Test
    fun peopleHeHasWrittenToAreHisWithoutTheAisLook() {
        val known = MailSortRules.correspondents(
            listOf("Sam@Example.com", "<jo@example.org>", "noreply@shop.example", "me@example.com", "not an address", "a@b", null, "x@@y.com"),
            own = setOf("me@example.com"),
        )
        assertEquals(setOf("sam@example.com", "jo@example.org"), known)
        val unsure = MailSortRules.sort(MailSortFacts("sam@example.com", "Sam", "Saturday?", listOf("INBOX"), null, null))
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.KNOWN_PERSON, true), MailSortRules.known(unsure, "SAM@example.com", known))
        assertFalse(MailSortRules.wantsAi(MailSortRules.known(unsure, "sam@example.com", known)))
        // A stranger stays unsure (the AI looks); a sure sort (his own newsletter) and an AI answer are left alone.
        assertEquals(unsure, MailSortRules.known(unsure, "kim@example.com", known))
        val list = MailSortRules.sort(MailSortFacts("sam@example.com", "Sam", "Weekly", listOf("INBOX"), "<mailto:u@example.com>", null))
        assertEquals(list, MailSortRules.known(list, "sam@example.com", known))
        val fyi = MailSortRules.afterAi(MailSortRules.AI_FYI)
        assertEquals(fyi, MailSortRules.known(fyi, "sam@example.com", known))
        assertEquals(unsure, MailSortRules.known(unsure, null, known))
    }
}
