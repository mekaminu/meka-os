package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailBacklogTest {
    private val now = 1_790_985_600_000L
    private val day = 86_400_000L
    private fun sure(s: MailSort) = MailSorted(s, MailSortReason.MAILING_LIST, true)
    private fun plan(
        s: MailSorted?, ageDays: Long = 45, from: String? = "news@shop.example", inInbox: Boolean = true,
        keep: Set<String> = emptySet(), replied: Boolean = false,
    ) = MailBacklogRules.plan(MailBacklogFacts(from, inInbox, s, now - ageDays * day, keep, replied), now)

    @Test
    fun olderTidyKindMailIsPlannedLikeAutopilot() {
        assertEquals(MailBacklogKind.NEWSLETTER, plan(sure(MailSort.NEWSLETTER)))
        assertEquals(MailBacklogKind.RECEIPT, plan(sure(MailSort.RECEIPT)))
        assertEquals(MailBacklogKind.ARCHIVE, plan(sure(MailSort.NOTIFICATION)))
        assertEquals(MailBacklogKind.ARCHIVE, plan(sure(MailSort.PROMO)))
        assertEquals(MailTidy.NEWSLETTER, MailBacklogKind.NEWSLETTER.tidy)
        assertEquals(MailTidy.TRASH, MailBacklogKind.JUNK.tidy)
    }

    @Test
    fun onlyMailOlderThanAWeekIsArchived() {
        // Inbox fixes, slice 2: a week, not 30 days.
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.NEWSLETTER), ageDays = 6))
        assertEquals(MailBacklogKind.NEWSLETTER, plan(sure(MailSort.NEWSLETTER), ageDays = 7))
        assertEquals(MailBacklogKind.ARCHIVE, plan(sure(MailSort.PROMO), ageDays = 8))
        // Junk goes whatever its age.
        assertEquals(MailBacklogKind.JUNK, plan(sure(MailSort.JUNK), ageDays = 1))
    }

    @Test
    fun peopleProtectedSendersHisThreadsAndUnsureMailAreKept() {
        for (s in listOf(MailSort.NEEDS_YOU, MailSort.FYI)) assertEquals(MailBacklogKind.KEEP, plan(sure(s)), s.name)
        assertEquals(MailBacklogKind.KEEP, plan(null))
        assertEquals(MailBacklogKind.KEEP, plan(MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, false)))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.NEWSLETTER), inInbox = false))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.NOTIFICATION), from = "alerts@barclays.co.uk"))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.JUNK), from = "office@oakfield.sch.uk"))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.PROMO), from = "alex@example.com", keep = setOf("alex@example.com")))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.RECEIPT), replied = true))
    }

    @Test
    fun theBusiestSendersAreNamed() {
        val movers = List(5) { "Weekly Digest" to "editor@weekly.example" } + List(3) { null to "deals@oakfield.example" } +
            List(3) { "Alpha" to "a@alpha.example" } + listOf("Zed" to "z@zed.example")
        assertEquals("Weekly Digest 5 · Alpha 3 · oakfield.example 3", MailBacklogRules.topSenders(movers))
        assertNull(MailBacklogRules.topSenders(emptyList()))
    }

    @Test
    fun theFieldsRoundTripAndTheRowSaysThePlan() {
        val counts = mapOf(MailBacklogKind.NEWSLETTER to 2100, MailBacklogKind.RECEIPT to 900, MailBacklogKind.ARCHIVE to 412,
            MailBacklogKind.JUNK to 640, MailBacklogKind.KEEP to 212)
        val f = MailBacklogRules.fields("Personal", now, 4264, counts, "Weekly Digest 412", readyAtMs = now + 1)
        val b = assertNotNull(MailBacklogRules.read("mb1", f))
        assertTrue(b.ready)
        assertEquals(4052, b.toMove)
        assertNull(b.approvedAtMs)
        val r = MailBacklogRules.row(b)
        assertEquals("Archive 3,412 · Trash 640 junk · keep 212 that may need you", r.line)
        assertEquals(
            "2,100 newsletters → Navilife/Newsletters · 900 receipts and orders → Navilife/Receipts · " +
                "412 notifications and promos archived · only mail older than a week\n" +
                "Junk → Trash, recoverable for 30 days\nMost from Weekly Digest 412",
            r.detail,
        )
        assertEquals(MailBacklogRules.NOTE, r.note)
        // A recount can lower a kind to zero: every kind is written.
        val again = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 10, mapOf(MailBacklogKind.KEEP to 3), null, now))!!
        assertEquals(mapOf(MailBacklogKind.KEEP to 3), again.counts)
        assertNull(again.top)
        assertEquals("Nothing to clean up · keep 3 that may need you", MailBacklogRules.row(again).line)
    }

    @Test
    fun whileScanningTheRowSaysHowFarItGot() {
        val b = MailBacklogRules.read("mb1", MailBacklogRules.fields("Business", now, 1240, emptyMap(), null, null))!!
        assertFalse(b.ready)
        val r = MailBacklogRules.row(b)
        assertEquals("Looking through the older mail in the inbox · 1,240 so far…", r.line)
        assertNull(r.detail)
        assertFalse(r.ready)
        assertEquals(r, MailBacklogRules.rowFor(listOf(b), "Business"))
        assertNull(MailBacklogRules.rowFor(listOf(b), "Personal"))
        assertEquals(MailBacklogRules.entityId("acc1"), MailBacklogRules.entityId("acc1"))
        assertTrue(MailBacklogRules.entityId("acc1") != MailBacklogRules.entityId("acc2"))
    }

    @Test
    fun mailShowsEachAccountsPlan() {
        val facts = listOf(MailAccountFacts(MailProviders.GMAIL, "a@example.com", "ok", now, "PERSONAL"))
        val b = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 5, mapOf(MailBacklogKind.NEWSLETTER to 5), null, now))!!
        val v = MailAccountRules.view(facts, now, emptyList(), MailAutopilotSetting(true, null), false, emptyList(), LocalCalendar.UTC, emptyList(), listOf(b))
        assertEquals("Archive 5", v.rows.single().backlog?.line)
        val none = MailAccountRules.view(facts, now, emptyList(), MailAutopilotSetting(true, null), false, emptyList(), LocalCalendar.UTC)
        assertNull(none.rows.single().backlog)
    }

    // ---- Slice 8b: Approve, applying, Undo ----

    private val counts = mapOf(MailBacklogKind.NEWSLETTER to 2100, MailBacklogKind.RECEIPT to 900, MailBacklogKind.ARCHIVE to 412,
        MailBacklogKind.JUNK to 640, MailBacklogKind.KEEP to 212)
    private fun ready(extra: Map<String, FieldValue> = emptyMap()) =
        MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 4264, counts, null, readyAtMs = now + 1) + extra)!!
    private fun tidy(asked: Long? = null, undone: Long? = null, note: String? = null): MailTidyBatch {
        val f = MailBacklogRules.batchFields(now + 10, "Personal", mapOf(MailTidy.NEWSLETTER to 400, MailTidy.TRASH to 100)) +
            listOfNotNull(
                asked?.let { MailTidyBatchFields.UNDO_ASKED_AT to FieldValue.Int64(it) },
                undone?.let { MailTidyBatchFields.UNDONE_AT to FieldValue.Int64(it) },
                note?.let { MailTidyBatchFields.UNDO_NOTE to FieldValue.Text(it) },
            ).toMap()
        return MailAutopilotRules.readBatch("mt1", f)!!
    }

    @Test
    fun aFinalPlanOffersApproveAndNothingElseDoes() {
        val b = ready()
        assertTrue(MailBacklogRules.canApprove(b))
        assertEquals("mb1", MailBacklogRules.row(b).approveId)
        assertNull(MailBacklogRules.row(b).undoId)
        // Still scanning, or nothing to move: no Approve.
        val scanning = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 10, counts, null, null))!!
        assertNull(MailBacklogRules.row(scanning).approveId)
        val nothing = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 3, mapOf(MailBacklogKind.KEEP to 3), null, now))!!
        assertFalse(MailBacklogRules.canApprove(nothing))
        assertNull(MailBacklogRules.row(nothing).approveId)
    }

    @Test
    fun approvedTheRowCountsTheCleanUpAndOffersUndo() {
        val approved = ready(MailBacklogRules.approveFields(now + 5))
        assertTrue(approved.approved)
        assertFalse(MailBacklogRules.canApprove(approved))
        assertTrue(MailBacklogRules.canUnapprove(approved))
        val waiting = MailBacklogRules.row(approved)
        assertEquals(MailBacklogRules.APPROVED_LINE, waiting.line)
        assertNull(waiting.approveId)
        assertTrue(waiting.working)
        assertEquals("mb1", waiting.cancelId)
        assertEquals(MailBacklogRules.PAUSED_NOTE, MailBacklogRules.row(approved, paused = true).note)
        // The bar's Undo clears it again.
        assertNull(MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 4264, counts, null, now + 1) +
            MailBacklogRules.approveFields(now + 5) + MailBacklogRules.unapproveFields())!!.approvedAtMs)
        // Moving: "Cleaning up · 500 of 4,052 moved…" with Undo on the tidy.
        val moving = ready(MailBacklogRules.approveFields(now + 5) + MailBacklogRules.progressFields(500, now + 10, null, 0))
        assertFalse(MailBacklogRules.canUnapprove(moving))
        val r = MailBacklogRules.row(moving, tidy(), nowMs = now + 20)
        assertEquals("Cleaning up · 500 of 4,052 moved…", r.line)
        assertEquals("mt1", r.undoId)
        assertNull(r.cancelId)
        // Finished.
        val done = ready(MailBacklogRules.approveFields(now + 5) + MailBacklogRules.progressFields(4052, now + 10, now + 99, 0))
        val d = MailBacklogRules.row(done, tidy(), nowMs = now + 100)
        assertEquals("Cleaned up · 4,052 older emails moved", d.line)
        assertEquals("Archived 3,412 · 640 junk to Trash", d.detail)
        assertEquals("mt1", d.undoId)
        assertFalse(d.working)
        // After 30 days Undo goes.
        assertNull(MailBacklogRules.row(done, tidy(), nowMs = now + 31 * day).undoId)
        // Undo asked, then done.
        val putting = ready(MailBacklogRules.approveFields(now + 5) + MailBacklogRules.progressFields(4052, now + 10, now + 99, 300))
        assertEquals("${MailBacklogRules.PUTTING_BACK} · 300 of 4,052 so far", MailBacklogRules.row(putting, tidy(asked = now + 200)).line)
        assertNull(MailBacklogRules.row(putting, tidy(asked = now + 200)).undoId)
        assertEquals("Put back 4,052 emails in the inbox",
            MailBacklogRules.row(putting, tidy(asked = now + 200, undone = now + 300, note = "Put back 4,052 emails in the inbox")).line)
    }

    @Test
    fun theServerAppliesOnlyAnApprovedFinalPlanWhileNothingStopsIt() {
        val approved = ready(MailBacklogRules.approveFields(now + 5))
        assertTrue(MailBacklogRules.shouldApply(approved, paused = false, batch = null))
        assertFalse(MailBacklogRules.shouldApply(ready(), paused = false, batch = null))
        assertFalse(MailBacklogRules.shouldApply(approved, paused = true, batch = null))
        assertTrue(MailBacklogRules.shouldApply(approved, paused = false, batch = tidy()))
        assertFalse(MailBacklogRules.shouldApply(approved, paused = false, batch = tidy(asked = now + 6)))
        // Approved before the plan was final (an old device): not applied.
        val early = ready(MailBacklogRules.approveFields(now))
        assertFalse(MailBacklogRules.shouldApply(early, paused = false, batch = null))
        // Finished: nothing more.
        assertFalse(MailBacklogRules.shouldApply(ready(MailBacklogRules.approveFields(now + 5) + MailBacklogRules.progressFields(4052, now, now + 9, 0)), false, null))
    }

    @Test
    fun theCleanUpIsOneTidyThatArchivedForYouLeavesToMail() {
        val b = tidy()
        assertTrue(b.backlog)
        assertEquals("Backlog clean-up · 500 older emails · Personal", b.summary)
        assertEquals("400 newsletters → Navilife/Newsletters · 100 junk → Trash", b.detail)
        assertEquals(500, b.count)
        assertEquals(MailBacklogRules.tidyBatchId("acc1"), MailBacklogRules.tidyBatchId("acc1"))
        assertTrue(MailBacklogRules.tidyBatchId("acc1") != MailBacklogRules.entityId("acc1"))
        // Mail's own tidies list and "tidied N today" leave it out; the backlog row has it.
        val facts = listOf(MailAccountFacts(MailProviders.GMAIL, "a@example.com", "ok", now, "PERSONAL"))
        val plan = ready(MailBacklogRules.approveFields(now + 5) + MailBacklogRules.progressFields(500, now + 10, null, 0))
        val v = MailAccountRules.view(facts, now + 20, emptyList(), MailAutopilotSetting(true, null), false, listOf(b), LocalCalendar.UTC, emptyList(), listOf(plan))
        val row = v.rows.single()
        assertTrue(row.tidies.isEmpty())
        assertEquals("mt1", row.backlog?.undoId)
        assertNull(ArchivedForYouRules.card(listOf(b), null, now + day, LocalCalendar.UTC))
    }

    @Test
    fun approveSyncsToTheOtherAppAndTheBarCanWithdrawIt() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        var n = 0
        val la = ActivityLog(a.replica, { "x${n++}" }, { world.clock.nowMs })
        val lm = ActivityLog(m.replica, { "y${n++}" }, { world.clock.nowMs })
        val f = MailBacklogRules.fields("Personal", world.clock.nowMs - 10, 4264, counts, null, readyAtMs = world.clock.nowMs - 5)
        a.replica.commitLocal(EntityTypes.MAIL_BACKLOG, "mb1", f)
        assertEquals(MailBacklogRules.NOT_APPROVED, la.approveBacklog("nope"))
        assertEquals(MailBacklogRules.APPROVED_BAR, la.approveBacklog("mb1"))
        a.sync(); m.sync()
        val seen = lm.mailBacklogs().single()
        assertTrue(seen.approved)
        assertEquals(MailBacklogRules.APPROVED_BAR, lm.approveBacklog("mb1"))
        assertTrue(lm.unapproveBacklog("mb1"))
        m.sync(); a.sync()
        assertFalse(la.mailBacklogs().single().approved)
        assertFalse(la.unapproveBacklog("mb1"))
    }

    @Test
    fun aPlanMadeByOlderRulesIsMadeAgainUntilApproved() {
        val counts = mapOf(MailBacklogKind.NEWSLETTER to 10, MailBacklogKind.KEEP to 100)
        val f = MailBacklogRules.fields("Personal", now, 110, counts, null, readyAtMs = now)
        val current = MailBacklogRules.read("mb1", f)!!
        assertEquals(MailBacklogRules.PLAN_VERSION, current.planVersion)
        assertFalse(MailBacklogRules.needsReplan(current))
        // Written before the version existed: version 1.
        val old = MailBacklogRules.read("mb1", f - MailBacklogFields.PLAN_VERSION)!!
        assertEquals(1, old.planVersion)
        assertTrue(MailBacklogRules.needsReplan(old))
        assertFalse(MailBacklogRules.needsReplan(null))
        // Approved, or already moving: kept as it is.
        assertFalse(MailBacklogRules.needsReplan(old.copy(approvedAtMs = now)))
        assertFalse(MailBacklogRules.needsReplan(old.copy(applied = 3)))
        // Scanning again writes readiness as 0: not final.
        val scanning = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 0, emptyMap(), null, readyAtMs = null))!!
        assertFalse(scanning.ready)
    }

    @Test
    fun aReadyPlanIsANeedsYouCardWithApproveLaterAndReview() {
        val counts = mapOf(MailBacklogKind.NEWSLETTER to 2000, MailBacklogKind.ARCHIVE to 140, MailBacklogKind.JUNK to 310,
            MailBacklogKind.KEEP to 212)
        val ready = MailBacklogRules.read("mbP", MailBacklogRules.fields("Personal", now, 2662, counts, null, readyAtMs = now))!!
        val scanning = MailBacklogRules.read("mbB", MailBacklogRules.fields("Business", now, 40, counts, null, readyAtMs = null))!!
        val cards = MailBacklogRules.cards(listOf(ready, scanning))
        assertEquals(1, cards.size)
        val c = cards.single()
        assertEquals("mbP", c.id)
        assertEquals(DecisionKind.MAIL_CLEANUP, c.kind)
        assertEquals("Clean up Personal", c.title)
        assertEquals(
            "Archive 2,140 newsletters and promos older than a week · Trash 310 junk · keep 212 that may need you · " +
                "nothing moves until you approve",
            c.why,
        )
        assertEquals(DecisionEffect.APPROVE_BACKLOG, c.effect(DecisionMove.YES))
        assertEquals(DecisionEffect.SET_ASIDE, c.effect(DecisionMove.LATER))
        assertEquals(DecisionEffect.OPEN_REVIEW, c.effect(DecisionMove.OPEN))
        assertEquals("→ Approve · ← Later · ↑ Review", NeedsYouStackRules.hint(c))
        assertEquals(MailBacklogRules.APPROVED_BAR, NeedsYouStackRules.message(c, DecisionMove.YES))
        // Approved, nothing to move, or paused: no card.
        assertTrue(MailBacklogRules.cards(listOf(ready.copy(approvedAtMs = now))).isEmpty())
        assertTrue(MailBacklogRules.cards(listOf(ready.copy(counts = mapOf(MailBacklogKind.KEEP to 5)))).isEmpty())
        assertTrue(MailBacklogRules.cards(listOf(ready), paused = true).isEmpty())
        // The newest plan per account only.
        val older = ready.copy(id = "mbOld", startedAtMs = now - day)
        assertEquals(listOf("mbP"), MailBacklogRules.cards(listOf(older, ready)).map { it.id })
    }

    @Test
    fun theCardsGoAfterTheTasksInNeedsYou() {
        val counts = mapOf(MailBacklogKind.NEWSLETTER to 5)
        val ready = MailBacklogRules.read("mbP", MailBacklogRules.fields("Personal", now, 5, counts, null, readyAtMs = now))!!
        val cal = LocalCalendar.fixedOffset(0)
        val stack = NeedsYouStackRules.build(Today(emptyList(), null, emptyList(), emptyList()), "2 to chase", now, cal, mail = MailBacklogRules.cards(listOf(ready)))
        assertEquals(listOf(DecisionKind.MAIL_CLEANUP, DecisionKind.LISTS), stack.cards.map { it.kind })
    }
}
