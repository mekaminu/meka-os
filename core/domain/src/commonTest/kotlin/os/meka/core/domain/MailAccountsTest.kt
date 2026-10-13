package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        assertEquals("Sign-in ended · Reconnect to keep Navilife reading it", r.line)
        assertEquals("Couldn't reach it last time · Navilife tries again every few minutes", MailAccountRules.line("error", now, now))
        assertEquals("1 day ago", MailAccountRules.ago(now - 30 * 60 * min, now))
        assertEquals("3 days ago", MailAccountRules.ago(now - 3 * 24 * 60 * min, now))
        assertEquals("just now", MailAccountRules.ago(now + min, now))
    }

    // ---- Slice 1b ----

    private val outlook = MailAccountFacts(MailProviders.OUTLOOK, "alex@example.net", "ok", now, "OUTLOOK")

    @Test
    fun anEndingGoogleSignInSaysWhenWithReconnect() {
        val a = gmail("alex@example.com", "PERSONAL")
        val ending = SignIn(MailProviders.GMAIL, "Alex@Example.com", SignInState.ENDING, now + 5 * 60 * min + 10 * min)
        val row = MailAccountRules.view(listOf(a, outlook), now, listOf(ending)).rows[0]
        assertEquals("Sign-in ends in 5 h · Reconnect to keep Navilife reading it", row.line)
        assertTrue(row.needsReconnect)
        assertTrue(row.ending)
        // Within the hour; and once it has passed, the server's own state decides (nothing ending any more).
        assertEquals("Sign-in ends within the hour · Reconnect to keep Navilife reading it",
            MailAccountRules.view(listOf(a), now, listOf(ending.copy(untilMs = now + 20 * min))).rows[0].line)
        val passed = MailAccountRules.view(listOf(a), now, listOf(ending.copy(untilMs = now - min))).rows[0]
        assertFalse(passed.ending)
        assertFalse(passed.needsReconnect)
        // Another account's sign-in doesn't count; an ended one keeps its own line.
        assertFalse(MailAccountRules.view(listOf(a), now, listOf(ending.copy(account = "other@example.com"))).rows[0].ending)
        val ended = MailAccountRules.view(listOf(a.copy(status = "needs_reconnect")), now, listOf(ending)).rows[0]
        assertEquals("Sign-in ended · Reconnect to keep Navilife reading it", ended.line)
        assertFalse(ended.ending)
    }

    @Test
    fun aDisconnectedAccountIsGoneFromThePane() {
        val v = MailAccountRules.view(listOf(gmail("alex@example.com", "PERSONAL", status = SignInRules.DISCONNECTED), outlook), now)
        assertEquals(listOf("Outlook · Outlook"), v.rows.map { it.title })
        val row = MailAccountRules.view(listOf(gmail("alex@example.com", "PERSONAL")), now).rows[0]
        assertEquals(
            "Disconnect Personal · Gmail (alex@example.com)? Navilife stops reading it and forgets the sign-in. Nothing in the mailbox changes.",
            MailAccountRules.disconnectAsk(row),
        )
        assertTrue(MailAccountRules.disconnectedLine(MailProviders.GMAIL, "alex@example.com").startsWith("Disconnected alex@example.com · Navilife asked Google"))
        assertTrue(MailAccountRules.disconnectedLine(MailProviders.OUTLOOK, "alex@example.net").contains("account.microsoft.com"))
    }

    @Test
    fun setupsMailStepFollowsTheThreeAccounts() {
        assertEquals(SetupState.UNKNOWN, MailAccountRules.setupStep(null, now).state)
        val none = MailAccountRules.setupStep(emptyList(), now)
        assertEquals(SetupState.TODO, none.state)
        assertEquals(SetupFix.MAIL, none.fix)
        assertEquals("Connect", none.fixLabel)
        val some = MailAccountRules.setupStep(listOf(gmail("alex@example.com", "PERSONAL")), now)
        assertEquals("1 connected · Still to connect: Outlook · the business Gmail", some.line)
        assertEquals(SetupState.TODO, some.state)
        val all = listOf(gmail("alex@example.com", "PERSONAL"), gmail("alex.shop@example.com", "BUSINESS"), outlook)
        val done = MailAccountRules.setupStep(all, now)
        assertEquals(SetupState.DONE, done.state)
        assertEquals("Connected · Personal · Business · Outlook", done.line)
        assertNull(done.fix)
        val ended = MailAccountRules.setupStep(all.map { if (it.label == "BUSINESS") it.copy(status = "needs_reconnect") else it }, now)
        assertEquals("Sign-in ended · Business · Gmail · Reconnect in Mail", ended.line)
        assertEquals("Reconnect", ended.fixLabel)
        // In place of the placeholder (same key), so Setup counts it.
        val f = SetupFacts(SetupDevice(mac = true), connected = true, server = null, accounts = emptyList(), ai = null,
            callAssistantOn = false, voiceMessageSeen = false, voiceChosen = null, nowMs = now)
        val v = SetupRules.replaceStep(SetupRules.view(f), none)
        assertEquals(SetupState.TODO, v.steps.first { it.key == MailAccountRules.SETUP_KEY }.state)
        assertEquals(SetupRules.view(f).toDo + 1, v.toDo)
    }

    @Test
    fun eachAccountListsItsOwnSendersWithUnsubscribe() {
        val sender = assertNotNull(MailUnsubscribeRules.read("ms1",
            MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", "digest.example", UnsubscribeMethod.MAILTO, now - 60_000, 3)))
        val other = assertNotNull(MailUnsubscribeRules.read("ms2",
            MailUnsubscribeRules.senderFields("Business", "Supplier", "supplier.example", UnsubscribeMethod.MAILTO, now - 60_000, 1)))
        val v = MailAccountRules.view(
            listOf(gmail("alex@example.com", "PERSONAL")), now, emptyList(), MailAutopilotRules.read(null), false, emptyList(), LocalCalendar.UTC,
            listOf(sender, other),
        )
        val row = v.rows.single()
        assertEquals(listOf("ms1"), row.senders.map { it.id })
        assertEquals("1 sender · tap Unsubscribe and Navilife does it for you", row.sendersLine)
        // Without senders (an older caller) there's no section.
        assertNull(MailAccountRules.view(listOf(gmail("alex@example.com", "PERSONAL")), now, emptyList(), MailAutopilotRules.read(null), false, emptyList(), LocalCalendar.UTC).rows.single().sendersLine)
    }
}
