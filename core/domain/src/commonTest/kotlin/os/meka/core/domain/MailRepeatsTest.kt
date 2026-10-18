package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The inbox item (2026-10-18): the same notification email again and again is grouped in Archived for you. */
class MailRepeatsTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private fun at(minute: Int, d: Long = day) = cal.toEpochMs(d, minute)

    private fun email(id: String, from: String, subject: String, minute: Int) = MailTidiedEmail(
        MailTidiedRules.key("acc1", id), MailTidy.ARCHIVE, MailSort.NOTIFICATION, at(minute), from, "example.com", subject, null,
    )

    private fun batch(id: String, atMs: Long, emails: List<MailTidiedEmail>): MailTidyBatch {
        val fields = MailAutopilotRules.batchFields(atMs, "Personal", mapOf(MailTidy.ARCHIVE to emails.size)) +
            MailTidiedRules.fields(emails) + (MailTidyBatchFields.COUNT to FieldValue.Int64(emails.size.toLong()))
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    private fun ci(n: Int, minute: Int) = email("ci$n", "GitHub", "[acme/app] Run failed: ci - main (${"a1b2c3d" + n})", minute)

    @Test
    fun subjectsDifferingOnlyInNumbersAreTheSameEmailAgain() {
        assertEquals(MailTidiedRules.repeatKey("[acme/app] Run failed: ci - main (a1b2c3d4)"), MailTidiedRules.repeatKey("[acme/app] Run failed: ci - main (9f8e7d6)"))
        assertEquals(MailTidiedRules.repeatKey("Order 1234 shipped"), MailTidiedRules.repeatKey("Order 98 shipped"))
        assertTrue(MailTidiedRules.repeatKey("Free delivery") != MailTidiedRules.repeatKey("20% off"))
        // Too little left to tell: never collapsed.
        assertNull(MailTidiedRules.repeatKey("12345"))
        assertNull(MailTidiedRules.repeatKey("Hi"))
        assertNull(MailTidiedRules.repeatKey("(no subject)".replace("subject", "x1")))
    }

    @Test
    fun withinATidyTheSameEmailIsOneRowWithHowManyTimes() {
        val es = (1..6).map { ci(it, 9 * 60 + it) } + email("v1", "GitHub", "Your weekly digest", 8 * 60)
        val b = batch("t", at(10 * 60), es)
        val (groups, more) = MailTidiedRules.groups(b, b.emails, at(12 * 60 + 30), cal)
        assertNull(more)
        val g = groups.single()
        assertEquals("GitHub · 7 notifications", g.title)
        assertEquals(2, g.rows.size)
        assertEquals("[acme/app] Run failed: ci - main (a1b2c3d6)", g.rows[0].subject) // the newest
        assertEquals("Notification · 6 times", g.rows[0].chip)
        assertEquals("09:06", g.rows[0].time)
        assertEquals("Notification", g.rows[1].chip)
        assertNull(g.moreLine)
    }

    @Test
    fun theCardSaysWhatCameAgainAcrossTidies() {
        // Each check tidies what came since the last: one CI failure per tidy.
        val tidies = (1..6).map { batch("b$it", at(9 * 60 + it * 10 + 1), listOf(ci(it, 9 * 60 + it * 10))) } +
            batch("v", at(11 * 60), listOf(email("x1", "Vinted", "Someone favourited your item", 10 * 60 + 50)))
        val card = assertNotNull(ArchivedForYouRules.card(tidies, null, at(12 * 60 + 30), cal))
        assertEquals(
            listOf("Came again · GitHub · “[acme/app] Run failed: ci - main (a1b2c3d6)” · 6 times since 09:10"),
            card.repeats,
        )
        assertTrue(card.spoken.endsWith("6 times since 09:10."))
        // Two of them isn't "again and again"; nor is the same email inside one tidy only.
        assertTrue(assertNotNull(ArchivedForYouRules.card(tidies.take(2), null, at(12 * 60 + 30), cal)).repeats.isEmpty())
        val one = batch("one", at(10 * 60), (1..4).map { ci(it, 9 * 60 + it) })
        assertTrue(assertNotNull(ArchivedForYouRules.card(listOf(one), null, at(12 * 60 + 30), cal)).repeats.isEmpty())
    }

    @Test
    fun aLongSubjectIsCutAndPutBackTidiesDontCount() {
        val long = "Weekly report for the shared team workspace with every update included"
        val tidies = (1..3).map { batch("b$it", at(9 * 60 + it * 10 + 1), listOf(email("r$it", "Reports", long, 9 * 60 + it * 10))) }
        val line = assertNotNull(ArchivedForYouRules.card(tidies, null, at(12 * 60 + 30), cal)).repeats.single()
        assertTrue(line.contains("“" + long.take(MailTidiedRules.MAX_REPEAT_SUBJECT - 1).trimEnd() + "…”"))
        val back = tidies.mapIndexed { i, b -> if (i == 0) b.copy(undoneAtMs = at(11 * 60)) else b }
        assertTrue(assertNotNull(ArchivedForYouRules.card(back, null, at(12 * 60 + 30), cal)).repeats.isEmpty())
    }
}
