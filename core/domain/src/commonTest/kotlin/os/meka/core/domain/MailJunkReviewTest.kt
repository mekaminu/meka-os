package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review opens its own list (Meka, 2026-10-17), slice 1: the Empty Junk card's Review sheet. Invented senders only. */
class MailJunkReviewTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun looked(from: String, name: String?, subject: String, tidy: MailTidy, ageDays: Int = 5, why: String? = null) =
        MailJunkReviewRules.Looked(from, name, subject, now - ageDays * day, tidy, why)

    private val sample = listOf(
        looked("win@prizes.example", "Prize Draws", "You've won a prize!", MailTidy.JUNK_TRASH, 3),
        looked("win2@prizes.example", "Prize Draws", "4 HOURS LEFT to claim", MailTidy.JUNK_TRASH, 4),
        looked("win3@prizes.example", "Prize Draws", "Last chance", MailTidy.JUNK_TRASH, 6),
        looked("alert@secure-pay.example", "Notification via PayPal", "Action needed on your account", MailTidy.JUNK_TRASH),
        looked("deals@shop.example", "Daily Deals", "50% off today", MailTidy.JUNK_TRASH),
        looked("sam.taylor@gmail.com", "Sam Taylor", "Re: Saturday", MailTidy.RESCUE, why = "You wrote in this thread"),
        looked("alex.green@gmail.com", "Alex Green", "Photos", MailTidy.JUNK_TRASH),
        looked("alerts@barclays.co.uk", "Barclays", "Statement ready", MailTidy.KEEP),
    )

    private fun backlog(extra: Map<String, FieldValue> = emptyMap(), senders: List<MailJunkReviewRules.Looked>? = sample): MailBacklog =
        MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 10, emptyMap(), null, readyAtMs = now) +
            MailJunkClearRules.countFields(8, now) +
            MailJunkClearRules.lookFields((senders ?: emptyList()).map { it.tidy }, full = true) +
            (senders?.let { MailJunkReviewRules.fields(MailJunkReviewRules.group(it)) } ?: emptyMap()) + extra)!!

    @Test
    fun sendersAreGroupedByDomainButWebmailByAddress() {
        val g = MailJunkReviewRules.group(sample)
        val prizes = g.first()
        assertEquals("Prize Draws", prizes.name); assertEquals("prizes.example", prizes.domain); assertEquals(3, prizes.count)
        assertEquals(listOf("You've won a prize!", "4 HOURS LEFT to claim"), prizes.subjects)
        assertNotNull(prizes.scam)
        // Two people on gmail.com are two senders.
        assertEquals(2, g.count { it.domain == "gmail.com" })
        assertEquals(MailJunkReviewRules.senderKey("win@prizes.example", null), MailJunkReviewRules.senderKey("other@prizes.example", "X"))
        assertFalse(MailJunkReviewRules.senderKey("sam.taylor@gmail.com", null) == MailJunkReviewRules.senderKey("alex.green@gmail.com", null))
        // The key never holds the address.
        assertFalse("prizes" in prizes.key)
    }

    @Test
    fun theListSurvivesTheField() {
        val g = MailJunkReviewRules.group(sample)
        val back = MailJunkReviewRules.decode(MailJunkReviewRules.encode(g))!!
        assertEquals(g.toSet(), back.toSet())
        assertEquals(emptyList(), MailJunkReviewRules.decode(MailJunkReviewRules.encode(emptyList())))
        assertNull(MailJunkReviewRules.decode(null))
        // Tabs and newlines in what the sender wrote can't break the lines.
        val odd = MailJunkReviewRules.group(listOf(looked("a@odd.example", "Odd\tName", "Line\none", MailTidy.JUNK_TRASH)))
        assertEquals("Odd Name", MailJunkReviewRules.decode(MailJunkReviewRules.encode(odd))!!.single().name)
        // Too many for the field: the rest are counted, never cut mid-line.
        val many = (1..2000).map { looked("x@s$it.example", "Sender $it", "Subject number $it with some words", MailTidy.JUNK_TRASH) }
        val text = MailJunkReviewRules.encode(MailJunkReviewRules.group(many))
        assertTrue(text.length <= MailJunkReviewRules.MAX_CHARS)
        assertTrue(MailJunkReviewRules.leftOut(text) > 0)
        assertEquals(2000, MailJunkReviewRules.decode(text)!!.size + MailJunkReviewRules.leftOut(text))
    }

    @Test
    fun reviewListsRescuesFirstThenEachSenderGoingToTrash() {
        val r = MailJunkReviewRules.review(backlog())!!
        assertEquals("junk:mb1", r.cardId)
        assertEquals("Empty Junk · Personal", r.title)
        assertEquals("6 emails to Trash from 4 senders · 1 back to your inbox · 1 stays for you to check", r.summary)
        assertEquals("Sam Taylor", r.rescues.single().title)
        assertEquals("You wrote in this thread", r.rescues.single().why)
        assertEquals("Prize Draws", r.senders.first().title)
        assertEquals("prizes.example · 3 emails", r.senders.first().meta)
        assertTrue(r.senders.first().scam)
        val paypal = r.senders.first { it.title == "Notification via PayPal" }
        assertEquals("Claims to be PayPal · not their address", paypal.scamLine)
        assertFalse(r.senders.first { it.title == "Daily Deals" }.scam)
        assertEquals("1 email stays for you to check: banks, schools and public bodies", r.staysLine)
        assertEquals("Approve · 6 to Trash", r.approveLabel)
        assertTrue(r.canApprove)
    }

    @Test
    fun aKeptSenderStaysInJunkEverywhere() {
        val key = MailJunkReviewRules.senderKey("win@prizes.example", "Prize Draws")
        val b = backlog(MailJunkReviewRules.keptFields(emptySet(), key, keep = true))
        assertEquals(setOf(key), b.junkKeepSenders)
        val r = MailJunkReviewRules.review(b)!!
        val row = r.senders.first { it.key == key }
        assertTrue(row.kept); assertEquals("Stays in Junk · you kept it", row.state)
        assertEquals("Approve · 3 to Trash", r.approveLabel)
        assertTrue(r.summary.startsWith("3 emails to Trash from 3 senders · 3 kept in Junk"), r.summary)
        // The card counts the same.
        val why = MailJunkClearRules.card(b)!!.why
        assertTrue(why.startsWith("3 emails your provider called spam") && "· 3 kept in Junk ·" in why, why)
        assertEquals(3, MailJunkReviewRules.toTrash(b))
        // The server leaves their mail; a rescue or a mail it would keep anyway is untouched by the tick.
        assertTrue(MailJunkReviewRules.keptInJunk(b.junkKeepSenders, "new@prizes.example", null, MailTidy.JUNK_TRASH))
        assertFalse(MailJunkReviewRules.keptInJunk(b.junkKeepSenders, "new@prizes.example", null, MailTidy.RESCUE))
        assertFalse(MailJunkReviewRules.keptInJunk(b.junkKeepSenders, "deals@shop.example", null, MailTidy.JUNK_TRASH))
        // Putting the tick back.
        val back = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 10, emptyMap(), null, now) +
            MailJunkReviewRules.keptFields(setOf(key), key, keep = false))!!
        assertTrue(back.junkKeepSenders.isEmpty())
    }

    @Test
    fun beforeTheListCameTheSheetSaysSoAndTheFolderIsCountedAgain() {
        val b = backlog(senders = null)
        val r = MailJunkReviewRules.review(b)!!
        assertTrue(r.senders.isEmpty())
        assertEquals(MailJunkReviewRules.WAITING, r.hint)
        assertFalse(MailJunkClearRules.countDue(b, now + 60_000))
        assertTrue(MailJunkClearRules.countDue(b, now + 31 * 60_000L))
        // Once the list is there, the usual recount.
        assertFalse(MailJunkClearRules.countDue(backlog(), now + 31 * 60_000L))
    }

    @Test
    fun theCardsReviewOpensItsOwnSheet() {
        val card = MailBacklogRules.cards(listOf(backlog())).first { MailJunkClearRules.isCard(it.id) }
        assertEquals(DecisionEffect.OPEN_REVIEW, card.open)
        assertEquals("Review", card.openLabel)
    }

    @Test
    fun rescuesSayWhy() {
        assertEquals("From your partner (About you)", MailJunkReviewRules.rescueWhy(MailJunkFacts("p@example.com", null, null, setOf("p@example.com"))))
        assertEquals("You wrote in this thread", MailJunkReviewRules.rescueWhy(MailJunkFacts("a@b.example", null, null, replied = true)))
        assertEquals("Already writes to you · their mail is in your inbox", MailJunkReviewRules.rescueWhy(MailJunkFacts("a@b.example", null, null, knownSender = true)))
        assertNull(MailJunkReviewRules.rescueWhy(MailJunkFacts("a@b.example", null, null)))
    }
}
