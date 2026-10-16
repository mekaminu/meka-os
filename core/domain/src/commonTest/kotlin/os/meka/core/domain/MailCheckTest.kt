package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailCheckTest {
    private val now = 1_790_985_600_000L
    private val min = 60_000L

    @Test
    fun failuresAreSortedByWhatTheProviderSaid() {
        assertEquals(MailCheckRules.BUSY, MailCheckRules.classify(429, network = false, duringSignIn = false))
        assertEquals(MailCheckRules.REFUSED, MailCheckRules.classify(403, false, false))
        assertEquals(MailCheckRules.DOWN, MailCheckRules.classify(503, false, true))
        assertEquals(MailCheckRules.NETWORK, MailCheckRules.classify(null, true, true))
        assertEquals(MailCheckRules.SIGN_IN, MailCheckRules.classify(400, false, true))
        assertEquals(MailCheckRules.OTHER, MailCheckRules.classify(null, false, false))
        // Inbox fixes, slice 7: a 403's reason word tells "API off" and "no permission" from a rate limit.
        assertEquals(MailCheckRules.API_OFF, MailCheckRules.classify(403, false, false, "accessNotConfigured"))
        assertEquals(MailCheckRules.API_OFF, MailCheckRules.classify(403, false, false, "SERVICE_DISABLED"))
        assertEquals(MailCheckRules.NO_PERMISSION, MailCheckRules.classify(403, false, false, "insufficientPermissions"))
        assertEquals(MailCheckRules.BUSY, MailCheckRules.classify(403, false, false, "userRateLimitExceeded"))
        assertEquals(MailCheckRules.REFUSED, MailCheckRules.classify(403, false, false, "somethingElse"))
        assertEquals(MailCheckRules.API_OFF, MailCheckRules.read("apioff"))
        // An older server's exception name isn't a reason.
        assertNull(MailCheckRules.read("IllegalStateException"))
        assertEquals(MailCheckRules.BUSY, MailCheckRules.read(" Busy "))
    }

    @Test
    fun theCardSaysTheRealReasonAndWhenItLastWorked() {
        assertEquals(
            "Gmail is busy (too many requests) · Navilife tries again in a few minutes · last worked 20 min ago",
            MailCheckRules.errorLine(MailProviders.GMAIL, MailCheckRules.BUSY, now - 20 * min, now),
        )
        assertEquals(
            "Google sign-in didn't refresh · Navilife tries again · last worked 1 day ago · Reconnect if it carries on",
            MailCheckRules.errorLine(MailProviders.GMAIL, MailCheckRules.SIGN_IN, now - 25 * 60 * min, now),
        )
        assertEquals(
            "Microsoft sign-in didn't refresh · Navilife tries again · last worked 2 h ago",
            MailCheckRules.errorLine(MailProviders.OUTLOOK, MailCheckRules.SIGN_IN, now - 120 * min, now),
        )
        assertTrue(MailCheckRules.errorLine(MailProviders.GMAIL, MailCheckRules.REFUSED, null, now).contains("Gmail API is off"))
        assertTrue(MailCheckRules.errorLine(MailProviders.OUTLOOK, MailCheckRules.DOWN, null, now).startsWith("Outlook's servers had a problem"))
        // Inbox fixes, slice 7: the API switched off, and a missing permission, said as what to do.
        assertEquals(
            "The Gmail API is off in Navilife's Google Cloud project · turn it on: Google Cloud console → APIs & Services → Library → Gmail API → Enable",
            MailCheckRules.errorLine(MailProviders.GMAIL, MailCheckRules.API_OFF, null, now),
        )
        assertEquals(
            "Google didn't give Navilife permission to read this mail · Reconnect and keep every permission ticked",
            MailCheckRules.errorLine(MailProviders.GMAIL, MailCheckRules.NO_PERMISSION, null, now),
        )
        // A busy provider never asks him to reconnect, however long it lasts.
        assertFalse(MailCheckRules.errorLine(MailProviders.GMAIL, MailCheckRules.BUSY, now - 3 * 24 * 60 * min, now).contains("Reconnect"))
    }

    @Test
    fun theAccountRowUsesTheReasonAndKeepsTheOldLineWithoutOne() {
        val busy = MailAccountFacts(MailProviders.GMAIL, "alex@example.com", "error", now - 5 * min, "PERSONAL", why = MailCheckRules.BUSY)
        assertEquals(
            "Gmail is busy (too many requests) · Navilife tries again in a few minutes · last worked 5 min ago",
            MailAccountRules.row(busy, emptyList(), now).line,
        )
        assertFalse(MailAccountRules.row(busy, emptyList(), now).needsReconnect)
        assertEquals(
            "Couldn't reach it last time · Navilife tries again every few minutes",
            MailAccountRules.row(busy.copy(why = null), emptyList(), now).line,
        )
        // A reason left over on a working account is ignored.
        assertEquals("Connected · checked 5 min ago · sorting comes in the next update", MailAccountRules.row(busy.copy(status = "ok"), emptyList(), now).line)
    }

    @Test
    fun theReportLineSaysEverythingButTheAddress() {
        val r = MailCheckRules.Report(
            label = "Business", provider = MailProviders.GMAIL, status = "error", why = MailCheckRules.REFUSED,
            lastCheckAtMs = now, lastOkAtMs = now - 60 * min, inboxTotal = 88, inboxUnread = 31,
            dryRunEndsAtMs = now + 60 * min, autopilotOn = true, paused = false, autopilot = "not tidying",
            sorted = mapOf("NEEDS_YOU" to 12, "PROMO" to 0), wouldTidy = mapOf("ARCHIVE" to 40, "KEEP" to 12),
            stageFailures = mapOf("junkReview" to "busy", "backlog" to "down"),
        )
        val utc = { ms: Long -> if (ms == now) "T0" else "T?" }
        assertEquals(
            "Gmail · error (refused) · checked T0 · ok T? · inbox 88 · 31 unread · autopilot on, dry run until T?: not tidying · " +
                "sorted needs_you 12 · would tidy 40 · failed: backlog=down junkReview=busy",
            MailCheckRules.reportLine(r, now, utc),
        )
        assertTrue(MailCheckRules.reportLine(r.copy(autopilotOn = false, autopilot = null), now, utc).contains("autopilot off"))
        assertTrue(MailCheckRules.reportLine(r.copy(paused = true), now, utc).contains("autopilot paused"))
        assertTrue(MailCheckRules.reportLine(r.copy(dryRunEndsAtMs = null), now, utc).contains("on, no sign-in time"))
        assertTrue(MailCheckRules.reportLine(r.copy(dryRunEndsAtMs = now - 1), now, utc).contains("autopilot on: not tidying"))
    }
}
