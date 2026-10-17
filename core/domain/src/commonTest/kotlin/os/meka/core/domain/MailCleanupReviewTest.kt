package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review opens its own list, slice 4: the backlog clean-up's and Mark read's review sheets. Invented senders only. */
class MailCleanupReviewTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val min = 60_000L

    private fun p(from: String, name: String?, kind: MailBacklogKind) =
        MailCleanupReviewRules.PlanLooked(from, name, now - 20 * day, kind)

    private val plan = listOf(
        p("news@weekly.example", "Weekly Digest", MailBacklogKind.NEWSLETTER),
        p("news2@weekly.example", "Weekly Digest", MailBacklogKind.NEWSLETTER),
        p("news3@weekly.example", "Weekly Digest", MailBacklogKind.NEWSLETTER),
        p("deals@shop.example", "Daily Deals", MailBacklogKind.ARCHIVE),
        p("deals2@shop.example", "Daily Deals", MailBacklogKind.ARCHIVE),
        p("win@prizes.example", "Prize Draws", MailBacklogKind.JUNK),
        p("sam.taylor@gmail.com", "Sam Taylor", MailBacklogKind.KEEP),
    )

    private fun backlog(withList: Boolean = true, extra: Map<String, os.meka.core.sync.FieldValue> = emptyMap()): MailBacklog {
        val counts = plan.groupingBy { it.kind }.eachCount()
        val list = if (withList) MailCleanupReviewRules.planFields(MailCleanupReviewRules.planSenders(plan)) else emptyMap()
        return MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, plan.size, counts, null, readyAtMs = now) + list + extra)!!
    }

    @Test
    fun theCleanUpCardOpensItsOwnListOfWhoWouldMove() {
        val b = backlog()
        val card = MailBacklogRules.planCard(b)!!
        assertEquals(DecisionEffect.OPEN_REVIEW, card.open)
        val r = MailCleanupReviewRules.planReview(b)!!
        assertEquals("mb1", r.cardId)
        assertEquals("Clean up Personal", r.title)
        assertEquals("5 to archive · 1 junk to Trash from 3 senders", r.summary)
        assertEquals("TO MOVE · 3 SENDERS", r.sendersLabel)
        assertEquals(listOf("Weekly Digest", "Daily Deals", "Prize Draws"), r.senders.map { it.title })
        assertEquals("weekly.example · 3 emails · newsletters → Archive", r.senders[0].meta)
        assertEquals("prizes.example · 1 email · junk → Trash", r.senders[2].meta)
        assertEquals("Going to Trash", r.senders[2].state)
        assertEquals("Approve · move 6", r.approveLabel)
        assertTrue(r.canApprove)
        assertEquals("1 email stays in your inbox: they may need you", r.staysLine)
        // The person (KEEP) is never on the list, and no address is ever written.
        assertFalse(r.senders.any { it.title == "Sam Taylor" })
        assertFalse("news@" in b.planSenders!!)
    }

    @Test
    fun untickingASenderKeepsTheirMailInTheInboxAndTheCountsFollow() {
        val key = MailJunkReviewRules.senderKey("news@weekly.example", "Weekly Digest")
        val b = backlog(extra = MailCleanupReviewRules.planKeptFields(emptySet(), key, keep = true))
        val r = MailCleanupReviewRules.planReview(b)!!
        assertEquals("2 to archive · 1 junk to Trash from 2 senders · 3 kept in your inbox", r.summary)
        assertEquals("Stays in your inbox · you kept it", r.senders.first { it.key == key }.state)
        assertEquals("Approve · move 3", r.approveLabel)
        assertEquals("Archive 2 newsletters and promos older than a week · Trash 1 junk · keep 4 that may need you · nothing moves until you approve",
            MailBacklogRules.planCard(b)!!.why)
        // The server leaves every email from that sender alone (another address on the same domain too).
        assertTrue(MailCleanupReviewRules.keptInInbox(b, "other@weekly.example", null))
        assertFalse(MailCleanupReviewRules.keptInInbox(b, "deals@shop.example", "Daily Deals"))
        // Keep everyone: nothing to approve, but the sheet stays so he can tick one back.
        val all = MailCleanupReviewRules.planSenders(plan).map { it.key }.toSet()
        val none = backlog(extra = mapOf(MailBacklogFields.PLAN_KEEP_SENDERS to os.meka.core.sync.FieldValue.Text(MailJunkReviewRules.encodeKept(all))))
        assertNull(MailBacklogRules.planCard(none))
        val rn = MailCleanupReviewRules.planReview(none)!!
        assertFalse(rn.canApprove)
        assertEquals("Nothing to move · 6 kept in your inbox", rn.summary)
    }

    @Test
    fun beforeTheListArrivesTheSheetWaitsAndAfterApproveThereIsNone() {
        val r = MailCleanupReviewRules.planReview(backlog(withList = false))!!
        assertEquals(MailCleanupReviewRules.PLAN_WAITING, r.hint)
        assertTrue(r.senders.isEmpty())
        assertTrue(r.canApprove)
        assertNull(MailCleanupReviewRules.planReview(backlog().copy(approvedAtMs = now)))
    }

    private fun unread(tidy: Int, looked: List<MailJunkReviewRules.Looked>?, kept: Set<String> = emptySet()): MailBacklog {
        val base = MailBacklog(
            id = "mbP", account = "Personal", startedAtMs = now - day, scanned = 0, readyAtMs = null, counts = emptyMap(), top = null,
            approvedAtMs = null, inboxUnread = 400, tidyUnread = tidy, unreadCountedAtMs = now - 5 * min,
        )
        val f = (looked?.let { MailCleanupReviewRules.readFields(MailCleanupReviewRules.readSenders(it)) } ?: emptyMap()) +
            kept.fold(emptyMap<String, os.meka.core.sync.FieldValue>()) { _, k -> MailCleanupReviewRules.readKeptFields(kept - k, k, keep = true) }
        val read = MailBacklogRules.read("mbP", MailBacklogRules.fields("Personal", now - day, 0, emptyMap(), null, null) + f)!!
        return base.copy(readSenders = read.readSenders, readKeepSenders = read.readKeepSenders)
    }

    private fun l(from: String, name: String, subject: String, tidy: MailTidy) = MailJunkReviewRules.Looked(from, name, subject, now - day, tidy, null)

    private val unreadSample = (1..6).map { l("a$it@weekly.example", "Weekly Digest", "Issue $it", MailTidy.NEWSLETTER) } +
        (1..4).map { l("o$it@shop.example", "Shop", "Order $it shipped", MailTidy.RECEIPT) }

    @Test
    fun markReadOpensItsOwnListWithSubjectsAndTicks() {
        val b = unread(10, unreadSample)
        val card = MailMarkReadRules.card(b, now)!!
        assertEquals(DecisionEffect.OPEN_REVIEW, card.open)
        val r = MailCleanupReviewRules.readReview(b, now)!!
        assertEquals("read:mbP", r.cardId)
        assertEquals("10 to mark read from 2 senders", r.summary)
        assertEquals("TO MARK READ · 2 SENDERS", r.sendersLabel)
        assertEquals("weekly.example · 6 emails · newsletters", r.senders[0].meta)
        assertEquals(listOf("Issue 1", "Issue 2"), r.senders[0].subjects.sorted())
        assertEquals("Mark 10 read", r.approveLabel)
        // Untick the shop: its mail stays unread, the card and the button count the rest.
        val key = MailJunkReviewRules.senderKey("o1@shop.example", "Shop")
        val kept = unread(10, unreadSample, setOf(key))
        assertEquals(6, MailCleanupReviewRules.readMarking(kept))
        assertEquals("Mark 6 read · Personal", MailMarkReadRules.card(kept, now)!!.title)
        val rk = MailCleanupReviewRules.readReview(kept, now)!!
        assertEquals("6 to mark read from 1 sender · 4 left unread", rk.summary)
        assertEquals("Stays unread · you kept it", rk.senders.first { it.key == key }.state)
        assertTrue(MailCleanupReviewRules.keptUnread(kept, "o9@shop.example", null))
        assertFalse(MailCleanupReviewRules.keptUnread(kept, "a1@weekly.example", null))
    }

    @Test
    fun markReadWaitsForItsListAndOnlyListsWhatItWouldMark() {
        val r = MailCleanupReviewRules.readReview(unread(10, null), now)!!
        assertEquals(MailCleanupReviewRules.READ_WAITING, r.hint)
        val s = MailCleanupReviewRules.readSenders(unreadSample + l("x@junk.example", "Junk", "Win", MailTidy.JUNK_TRASH))
        assertFalse(s.any { it.name == "Junk" })
        assertNotNull(MailJunkReviewRules.decode(MailJunkReviewRules.encode(s), MailCleanupReviewRules.READ_TIDIES))
        assertNull(MailCleanupReviewRules.readReview(unread(3, unreadSample.take(3)), now))
    }
}
