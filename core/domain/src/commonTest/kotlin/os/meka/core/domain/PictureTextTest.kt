package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PictureTextTest {
    @Test
    fun aScreenshotsStatusBarAndChromeAreDropped() {
        val raw = """
            14:02 ⚡
            4G   85%
            ‹ Back
            RingGo
            Parking session confirmed
            Location 81234  Oakfield Road
            Expires   15:30
            Expires   15:30
            •
            Done
        """.trimIndent()
        assertEquals(
            "RingGo\nParking session confirmed\nLocation 81234 Oakfield Road\nExpires 15:30",
            PictureTextRules.tidy(raw),
        )
    }

    @Test
    fun paragraphsStayApartAndBlankRunsFold() {
        val raw = "Oakfield School\n\n\n\nDear parents,\nThe trip to the museum is on Fri 16 Oct.\n\n\nPlease pay £8 by Tuesday."
        assertEquals(
            "Oakfield School\n\nDear parents,\nThe trip to the museum is on Fri 16 Oct.\n\nPlease pay £8 by Tuesday.",
            PictureTextRules.tidy(raw),
        )
    }

    @Test
    fun nothingLeftIsEmptyAndNotWorthOffering() {
        assertEquals("", PictureTextRules.tidy(null))
        assertEquals("", PictureTextRules.tidy("12:01\n•\n85%\n> "))
        assertFalse(PictureTextRules.worthOffering(PictureTextRules.tidy("Ok\nHi")))
        assertTrue(PictureTextRules.worthOffering("Dentist appointment Thursday 3pm"))
    }

    @Test
    fun aLongReadIsCutOnALine() {
        val line = "Terms and conditions apply to this booking in full."
        val raw = List(400) { "$line $it" }.joinToString("\n")
        val out = PictureTextRules.tidy(raw)
        assertTrue(out.length <= PictureTextRules.MAX_TEXT)
        assertTrue(out.endsWith(out.lines().last()) && out.lines().last().startsWith("Terms"))
    }

    @Test
    fun readSizeShrinksNeverGrows() {
        assertEquals(1200 to 800, PictureTextRules.readSize(1200, 800))
        assertEquals(2400 to 1800, PictureTextRules.readSize(4000, 3000))
        assertEquals(1080 to 2400, PictureTextRules.readSize(1080 * 2, 2400 * 2))
        assertEquals(1 to 1, PictureTextRules.readSize(0, 10))
    }

    @Test
    fun sizeLimitAndTheReadLine() {
        assertFalse(PictureTextRules.tooBig(null))
        assertFalse(PictureTextRules.tooBig(5_000_000))
        assertTrue(PictureTextRules.tooBig(PictureTextRules.MAX_BYTES + 1))
        assertEquals(
            "Read 6 words from the photo · the picture isn't kept",
            PictureTextRules.readLine("Parents' evening Tue 20 Oct 18:00", PictureTextRules.Source.CAMERA),
        )
        assertEquals("Read 1 word from the picture · the picture isn't kept", PictureTextRules.readLine("Invoice", PictureTextRules.Source.SHARED))
    }
}
