package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsYouGroupsTest {
    private val R = NeedsYouGroupRules

    @Test fun toDecideAndMessagesStartOpen() {
        assertEquals(setOf(NeedsYouGroup.DECIDE, NeedsYouGroup.MESSAGES), R.DEFAULT_OPEN)
        assertEquals(R.DEFAULT_OPEN, R.decode(null))
    }

    @Test fun whatHeLeftOpenIsKept() {
        val open = setOf(NeedsYouGroup.FYI, NeedsYouGroup.TIDIED)
        assertEquals("fyi,tidy", R.encode(open))
        assertEquals(open, R.decode("fyi,tidy"))
        // All closed is kept as closed, never back to the defaults.
        assertEquals(emptySet(), R.decode(""))
        assertEquals(setOf(NeedsYouGroup.MAIL), R.decode("mail, gone ,"))
    }

    @Test fun theOpenFoldSplitsTheBoxesIntoTwoColumns() {
        assertEquals(listOf(NeedsYouGroup.DECIDE, NeedsYouGroup.MESSAGES), NeedsYouGroup.entries.filter { R.side(it) == NeedsYouPart.LEFT })
        assertEquals(listOf(NeedsYouGroup.FYI, NeedsYouGroup.CHATS, NeedsYouGroup.MAIL, NeedsYouGroup.RENEWALS, NeedsYouGroup.TIDIED),
            NeedsYouGroup.entries.filter { R.side(it) == NeedsYouPart.RIGHT })
        NeedsYouGroup.entries.forEach { g ->
            assertTrue(R.inPart(NeedsYouPart.ALL, g))
            // Each box draws in exactly one column, never both.
            assertTrue(R.inPart(NeedsYouPart.LEFT, g) != R.inPart(NeedsYouPart.RIGHT, g))
        }
        // A chip's groups share a column, so its list opens together.
        NeedsYouFilter.entries.filter { it != NeedsYouFilter.ALL }.forEach { f ->
            assertEquals(1, NeedsYouGroup.entries.filter { R.shows(f, it) }.map(R::side).distinct().size, "$f")
        }
    }

    @Test fun aTapOpensOrCloses() {
        val once = R.toggle(R.DEFAULT_OPEN, NeedsYouGroup.FYI)
        assertTrue(NeedsYouGroup.FYI in once)
        assertFalse(NeedsYouGroup.FYI in R.toggle(once, NeedsYouGroup.FYI))
    }

    @Test fun openAllAndCloseAll() {
        val shown = listOf(NeedsYouGroup.DECIDE, NeedsYouGroup.MESSAGES, NeedsYouGroup.FYI)
        assertEquals(R.CLOSE_ALL, R.allLabel(R.DEFAULT_OPEN, shown))
        val closed = R.all(R.DEFAULT_OPEN, shown)
        assertEquals(emptySet(), closed)
        assertEquals(R.OPEN_ALL, R.allLabel(closed, shown))
        assertEquals(shown.toSet(), R.all(closed, shown))
    }

    @Test fun aChipOpensJustItsGroups() {
        assertTrue(R.shows(NeedsYouFilter.FYI, NeedsYouGroup.CHATS))
        assertFalse(R.shows(NeedsYouFilter.FYI, NeedsYouGroup.MESSAGES))
        assertTrue(R.shows(NeedsYouFilter.MAIL, NeedsYouGroup.RENEWALS))
        val opened = R.pick(emptySet(), NeedsYouFilter.MAIL)
        assertEquals(setOf(NeedsYouGroup.MAIL, NeedsYouGroup.RENEWALS, NeedsYouGroup.TIDIED), opened)
        assertEquals(emptySet(), R.pick(emptySet(), NeedsYouFilter.ALL))
    }

    @Test fun longGroupsShowFiveAPage() {
        val first = R.page(14, 0)
        assertEquals("1–5 of 14", first.label)
        assertEquals(3, first.pages)
        assertFalse(first.hasPrev)
        assertTrue(first.hasNext)
        val last = R.page(14, 9)
        assertEquals(2, last.page)
        assertEquals("11–14 of 14", last.label)
        assertEquals(listOf(11, 12, 13, 14), last.of((1..14).toList()))
        assertEquals("", R.page(5, 0).label)
        assertEquals(1, R.page(0, 3).pages)
    }

    @Test fun peeksSayWhatIsInside() {
        assertEquals("Alex, Card game testing, Sam, Jo", R.names(listOf("Alex", "Card game testing", "Sam", "Jo")))
        assertEquals("A, B, C, D + 2 more", R.names(listOf("A", "B", "C", "D", "E", "F")))
        assertEquals("No reply needed · Alex, Sam, Jo + 1 more", R.fyiPeek(listOf("Alex", "Sam", "Jo", "Kim")))
        assertEquals("1 approval · 2 requests · 1 from your lists", R.decidePeek(1, 2, 0, 1))
        assertEquals("Streamly · nothing added until you tap Add", R.renewalsPeek(listOf("Streamly")))
        val tiles = listOf(NeedsYouMailTile("41", "tidied", false), NeedsYouMailTile("5", "junk & scams", true))
        assertEquals("41 tidied · 5 junk & scams", R.tidiedPeek(tiles))
    }

    @Test fun aHeadPeeksWhileClosedAndShowsItsPageWhileOpen() {
        val closed = R.head(NeedsYouGroup.FYI, 6, "No reply needed · Alex", open = false, page = R.page(6, 0))
        assertEquals("No reply needed · Alex", closed.peek)
        assertEquals("6", closed.count)
        assertEquals("FYI, 6. Closed. No reply needed · Alex.", closed.spoken)
        val open = R.head(NeedsYouGroup.FYI, 6, "No reply needed · Alex", open = true, page = R.page(6, 0))
        assertEquals("1–5 of 6", open.peek)
        assertEquals("", R.head(NeedsYouGroup.MESSAGES, 0, "", open = true).count)
    }

    @Test fun theSubtitleCountsMail() {
        assertEquals("1 to decide · 4 to reply · 5 FYI · 14 mail", R.subtitle(1, 4, 5, 14))
        assertEquals("3 mail", R.subtitle(0, 0, 0, 3))
        assertEquals(NeedsYouLayoutRules.ALL_CLEAR, R.subtitle(0, 0, 0, 0))
    }

    // Slice 2: the lists card as one row with Go through, the after-work card as To decide's foot line.
    private fun card(id: String, kind: DecisionKind, why: String = "") = DecisionCard(
        id = id, kind = kind, title = id, why = why, taskId = null, yesLabel = "Go through", laterLabel = "Later",
        openLabel = "Open Lists", yes = DecisionEffect.OPEN_LISTS, later = DecisionEffect.SET_ASIDE,
        open = DecisionEffect.OPEN_LISTS, urgent = false,
    )

    @Test fun theListsCardBecomesOneRow() {
        val lists = card(NeedsYouStackRules.LISTS_ID, DecisionKind.LISTS, "2 to chase")
        val task = card("t1", DecisionKind.OVERDUE)
        val (row, rest) = R.splitLists(listOf(task, lists))
        assertEquals(lists, row)
        assertEquals(listOf(task), rest)
        assertEquals("2 to chase · from your lists", R.listsLead(row!!.why))
        assertEquals("From your lists", R.listsLead(" "))
        assertEquals(null to listOf(task), R.splitLists(listOf(task)))
    }

    @Test fun toDecidesPeekSaysWhatIsReallyInIt() {
        val lists = card(NeedsYouStackRules.LISTS_ID, DecisionKind.LISTS, "1 renewal due")
        assertEquals("1 approval · 2 tasks · 1 renewal due from your lists",
            R.decidePeekOf(1, 0, 0, listOf(card("a", DecisionKind.OVERDUE), card("b", DecisionKind.DUE_TODAY), lists)))
        assertEquals("1 request · 1 task", R.decidePeekOf(0, 1, 0, listOf(card("a", DecisionKind.CONFLICT))))
        assertEquals("", R.decidePeekOf(0, 0, 0, emptyList()))
    }

    private fun person(name: String, messages: Int, urgent: Boolean = false) = PersonSummary(
        personName = name, isFamily = false, urgent = urgent, messages = messages, missedCalls = 0,
        apps = listOf(CaptureApp.WHATSAPP), firstAtMs = 0, latestAtMs = 0, latestText = null,
        items = listOf(CapturedItem("i-$name", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, name, "Hi", null, 0L)),
    )

    @Test fun theAfterWorkCardBecomesTheFootLine() {
        assertEquals(null, R.afterWorkFoot(AfterWorkSummary(emptyList()), "At work · 3 held for later"))
        val summary = AfterWorkSummary(listOf(person("Alex", 3, urgent = true), person("Sam", 2)))
        assertEquals("At work until 17:30 · 37 held for later ›", R.afterWorkFoot(summary, "At work until 17:30 · 37 held for later"))
        assertEquals("While you were at work · 2 people · 5 messages · 1 urgent ›", R.afterWorkFoot(summary, null))
        assertEquals("While you were at work · 1 person · 2 messages ›", R.afterWorkFoot(AfterWorkSummary(listOf(person("Sam", 2))), null))
    }

    private fun mail(id: String, from: String, scam: Boolean = false) =
        MailNeedsRow(id, from, "Personal · 09:12", "Lunch $id", null, false, "spoken $id", scam = scam, time = "09:12")

    @Test fun mailRowsAreSenderTimeAndSubjectOnly() {
        val row = R.mailRow(mail("a", "Sam Taylor").copy(preview = "Hi Meka, the card ending 1234"))
        assertEquals("Sam Taylor", row.from)
        assertEquals("09:12", row.time)
        assertEquals("Lunch a", row.subject)
        assertEquals(null, row.flag)
        assertEquals("Sam Taylor, Lunch a, 09:12", row.spoken)
        assertFalse(row.spoken.contains("1234"))
        val scam = R.mailRow(mail("b", "PayPal", scam = true))
        assertEquals("Possible scam", scam.flag)
        assertTrue(scam.spoken.startsWith("Possible scam, PayPal"))
        assertEquals("(no subject)", R.mailRow(mail("c", "Jo").copy(subject = " ")).subject)
    }

    @Test fun theTenMinuteInboxLeadsOnlyWhileDueAndNotRunning() {
        assertEquals("Ten-minute inbox · one at a time", R.mailLead(momentDue = true, running = false))
        assertEquals(null, R.mailLead(momentDue = true, running = true))
        assertEquals(null, R.mailLead(momentDue = false, running = false))
    }

    @Test fun mailPeekSaysTheMomentThenWho() {
        val rows = listOf(mail("a", "Sam"), mail("b", "School"), mail("c", "School"), mail("d", "Council"), mail("e", "Gym"))
        assertEquals("Ten-minute inbox · about 2 min each · Sam, School, Council + 1 more · 1 to chase",
            R.mailPeek(true, rows, "1 to chase"))
        assertEquals("Sam", R.mailPeek(false, rows.take(1), null))
    }

    @Test fun mailPagesFiveAtATimeThroughEveryRow() {
        val every = (1..14).map { mail("m$it", "Sender $it") }
        val p = R.page(every.size, 2)
        assertEquals("11–14 of 14", p.label)
        assertEquals(listOf("m11", "m12", "m13", "m14"), p.of(every).map { it.id })
    }

    @Test fun answeredRowsMakeRoomForTheNextOnes() {
        val every = (1..7).map { mail("m$it", "S$it") }
        val card = MailNeedsCard(MailNeedsRules.TITLE, "7 emails need you", every.take(5), null, null, count = 7, every = every)
        val left = InstantMailRules.needs(card, every.take(5).map { it.id }.toSet())!!
        assertEquals(listOf("m6", "m7"), left.rows.map { it.id })
        assertEquals(listOf("m6", "m7"), left.every.map { it.id })
        assertEquals(null, InstantMailRules.needs(card, every.map { it.id }.toSet()))
    }
}
