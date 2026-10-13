package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailUnsubscribeTest {
    @Test
    fun entriesAreReadFromTheBrackets() {
        assertEquals(
            listOf("mailto:leave@lists.example.com?subject=unsubscribe", "https://example.com/u/abc123"),
            MailUnsubscribeRules.entries("<mailto:leave@lists.example.com?subject=unsubscribe>, <https://example.com/u/abc123>"),
        )
        assertEquals(listOf("https://example.com/u"), MailUnsubscribeRules.entries("https://example.com/u"))
        assertEquals(emptyList(), MailUnsubscribeRules.entries(null))
        assertEquals(emptyList(), MailUnsubscribeRules.entries("  "))
        assertEquals(emptyList(), MailUnsubscribeRules.entries("<unclosed"))
    }

    @Test
    fun oneClickNeedsTheHeaderAndAnHttpsLink() {
        val h = "<mailto:leave@example.com>, <https://example.com/u/abc>"
        val p = MailUnsubscribeRules.plan(h, oneClick = true)
        assertEquals(UnsubscribeMethod.ONE_CLICK, p.method)
        assertEquals("https://example.com/u/abc", p.url)
        assertEquals("leave@example.com", p.mailto?.to)
        // Without the One-Click header, the mailto comes first.
        assertEquals(UnsubscribeMethod.MAILTO, MailUnsubscribeRules.plan(h, oneClick = false).method)
        assertEquals("https://example.com/u/abc", MailUnsubscribeRules.plan(h, oneClick = false).fallbackUrl)
        // One-click said, but only an http (not https) link and no mailto: nothing MEKA can use.
        assertEquals(UnsubscribeMethod.NONE, MailUnsubscribeRules.plan("<http://example.com/u>", oneClick = true).method)
        // Only a page.
        val link = MailUnsubscribeRules.plan("<https://example.com/prefs?id=1>", oneClick = false)
        assertEquals(UnsubscribeMethod.LINK, link.method)
        assertEquals("https://example.com/prefs?id=1", link.url)
        assertEquals(UnsubscribeMethod.NONE, MailUnsubscribeRules.plan(null, oneClick = false).method)
        // Outlook before the headers are read.
        assertEquals(UnsubscribeMethod.CHECK, MailUnsubscribeRules.plan(null, oneClick = false, check = true).method)
    }

    @Test
    fun unsafeLinksAreRefused() {
        for (bad in listOf(
            "http://example.com/u", "https://localhost/u", "https://127.0.0.1/u", "https://10.0.0.5/u", "https://user:pw@example.com/u",
            "https://intranet/u", "https://printer.local/u", "https://example.com:8080/u", "https://exa mple.com/u", "javascript:alert(1)",
            "https://[::1]/u", "https://metadata.google.internal/x", "https://example.com/\"x",
        )) assertNull(MailUnsubscribeRules.safeUrl(bad), bad)
        assertEquals("https://example.com:443/u", MailUnsubscribeRules.safeUrl("https://example.com:443/u"))
        assertEquals("https://e.example.co.uk/u?a=1&b=2", MailUnsubscribeRules.safeUrl("https://e.example.co.uk/u?a=1&b=2"))
        assertNull(MailUnsubscribeRules.safeUrl("https://example.com/" + "a".repeat(MailUnsubscribeRules.MAX_URL)))
    }

    @Test
    fun mailtoKeepsOneAddressAndItsSubjectAndBody() {
        val m = MailUnsubscribeRules.mailto("mailto:Leave-123@Lists.Example.com?subject=Unsubscribe%20me&body=please+remove")
        assertEquals(UnsubscribeMailto("leave-123@lists.example.com", "Unsubscribe me", "please remove"), m)
        assertEquals(UnsubscribeMailto("x@example.com", "unsubscribe", "unsubscribe"), MailUnsubscribeRules.mailto("mailto:x@example.com"))
        // Header injection and several addresses are refused or flattened.
        assertNull(MailUnsubscribeRules.mailto("mailto:a@example.com,b@example.com"))
        assertNull(MailUnsubscribeRules.mailto("mailto:a@example.com%0d%0aBcc:c@example.com"))
        assertEquals("unsub  Bcc: c@example.com", MailUnsubscribeRules.mailto("mailto:a@example.com?subject=unsub%0d%0aBcc:%20c@example.com")?.subject)
        assertNull(MailUnsubscribeRules.mailto("mailto:@example.com"))
        assertNull(MailUnsubscribeRules.mailto("mailto:nobody"))
        assertNull(MailUnsubscribeRules.mailto("https://example.com"))
    }

    @Test
    fun displayNameFallsBackToTheDomain() {
        assertEquals("The Weekly Digest", MailUnsubscribeRules.displayName("\"The Weekly Digest\"", "news@digest.example.com"))
        assertEquals("digest.example.com", MailUnsubscribeRules.displayName(null, "news@digest.example.com"))
        assertEquals("digest.example.com", MailUnsubscribeRules.displayName("news@digest.example.com", "news@digest.example.com"))
        assertEquals("Unknown sender", MailUnsubscribeRules.displayName(null, null))
        assertEquals(MailUnsubscribeRules.senderId("acc", "News@X.com"), MailUnsubscribeRules.senderId("acc", "news@x.com"))
    }

    private val cal = LocalCalendar.UTC
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L

    private fun sender(id: String, f: Map<String, FieldValue>) = assertNotNull(MailUnsubscribeRules.read(id, f))

    @Test
    fun rowsAskAndTheServersOutcome() {
        val base = MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", "digest.example.com", UnsubscribeMethod.ONE_CLICK, now - 3_600_000L, 8)
        val s = sender("ms1", base)
        val none = sender("ms2", MailUnsubscribeRules.senderFields("Personal", "No Way Out", "x.example.com", UnsubscribeMethod.NONE, now, 20))
        val other = sender("ms3", MailUnsubscribeRules.senderFields("Business", "Supplier News", null, UnsubscribeMethod.MAILTO, now - 2 * 86_400_000L, 1))
        val rows = MailSenderPanelRules.rows(listOf(s, none, other), "Personal", now, cal)
        // No way out isn't listed; the other account's sender isn't either.
        assertEquals(listOf("ms1"), rows.map { it.id })
        assertEquals("digest.example.com · 8 emails · last today", rows[0].line)
        assertTrue(rows[0].canUnsubscribe)
        assertNull(rows[0].state)
        assertEquals("1 sender · tap Unsubscribe and Navilife does it for you", MailSenderPanelRules.header(rows))
        assertNull(MailSenderPanelRules.header(emptyList()))
        val biz = MailSenderPanelRules.rows(listOf(other), "Business", now, cal).single()
        assertTrue(biz.line.startsWith("1 email · last "), biz.line)

        // Tap: asked, then nothing more to ask.
        val ask = assertNotNull(MailSenderPanelRules.ask(s, now))
        val asked = sender("ms1", base + ask)
        assertTrue(MailUnsubscribeRules.due(asked))
        assertNull(MailSenderPanelRules.ask(asked, now + 1))
        val askedRow = MailSenderPanelRules.rows(listOf(asked), "Personal", now, cal).single()
        assertFalse(askedRow.canUnsubscribe)
        assertEquals(MailSenderPanelRules.ASKED, askedRow.state)

        // The server: done.
        val done = sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(UnsubscribeState.DONE, now + 60_000))
        assertFalse(MailUnsubscribeRules.due(done))
        val doneRow = MailSenderPanelRules.rows(listOf(done), "Personal", now, cal).single()
        assertTrue(doneRow.done)
        assertFalse(doneRow.canUnsubscribe)
        assertEquals(UnsubscribeState.DONE.note, doneRow.state)
        assertNull(MailSenderPanelRules.ask(done, now + 120_000))

        // A failure can be asked again, which makes it due again.
        val failed = sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(UnsubscribeState.RETRY, now + 60_000))
        assertTrue(MailSenderPanelRules.rows(listOf(failed), "Personal", now, cal).single().canUnsubscribe)
        val again = sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(UnsubscribeState.RETRY, now + 60_000) +
            assertNotNull(MailSenderPanelRules.ask(failed, now + 120_000)))
        assertTrue(MailUnsubscribeRules.due(again))

        // Only a page: Open page with the link, no Unsubscribe.
        val page = sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(UnsubscribeState.OPEN_LINK, now + 60_000, "https://digest.example.com/prefs"))
        val pageRow = MailSenderPanelRules.rows(listOf(page), "Personal", now, cal).single()
        assertEquals("https://digest.example.com/prefs", pageRow.link)
        assertFalse(pageRow.canUnsubscribe)
        // A bad link written by mistake is never handed to the app.
        val bad = sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(UnsubscribeState.OPEN_LINK, now + 60_000) +
            mapOf(MailSenderFields.LINK to FieldValue.Text("http://10.0.0.1/x")))
        assertNull(MailSenderPanelRules.rows(listOf(bad), "Personal", now, cal).single().link)
    }

    @Test
    fun onlyAFinishedUnsubscribeSendsLaterMailToTrash() {
        val base = MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", "digest.example.com", UnsubscribeMethod.ONE_CLICK, now, 8)
        val ask = mapOf(MailSenderFields.ASKED_AT to os.meka.core.sync.FieldValue.Int64(now))
        val tried = now + 60_000
        // Never asked, asked but not tried, or an outcome that didn't unsubscribe: nothing goes to Trash.
        assertFalse(MailUnsubscribeRules.trashes(null, tried + 1))
        assertFalse(MailUnsubscribeRules.trashes(sender("ms1", base), tried + 1))
        assertFalse(MailUnsubscribeRules.trashes(sender("ms1", base + ask), tried + 1))
        for (st in listOf(UnsubscribeState.OPEN_LINK, UnsubscribeState.FAILED, UnsubscribeState.RETRY)) {
            assertFalse(MailUnsubscribeRules.trashes(sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(st, tried)), tried + 1), st.name)
        }
        // Done or sent: mail that came after it goes; mail from before stays.
        for (st in listOf(UnsubscribeState.DONE, UnsubscribeState.SENT)) {
            val done = sender("ms1", base + ask + MailUnsubscribeRules.outcomeFields(st, tried))
            assertTrue(MailUnsubscribeRules.trashes(done, tried + 1), st.name)
            assertFalse(MailUnsubscribeRules.trashes(done, tried), st.name)
        }
        // Mail's row counts what went.
        val done = base + ask + MailUnsubscribeRules.outcomeFields(UnsubscribeState.DONE, tried)
        assertEquals(UnsubscribeState.DONE.note, MailSenderPanelRules.rows(listOf(sender("ms1", done)), "Personal", now, cal).single().state)
        val two = sender("ms1", done + MailUnsubscribeRules.trashedFields(1, 1, tried + 5))
        assertEquals(2, two.trashed)
        assertEquals("${UnsubscribeState.DONE.note} · 2 later emails sent to Trash",
            MailSenderPanelRules.rows(listOf(two), "Personal", now, cal).single().state)
        assertEquals("1 later email sent to Trash", MailSenderPanelRules.trashedLine(1))
    }

    @Test
    fun busiestSendersFirstAndAtMostEight() {
        val many = (1..12).map { i -> sender("ms$i", MailUnsubscribeRules.senderFields("Personal", "S$i", null, UnsubscribeMethod.MAILTO, now - i, i)) }
        val rows = MailSenderPanelRules.rows(many, "Personal", now, cal)
        assertEquals(MailSenderPanelRules.SHOWN, rows.size)
        assertEquals("S12", rows.first().name)
    }

    @Test
    fun activityShowsWhatWasTried() {
        val base = MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", null, UnsubscribeMethod.MAILTO, now, 3)
        assertNull(ActivityRules.unsubscribeItem(sender("ms1", base)))
        val sent = assertNotNull(ActivityRules.unsubscribeItem(sender("ms1", base + MailUnsubscribeRules.outcomeFields(UnsubscribeState.SENT, now))))
        assertEquals("Unsubscribed from Weekly Digest · Personal", sent.summary)
        assertFalse(sent.canUndo)
        val failed = assertNotNull(ActivityRules.unsubscribeItem(sender("ms1", base + MailUnsubscribeRules.outcomeFields(UnsubscribeState.FAILED, now))))
        assertEquals("Couldn't unsubscribe from Weekly Digest · Personal", failed.summary)
    }
}
