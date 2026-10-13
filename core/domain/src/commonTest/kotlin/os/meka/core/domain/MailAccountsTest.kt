package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailAccountsTest {
    private val now = 1_790_985_600_000L
    private val min = 60_000L

    private fun gmail(email: String, label: String? = null, status: String = "ok", synced: Long? = now - 4 * min) =
        MailAccountFacts(MailProviders.GMAIL, email, status, synced, label)

    @Test
    fun onlyMailAccountsAreMail() {
        assertTrue(MailProviders.isMail("gmail"))
        assertTrue(MailProviders.isMail("outlook_mail"))
        assertFalse(MailProviders.isMail("google"))
        assertFalse(MailProviders.isMail("microsoft"))
        assertFalse(MailProviders.isMail("weather"))
    }

    @Test
    fun aNewAccountsLabelFollowsWhatsThere() {
        assertEquals(MailLabel.PERSONAL, MailAccountRules.defaultLabel(MailProviders.GMAIL, emptyList()))
        assertEquals(MailLabel.BUSINESS, MailAccountRules.defaultLabel(MailProviders.GMAIL, listOf(MailLabel.PERSONAL)))
        assertEquals(MailLabel.OUTLOOK, MailAccountRules.defaultLabel(MailProviders.OUTLOOK, emptyList()))
        assertEquals(MailLabel.OUTLOOK, MailAccountRules.defaultLabel(MailProviders.OUTLOOK, listOf(MailLabel.PERSONAL)))
        assertEquals(MailLabel.BUSINESS, MailLabel.of("BUSINESS"))
        assertNull(MailLabel.of("Work"))
    }

    @Test
    fun theViewListsMailOnlyLabelledAndInOrder() {
        val v = MailAccountRules.view(
            listOf(
                MailAccountFacts("google", "alex@example.com", "ok", now, null), // a calendar account: not here
                MailAccountFacts(MailProviders.OUTLOOK, "alex@example.net", "ok", now - 2 * 60 * min, null),
                gmail("alex.shop@example.com", "BUSINESS"),
                gmail("alex@example.com", "PERSONAL"),
            ),
            now,
        )
        assertEquals(listOf("Personal · Gmail", "Business · Gmail", "Outlook · Outlook"), v.rows.map { it.title })
        assertEquals("Connected · checked 4 min ago · sorting comes in the next update", v.rows[0].line)
        assertEquals("Connected · checked 2 h ago · sorting comes in the next update", v.rows[2].line)
        assertEquals(listOf(MailLabel.BUSINESS, MailLabel.PERSONAL, MailLabel.OUTLOOK), v.rows[1].labels)
        assertNull(v.emptyLine)
        assertNull(v.toConnect)
        assertEquals(listOf("Connect Gmail", "Connect Outlook"), v.connects.map { it.label })
        assertTrue(v.connects[0].note.contains("sends only when you tap Send"))
    }

    @Test
    fun emptyAndPartlyConnected() {
        val empty = MailAccountRules.view(emptyList(), now)
        assertEquals(MailAccountRules.EMPTY, empty.emptyLine)
        assertNull(empty.toConnect)
        // One Gmail without a stored label reads as Personal, and the rest is still to connect.
        val one = MailAccountRules.view(listOf(gmail("alex@example.com", synced = null)), now)
        assertEquals("Personal · Gmail", one.rows.single().title)
        assertEquals("Connected · checking the sign-in…", one.rows.single().line)
        assertEquals("Still to connect: Outlook · the business Gmail", one.toConnect)
        // A second unlabelled Gmail reads as Business.
        val two = MailAccountRules.view(listOf(gmail("alex@example.com"), gmail("shop@example.com")), now)
        assertEquals(listOf(MailLabel.PERSONAL, MailLabel.BUSINESS), two.rows.map { it.label })
    }

    @Test
    fun anEndedSignInSaysReconnect() {
        val r = MailAccountRules.view(listOf(gmail("alex@example.com", "PERSONAL", status = "needs_reconnect")), now).rows.single()
        assertTrue(r.needsReconnect)
        assertEquals("Sign-in ended · Reconnect to keep MEKA reading it", r.line)
        assertEquals("Couldn't reach it last time · MEKA tries again every few minutes", MailAccountRules.line("error", now, now))
        assertEquals("1 day ago", MailAccountRules.ago(now - 30 * 60 * min, now))
        assertEquals("3 days ago", MailAccountRules.ago(now - 3 * 24 * 60 * min, now))
        assertEquals("just now", MailAccountRules.ago(now + min, now))
    }
}
