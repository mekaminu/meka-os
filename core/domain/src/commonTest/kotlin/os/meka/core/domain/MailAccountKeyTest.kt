package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox fixes, slice 5: each account shows only its own tidies, one shared rules list, and the Mark-read switch. */
class MailAccountKeyTest {
    private val hour = 3_600_000L
    private val today = 20_000L * 86_400_000L
    private val now = today + 15 * hour
    private val on = MailAutopilotSetting(true, null)

    private fun facts(provider: String, email: String, label: String) = MailAccountFacts(provider, email, "ok", now - 60_000L, label)

    private fun batch(id: String, account: String, key: String?, count: Int = 3) =
        MailTidyBatch(id, today + 9 * hour, account, "Tidied $count emails · $account", "3 newsletters → Navilife/Newsletters", count, null, null, null, accountKey = key)

    private fun sender(id: String, account: String, key: String?, name: String, rule: MailSenderRule?) =
        MailSender(id, account, name, "example.com", UnsubscribeMethod.ONE_CLICK, today, 4, null, null, null, null, null, rule = rule, accountKey = key)

    private val gmail = facts(MailProviders.GMAIL, "alex@example.com", "PERSONAL")
    private val work = facts(MailProviders.GMAIL, "alex@example.org", "BUSINESS")
    // The Outlook account relabelled Personal: the same label as the Gmail one.
    private val outlook = facts(MailProviders.OUTLOOK, "alex@example.net", "PERSONAL")
    private val gKey = MailAccountRules.key(MailProviders.GMAIL, "alex@example.com")
    private val oKey = MailAccountRules.key(MailProviders.OUTLOOK, "alex@example.net")

    @Test
    fun theKeyIsAStableHashNeverTheAddress() {
        assertEquals(gKey, MailAccountRules.key(MailProviders.GMAIL, " Alex@Example.com "))
        assertNotEquals(gKey, MailAccountRules.key(MailProviders.OUTLOOK, "alex@example.com"))
        assertFalse("example" in gKey)
        assertTrue(gKey.startsWith("ma"))
    }

    @Test
    fun aKeyedTidyShowsOnlyOnItsOwnCardEvenWhenTwoAccountsShareALabel() {
        val v = MailAccountRules.view(
            listOf(gmail, outlook), now, emptyList(), on, false,
            listOf(batch("g", "Personal", gKey), batch("o", "Personal", oKey, count = 2)), LocalCalendar.UTC,
        )
        val g = v.rows.single { it.provider == MailProviders.GMAIL }
        val o = v.rows.single { it.provider == MailProviders.OUTLOOK }
        assertEquals(listOf("g"), g.tidies.map { it.id })
        assertEquals(listOf("o"), o.tidies.map { it.id })
        assertEquals("Autopilot · on · tidied 3 today", g.tidyLine)
        assertEquals("Autopilot · on · tidied 2 today", o.tidyLine)
    }

    @Test
    fun anOlderTidyWithoutAKeyIsListedOnceOnTheFirstCardWithItsLabel() {
        val v = MailAccountRules.view(listOf(gmail, outlook), now, emptyList(), on, false, listOf(batch("old", "Personal", null)), LocalCalendar.UTC)
        assertEquals(1, v.rows.sumOf { it.tidies.size })
        assertEquals(listOf("old"), v.rows.first().tidies.map { it.id })
    }

    @Test
    fun aRelabelledAccountKeepsItsKeyedHistory() {
        // Written while the business account was labelled Personal; now it's Business.
        val v = MailAccountRules.view(listOf(gmail, work), now, emptyList(), on, false,
            listOf(batch("b", "Personal", MailAccountRules.key(MailProviders.GMAIL, "alex@example.org"))), LocalCalendar.UTC)
        assertTrue(v.rows.single { it.label == MailLabel.PERSONAL }.tidies.isEmpty())
        assertEquals(listOf("b"), v.rows.single { it.label == MailLabel.BUSINESS }.tidies.map { it.id })
    }

    @Test
    fun newsletterSendersStayWithTheirOwnAccount() {
        val v = MailAccountRules.view(listOf(gmail, outlook), now, emptyList(), on, false, emptyList(), LocalCalendar.UTC,
            senders = listOf(sender("s1", "Personal", gKey, "Weekly Digest", null), sender("s2", "Personal", oKey, "Shop News", null)))
        assertEquals(listOf("Weekly Digest"), v.rows.single { it.provider == MailProviders.GMAIL }.senders.map { it.name })
        assertEquals(listOf("Shop News"), v.rows.single { it.provider == MailProviders.OUTLOOK }.senders.map { it.name })
    }

    @Test
    fun rulesAreOneSharedListNamingTheirAccount() {
        val v = MailAccountRules.view(listOf(gmail, work), now, emptyList(), on, false, emptyList(), LocalCalendar.UTC,
            senders = listOf(
                sender("s1", "Personal", gKey, "Sam Taylor", MailSenderRule.KEEP),
                sender("s2", "Business", MailAccountRules.key(MailProviders.GMAIL, "alex@example.org"), "Acme Offers", MailSenderRule.TIDY),
                sender("s3", "Business", null, "Plain News", null),
            ))
        assertTrue(v.rows.all { it.rules.isEmpty() && it.rulesLine == null })
        assertEquals("Your rules · 2 senders", v.rulesLine)
        assertEquals(listOf("Acme Offers", "Sam Taylor"), v.rules.map { it.name })
        assertTrue(v.rules[0].line.endsWith(" · Business"))
        assertTrue(v.rules[1].line.endsWith(" · Personal"))
    }

    @Test
    fun withOneAccountTheRulesDontNameIt() {
        val v = MailAccountRules.view(listOf(gmail), now, emptyList(), on, false, emptyList(), LocalCalendar.UTC,
            senders = listOf(sender("s1", "Personal", gKey, "Sam Taylor", MailSenderRule.KEEP)))
        assertEquals("Your rules · 1 sender", v.rulesLine)
        assertFalse(v.rules.single().line.endsWith(" · Personal"))
        assertNull(MailAccountRules.view(listOf(gmail), now, emptyList(), on, false, emptyList(), LocalCalendar.UTC).rulesLine)
    }

    @Test
    fun theMarkReadSwitchIsOnUnlessTurnedOff() {
        val p = MailAutopilotPanelRules.panel(MailAutopilotRules.read(null), paused = false)
        assertTrue(p.markRead)
        assertEquals(MailAutopilotPanelRules.MARK_READ_ON_LINE, p.markReadLine)
        assertEquals("Turn off", p.markReadAction)
        val off = MailAutopilotPanelRules.panel(MailAutopilotRules.read(MailAutopilotRules.markReadFields(false)), paused = false)
        assertFalse(off.markRead)
        assertEquals(MailAutopilotPanelRules.MARK_READ_OFF_LINE, off.markReadLine)
        assertEquals("Turn on", off.markReadAction)
        assertEquals("Tidied mail keeps its unread mark from Navilife's next check", MailAutopilotPanelRules.markReadSwitchedLine(false))
    }

    @Test
    fun aBatchAndASenderReadTheirKey() {
        val b = MailAutopilotRules.readBatch("t", mapOf(
            MailTidyBatchFields.AT to FieldValue.Int64(today), MailTidyBatchFields.SUMMARY to FieldValue.Text("Tidied 1 email · Personal"),
            MailTidyBatchFields.ACCOUNT to FieldValue.Text("Personal"), MailTidyBatchFields.ACCOUNT_KEY to FieldValue.Text(gKey),
        ))
        assertEquals(gKey, b?.accountKey)
        val s = MailUnsubscribeRules.read("s", mapOf(
            MailSenderFields.ACCOUNT to FieldValue.Text("Personal"), MailSenderFields.NAME to FieldValue.Text("Sam"),
            MailSenderFields.ACCOUNT_KEY to FieldValue.Text(oKey),
        ))
        assertEquals(oKey, s?.accountKey)
        assertNull(MailUnsubscribeRules.read("s", mapOf(MailSenderFields.ACCOUNT to FieldValue.Text("Personal"), MailSenderFields.NAME to FieldValue.Text("Sam")))?.accountKey)
    }
}
