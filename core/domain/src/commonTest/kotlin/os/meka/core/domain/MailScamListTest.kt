package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A possible scam isn't mail to answer: it waits under Possible scams to check in the tidied box (Meka 2026-11-18 23:34). */
class MailScamListTest {
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L
    private val cal = LocalCalendar.UTC
    private val scam = "⚠ Possible scam · sent from mail-x.example, not invest.example"

    private fun item(id: String, from: String = "Invest Co", warning: String = "", at: Long = now - 3_600_000L) =
        assertNotNull(MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", from, "x@mail-x.example", "Our bank details are changing", null, at, 1, warning = warning)))

    @Test
    fun aPossibleScamLeavesMailToAnswerAndNotAScamBringsItBack() {
        val items = listOf(item("a", warning = scam), item("b", from = "Alex Reed"), item("c", warning = "Log in yourself, not from a link"))
        assertEquals(listOf("b", "c"), MailScamListRules.toAnswer(items).map { it.id })
        val card = assertNotNull(MailNeedsRules.card(MailScamListRules.toAnswer(items), now, cal))
        assertEquals(listOf("b", "c"), card.rows.map { it.id }.sorted())
        assertEquals(2, card.count)
        // Not a scam: back with the mail to answer; its Undo sends it back again.
        assertFalse(MailScamListRules.possibleScam(items[0].copy(notScamAtMs = now)))
        assertTrue(MailScamListRules.possibleScam(items[0].copy(notScamAtMs = now, notScamUndoAtMs = now + 1)))
        val real = items.map { if (it.id == "a") it.copy(notScamAtMs = now) else it }
        assertEquals(real, MailScamListRules.toAnswer(real))
    }

    @Test
    fun theScamCardHoldsOnlyTheScamsWithTheirAnswersAndAHeadline() {
        val items = listOf(item("a", warning = scam), item("b", from = "Alex Reed"), item("d", from = "PayPal", warning = scam, at = now - 60_000L))
        val card = assertNotNull(MailScamListRules.card(items, now, cal))
        assertEquals(MailScamListRules.TITLE, card.title)
        assertEquals("2 possible scams to check", card.headline)
        assertEquals(listOf("d", "a"), card.every.map { it.id })
        assertTrue(card.every.all { it.scam })
        assertNull(card.replyLine)
        // Tapped, a scam row opens on Junk it & block · Not a scam · Done, never Task.
        val actions = MailRowActionRules.actions(card.rows.first(), notScam = true)
        assertEquals(listOf(MailRowAction.BIN, MailRowAction.NOT_SCAM, MailRowAction.DONE), actions.shown)
        assertFalse(MailRowAction.TASK in actions.shown + actions.more)
        assertEquals("2 possible scams · PayPal, Invest Co", MailScamListRules.peek(card))
        assertEquals("1 possible scam to check", MailScamListRules.headline(1))
        assertEquals("Possible scam · Invest Co", MailScamListRules.peek(MailScamListRules.card(items.take(2), now, cal)))
        assertNull(MailScamListRules.card(items.filter { it.id == "b" }, now, cal))
        assertNull(MailScamListRules.peek(null))
    }

    @Test
    fun theScamsCountUnderJunkAndScams() {
        val tiles = listOf(
            NeedsYouMailTile("84", NeedsYouMailRules.TIDIED, false, TidiedKind.ALL.id),
            NeedsYouMailTile("26", NeedsYouMailRules.NEWSLETTERS, false, TidiedKind.NEWSLETTERS.id),
            NeedsYouMailTile("38", NeedsYouMailRules.PROMOS, false, TidiedKind.PROMOS.id),
            NeedsYouMailTile("20", NeedsYouMailRules.JUNK, true, TidiedKind.JUNK.id, "20 junk and scam emails tidied today. Opens them"),
        )
        val scams = MailScamListRules.card(listOf(item("a", warning = scam)), now, cal)
        val out = MailScamListRules.withScams(tiles, scams)
        assertEquals(listOf("84", "26", "38", "21"), out.map { it.number })
        assertEquals("20 junk and scam emails tidied today. Opens them. 1 possible scam to check, shown below", out[3].spoken)
        assertEquals(tiles, MailScamListRules.withScams(tiles, null))
        assertEquals(85, MailScamListRules.tidiedCount(84, scams))
        assertEquals(84, MailScamListRules.tidiedCount(84, null))
    }
}
