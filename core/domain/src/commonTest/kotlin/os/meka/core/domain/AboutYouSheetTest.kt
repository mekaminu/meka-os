package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walk-through week 5, A17 on About you: each line is a row that opens its own sheet (no open input boxes on the
 * page), and the partner's lines name her instead of saying "their" in one line and "her" in the next. Invented names.
 */
class AboutYouSheetTest {
    private fun view(a: AboutYou) = AboutYouRules.view(a, LocalCalendar.UTC, 0L)
    private fun row(a: AboutYou, f: AboutField) = view(a).rows.first { it.field == f }

    @Test
    fun aRowOpensASheetHoldingWhatsThereWithClearOnlyWhenHeld() {
        val held = row(AboutYou(shownName = "Jordan"), AboutField.SHOWN_NAME)
        val s = AboutYouRules.editSheet(held)
        assertEquals("Your name", s.title)
        assertEquals("Jordan", s.value)
        assertEquals(AboutField.SHOWN_NAME.hint, s.hint)
        assertEquals(AboutField.SHOWN_NAME.spokenHint, s.spokenHint)
        assertEquals(AboutYouRules.CLEAR, s.clearLabel)
        assertEquals("Save", s.saveLabel)
        assertEquals("Your name, Jordan", AboutYouRules.rowSpoken(held))

        val empty = AboutYouRules.editSheet(row(AboutYou.EMPTY, AboutField.HOME))
        assertEquals("", empty.value)
        assertNull(empty.clearLabel)
        assertEquals("Home, not added", AboutYouRules.rowSpoken(row(AboutYou.EMPTY, AboutField.HOME)))
        // A field with a fuller how-to says it in the sheet, without repeating its label.
        val route = AboutYouRules.editSheet(row(AboutYou.EMPTY, AboutField.ROUTE))
        assertTrue(route.note.startsWith("For example South Rail, then Metro"), route.note)
        // One without says what the line is for, even once it's held.
        assertEquals(AboutField.HOME.emptyLine, AboutYouRules.editSheet(row(AboutYou(home = "Oakfield"), AboutField.HOME)).note)
        // Every field's sheet has a note.
        for (f in AboutField.entries) assertTrue(AboutYouRules.editSheet(row(AboutYou.EMPTY, f)).note.isNotBlank(), f.name)
    }

    @Test
    fun theChildSheetAddsOne() {
        val s = AboutYouRules.childSheet()
        assertNull(s.field)
        assertEquals("Add a child", s.title)
        assertEquals("Add", s.saveLabel)
        assertEquals(AboutYouRules.CHILD_HINT, s.hint)
        assertNull(s.clearLabel)
    }

    @Test
    fun thePartnerIsNamedNotTheirThenHer() {
        val a = AboutYou(partnerName = "Morgan")
        val v = view(a)
        assertEquals("Morgan's mobile number", row(a, AboutField.PARTNER_PHONE).hint)
        assertEquals("Add it so calls from Morgan always ring", row(a, AboutField.PARTNER_PHONE).line)
        assertEquals("Add it so Inbox autopilot never tidies Morgan's emails away", row(a, AboutField.PARTNER_EMAIL).line)
        assertEquals("On · Navilife flags what Morgan asks you for in messages", v.watchLine)
        assertEquals("Handle Morgan's requests for me", v.handleTitle)
        assertTrue(v.handleLine!!.contains("what Morgan asks for"))
        assertEquals("Off · Morgan's messages are treated like anyone's", view(a.copy(partnerWatch = false)).watchLine)
        val words = v.rows.flatMap { listOfNotNull(it.hint, it.line, it.purpose) } + listOfNotNull(v.watchLine, v.handleLine, v.handleTitle)
        val pronoun = Regex("\\b(their|they|her|she)\\b")
        for (w in words.filter { "Morgan" in it }) assertFalse(pronoun.containsMatchIn(w.lowercase()), "\"$w\" still uses a pronoun for the partner")
        // No partner named: the generic words stay, and no switch line.
        val none = view(AboutYou.EMPTY)
        assertEquals(AboutField.PARTNER_PHONE.emptyLine, none.rows.first { it.field == AboutField.PARTNER_PHONE }.line)
        assertNull(none.watchLine)
        assertEquals(HandleForMeRules.TITLE, none.handleTitle)
    }
}
