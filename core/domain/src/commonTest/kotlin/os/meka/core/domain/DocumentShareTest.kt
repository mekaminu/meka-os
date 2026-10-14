package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screenshots and photos, slice 3: a picture of a document becomes a Vault row (invented people and numbers only). */
class DocumentShareTest {
    private val today = CivilDate.toEpochDay(2026, 10, 10)
    private fun d(y: Int, m: Int, day: Int) = CivilDate.toEpochDay(y, m, day)

    private val mrz1 = "P<GBRSMITH<<ALEX<JOHN<<<<<<<<<<<<<<<<<<<<<<<"
    private val mrz2 = "9256654166GBR8001014M2705206<<<<<<<<<<<<<<00"
    private val passportLabels = """
        UNITED KINGDOM OF GREAT BRITAIN AND NORTHERN IRELAND
        PASSPORT
        Surname / Nom (1)
        SMITH
        Given names / Prénoms (2)
        ALEX JOHN
        Date of birth / Date de naissance (4)
        01 JAN /JAN 80
        Date of issue / Date de délivrance (5)  Date of expiry / Date d'expiration (6)
        20 MAY /MAI 17  20 MAY /MAI 27
    """.trimIndent()

    @Test
    fun aPassportPhotoPageIsReadFromItsLabelsOrItsMachineLines() {
        // Labels only: the issue and expiry dates side by side, the later one is the expiry; the birth date is ignored.
        val e = DocumentShareRules.read(passportLabels, null, today)!!
        assertEquals(DocumentEntry("Alex passport", DocKind.PASSPORT, d(2027, 5, 20)), e)
        // The machine-readable lines alone carry the expiry (checked) and the given name.
        val m = DocumentShareRules.read("$mrz1\n$mrz2", null, today)!!
        assertEquals(DocumentEntry("Alex passport", DocKind.PASSPORT, d(2027, 5, 20)), m)
        assertEquals(d(2027, 5, 20), DocumentShareRules.mrzExpiry(mrz2, 2026))
        // A misread digit fails the check, so the labels decide.
        val bad = mrz2.replaceRange(21, 22, "3")
        assertNull(DocumentShareRules.mrzExpiry(bad, 2026))
        assertEquals(d(2027, 5, 20), DocumentShareRules.read("$passportLabels\n$mrz1\n$bad", null, today)!!.expiresDay)
        // No document number reaches the title.
        assertFalse(e.title.any { it.isDigit() })
    }

    @Test
    fun aLicenceAHealthCardARailcardAndAnInsuranceCertificate() {
        val licence = "DRIVING LICENCE\nUK\n1. SMITH\n2. ALEX JOHN\n3. 01.01.1980 UNITED KINGDOM\n4a. 12.06.2021 4b. 11.06.2031\n4c. DVLA\n5. SMITH801010AJ9XY 12"
        assertEquals(DocumentEntry("Alex driving licence", DocKind.DRIVING_LICENCE, d(2031, 6, 11)), DocumentShareRules.read(licence, null, today))
        val ghic = "Global Health Insurance Card\n3. Name SMITH\n4. Given names SAM\n6. Personal identification number 1234567890\n9. Expiry date 31/03/2030"
        assertEquals(DocumentEntry("Sam GHIC", DocKind.HEALTH_CARD, d(2030, 3, 31)), DocumentShareRules.read(ghic, null, today))
        assertEquals(DocumentEntry("Railcard", DocKind.RAILCARD, d(2027, 5, 14)), DocumentShareRules.read("16-25 Railcard\nValid until 14 May 2027", null, today))
        assertEquals(d(2027, 5, 31), DocumentShareRules.read("Two Together Railcard\nExpiry 05/2027", null, today)!!.expiresDay)
        val car = "Certificate of Motor Insurance\nPolicy number: ABC1234567\nRegistration mark of vehicle: AB12 CDE\n" +
            "Effective date of the commencement of insurance: 00:01 on 13/03/2026\nDate of expiry of insurance: 23:59 on 12/03/2027"
        assertEquals(DocumentEntry("Car insurance", DocKind.INSURANCE, d(2027, 3, 12)), DocumentShareRules.read(car, null, today))
        // Shared as an email: the subject counts too.
        assertEquals(
            DocumentEntry("Warranty", DocKind.WARRANTY, d(2028, 10, 2)),
            DocumentShareRules.read("Thanks for registering your boiler.\nYour cover ends on 2 October 2028.", "Your warranty is registered", today),
        )
    }

    @Test
    fun anythingElseIsNotADocument() {
        // A renewal letter that doesn't say the day it runs out, a parking confirmation, a chat, an appointment.
        assertNull(DocumentShareRules.read("Your home insurance policy renews soon. Call us to talk it through.", null, today))
        assertNull(DocumentShareRules.read("RingGo\nParking session confirmed\nLocation 81234\nExpires 15:30", null, today))
        assertNull(DocumentShareRules.read("Sam\nhaha see you later", null, today))
        assertNull(DocumentShareRules.read("Oakfield Dental\nYour appointment is on Thu 16 Oct at 15:00", null, today))
        // A passport with no date that can be its expiry (too far ahead) isn't offered.
        assertNull(DocumentShareRules.read("PASSPORT\nDate of expiry 20 MAY 2099", null, today))
        assertTrue(DocumentShareRules.looksLikeOne(passportLabels))
        assertFalse(DocumentShareRules.looksLikeOne("Dear parents, please return the reply slip by Friday"))
    }

    @Test
    fun theOfferSaysWhetherItIsNewAnUpdateOrAlreadyThere() {
        val v = DocumentShareRules.view(passportLabels, null, today, emptyList())!!
        assertEquals("Add to the Vault · Alex passport · expires Thu 20 May 2027", v.line)
        assertFalse(v.already)
        val old = DocumentItem("d1", "Alex passport", DocKind.PASSPORT, d(2026, 12, 1), 0L)
        val u = DocumentShareRules.view(passportLabels, null, today, listOf(old))!!
        assertEquals("Update the Vault · Alex passport · now expires Thu 20 May 2027", u.line)
        assertTrue(u.updates)
        val same = old.copy(expiresDay = d(2027, 5, 20))
        val a = DocumentShareRules.view(passportLabels, null, today, listOf(same))!!
        assertEquals("Already in the Vault · Alex passport · expires Thu 20 May 2027", a.line)
        assertTrue(a.already)
        // A screenshot of one is noticed as a document, with Add to the Vault.
        val n = ScreenshotRules.noticeFor(passportLabels)!!
        assertEquals("Screenshot · looks like a document with an expiry date", n.title)
        assertEquals(DocumentShareRules.ACTION, n.action)
    }
}
