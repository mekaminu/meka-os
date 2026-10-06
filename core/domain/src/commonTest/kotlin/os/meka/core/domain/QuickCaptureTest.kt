package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCaptureTest {
    private fun d(text: String?, subject: String? = null) = QuickCapture.draft(text, subject)

    @Test
    fun nothingToCaptureIsNull() {
        assertNull(d(null))
        assertNull(d(""))
        assertNull(d("  \n \r\n\t "))
        assertNull(d(null, "   "))
    }

    @Test
    fun oneLineBecomesTheTitleWithSpacesTidied() {
        assertEquals(QuickCapture.Draft("Call James about football", null), d("  Call   James\tabout football \n"))
    }

    @Test
    fun firstLineIsTitleAndTheRestGoesToNotes() {
        val r = d("\n\nBuy boots\r\nsize 9\r\n\r\nfrom the Barca shop\n\n")
        assertEquals("Buy boots", r?.title)
        assertEquals("size 9\n\nfrom the Barca shop", r?.notes)
    }

    @Test
    fun aLinkOnItsOwnBecomesOpenHostWithTheLinkKept() {
        assertEquals(QuickCapture.Draft("Open bbc.co.uk", "https://www.bbc.co.uk/sport/football/123"),
            d("https://www.bbc.co.uk/sport/football/123"))
        assertEquals("Open example.org", d("example.org/a?b=c")?.title)
        assertEquals("example.org is great", d("example.org is great")?.title) // a sentence, not a link
    }

    @Test
    fun subjectSentWithAShareIsTheTitle() {
        val r = d("https://example.com/article", "  How to plan a week  ")
        assertEquals(QuickCapture.Draft("How to plan a week", "https://example.com/article"), r)
        assertEquals(QuickCapture.Draft("Same", null), d("Same", "Same"))
        assertEquals(QuickCapture.Draft("Only a subject", null), d(null, "Only a subject"))
    }

    @Test
    fun longTitlesAreCutAtAWordAndKeptWholeInNotes() {
        val long = "Remember to " + "renew the car insurance before the end of the month ".repeat(4).trim()
        val r = d(long)!!
        assertTrue(r.title.length <= QuickCapture.MAX_TITLE, r.title)
        assertTrue(r.title.endsWith("…"))
        assertTrue(!r.title.dropLast(1).endsWith(" "))
        assertEquals(long, r.notes)

        val noSpaces = "x".repeat(300)
        assertEquals(QuickCapture.MAX_TITLE, d(noSpaces)!!.title.length)

        val longSubject = "S ".repeat(100).trim()
        val s = d("body", longSubject)!!
        assertEquals("$longSubject\n\nbody", s.notes)
    }

    @Test
    fun notesAreCapped() {
        val r = d("Title\n" + "y".repeat(20_000))!!
        assertEquals(QuickCapture.MAX_NOTES, r.notes!!.length)
    }
}
