package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox fixes, slice 3: Junk older than two days goes to Trash, and "Empty Junk" clears the folder once on Approve. */
class MailJunkClearTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun v(
        from: String? = "someone@random.example", subject: String? = "Meeting notes", ageMs: Long,
        keep: Set<String> = emptySet(), replied: Boolean = false, known: Boolean = false,
    ) = MailJunkRules.verdict(MailJunkFacts(from, null, subject, keep, replied, known, receivedAtMs = now - ageMs, nowMs = now))

    @Test
    fun junkOlderThanTwoDaysGoesToTrash() {
        assertEquals(MailJunkVerdict.LEAVE, v(ageMs = 2 * day - 1))
        assertEquals(MailJunkVerdict.TRASH, v(ageMs = 2 * day))
        assertEquals(MailJunkVerdict.TRASH, v(ageMs = 40 * day))
        // A scam goes at once, whatever its age.
        assertEquals(MailJunkVerdict.TRASH, v(subject = "You have won a prize", ageMs = 0))
        // No date known: left as before.
        assertEquals(MailJunkVerdict.LEAVE, MailJunkRules.verdict(MailJunkFacts("x@y.example", null, "Hi", nowMs = now)))
    }

    @Test
    fun theNeverTouchListStillHoldsWhenOld() {
        assertEquals(MailJunkVerdict.RESCUE, v(from = "sam@example.com", keep = setOf("sam@example.com"), ageMs = 10 * day))
        assertEquals(MailJunkVerdict.RESCUE, v(replied = true, ageMs = 10 * day))
        assertEquals(MailJunkVerdict.RESCUE, v(known = true, ageMs = 10 * day))
        assertEquals(MailJunkVerdict.LEAVE, v(from = "alerts@barclays.co.uk", ageMs = 10 * day))
        assertEquals(MailJunkVerdict.LEAVE, v(from = "office@oakfield.sch.uk", ageMs = 10 * day))
        assertEquals(MailJunkVerdict.LEAVE, v(from = "noreply@tax.service.gov.uk", ageMs = 10 * day))
        // The daily review's window covers the two-day mark between reviews.
        assertTrue(MailJunkRules.WINDOW_MS - MailJunkRules.CLEAR_AFTER_MS > MailJunkRules.REVIEW_EVERY_MS)
    }

    private fun plan(extra: Map<String, os.meka.core.sync.FieldValue> = emptyMap()): MailBacklog =
        MailBacklogRules.read("mb1", MailBacklogRules.fields("Outlook", now, 10, emptyMap(), null, readyAtMs = now) + extra)!!

    @Test
    fun emptyJunkIsACardUntilApproved() {
        assertTrue(MailBacklogRules.cards(listOf(plan())).isEmpty())
        val counted = plan(MailJunkClearRules.countFields(1204, now))
        val card = MailBacklogRules.cards(listOf(counted)).single()
        assertEquals("junk:mb1", card.id)
        assertEquals("Empty Junk · Outlook", card.title)
        assertTrue(card.why.startsWith("1,204 emails your provider called spam → Trash, recoverable for 30 days"))
        assertTrue("nothing moves until you approve" in card.why)
        assertEquals(DecisionEffect.APPROVE_BACKLOG, card.yes)
        assertTrue(MailBacklogRules.cards(listOf(counted), paused = true).isEmpty())
        val approved = plan(MailJunkClearRules.countFields(1204, now) + MailJunkClearRules.approveFields(now + 1))
        assertTrue(MailBacklogRules.cards(listOf(approved)).isEmpty())
        assertTrue(MailJunkClearRules.canUnapprove(approved))
        assertFalse(MailJunkClearRules.countDue(approved, now + day))
        val moving = plan(MailJunkClearRules.countFields(1204, now) + MailJunkClearRules.approveFields(now + 1) + MailJunkClearRules.progressFields(200, null))
        assertFalse(MailJunkClearRules.canUnapprove(moving))
        assertTrue(MailJunkClearRules.shouldClear(moving, paused = false, batch = null))
        assertFalse(MailJunkClearRules.shouldClear(moving, paused = true, batch = null))
        val done = plan(MailJunkClearRules.countFields(1204, now) + MailJunkClearRules.approveFields(now + 1) + MailJunkClearRules.progressFields(1198, now + 2))
        assertFalse(MailJunkClearRules.shouldClear(done, false, null))
        assertTrue(MailBacklogRules.cards(listOf(done)).isEmpty())
    }

    @Test
    fun theFolderIsCountedAgainNowAndThen() {
        assertFalse(MailJunkClearRules.countDue(null, now))
        assertTrue(MailJunkClearRules.countDue(plan(), now))
        val counted = plan(MailJunkClearRules.countFields(5, now))
        assertFalse(MailJunkClearRules.countDue(counted, now + MailJunkClearRules.RECOUNT_EVERY_MS - 1))
        assertTrue(MailJunkClearRules.countDue(counted, now + MailJunkClearRules.RECOUNT_EVERY_MS))
        // An empty Junk shows no card.
        assertTrue(MailBacklogRules.cards(listOf(plan(MailJunkClearRules.countFields(0, now)))).isEmpty())
    }

    @Test
    fun theClearOutIsOneTidyWithUndo() {
        val f = MailJunkClearRules.batchFields(now, "Outlook", mapOf(MailTidy.JUNK_TRASH to 1198, MailTidy.RESCUE to 2))
        val b = MailAutopilotRules.readBatch(MailJunkClearRules.batchId("acc1"), f)!!
        assertEquals("Junk emptied · 1,198 to Trash · 2 rescued · Outlook", b.summary)
        assertTrue(b.junk)
        assertTrue(b.canUndo)
        assertTrue(MailJunkClearRules.batchId("acc1") != MailJunkRules.batchId("acc1", now))
    }

    @Test
    fun approveFromEitherAppAndWithdrawWhileNothingMoved() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        var n = 0
        val la = ActivityLog(a.replica, { "x${n++}" }, { world.clock.nowMs })
        val lm = ActivityLog(m.replica, { "y${n++}" }, { world.clock.nowMs })
        val t = world.clock.nowMs
        a.replica.commitLocal(EntityTypes.MAIL_BACKLOG, "mb1",
            MailBacklogRules.fields("Outlook", t - 10, 10, emptyMap(), null, readyAtMs = null) + MailJunkClearRules.countFields(322, t - 5))
        assertEquals(MailBacklogRules.NOT_APPROVED, la.approveBacklog("junk:nope"))
        assertEquals(MailBacklogRules.APPROVED_BAR, la.approveBacklog("junk:mb1"))
        // The backlog plan itself (still scanning) is untouched.
        assertNull(la.mailBacklogs().single().approvedAtMs)
        a.sync(); m.sync()
        assertTrue(lm.mailBacklogs().single().junkApprovedAtMs != null)
        assertTrue(lm.unapproveBacklog("junk:mb1"))
        m.sync(); a.sync()
        assertNull(la.mailBacklogs().single().junkApprovedAtMs)
        assertFalse(la.unapproveBacklog("junk:mb1"))
    }
}
