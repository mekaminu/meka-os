package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Vault, slice 3: Ask and Talk know the documents, answered on the device by rules. */
class VaultAskTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val today = d(10, 18)
    private val copy = VaultFileRef("f1", VaultFileRules.PDF, 412_000, "k1", cal.toEpochMs(today, 9 * 60), "Fold")
    private val docs = listOf(
        DocumentItem("1", "Passport", DocKind.PASSPORT, d(3, 3, 2029), 0, file = copy),
        DocumentItem("2", "Theo passport", DocKind.PASSPORT, d(2, 20, 2027), 0),
        DocumentItem("3", "Driving licence", DocKind.DRIVING_LICENCE, d(6, 12, 2031), 0),
        DocumentItem("4", "Birth certificate Theo", DocKind.CERTIFICATE, null, 0),
    )

    private fun ask(q: String, items: List<DocumentItem> = docs): String? =
        VaultAskRules.request(q)?.let { VaultAskRules.answer(it, items, today, cal, "Fold") }

    @Test
    fun whatIsAboutTheVaultAndWhatIsNot() {
        assertNotNull(VaultAskRules.request("When does Theo's passport run out?"))
        assertNotNull(VaultAskRules.request("is my driving licence still valid"))
        assertNotNull(VaultAskRules.request("Navilife, what's in my vault?"))
        assertNotNull(VaultAskRules.request("do I have a copy of my passport?"))
        assertNotNull(VaultAskRules.request("which documents need renewing?"))
        // The help's, the renewals radar's, or general knowledge.
        assertNull(VaultAskRules.request("how do I add a passport?"))
        assertNull(VaultAskRules.request("what is a GHIC?"))
        assertNull(VaultAskRules.request("when is the TV licence due?"))
        assertNull(VaultAskRules.request("passport photo booths near me"))
        assertNull(VaultAskRules.request("when's the MOT due?"))
        assertNull(VaultAskRules.request("add milk to the shopping"))
    }

    @Test
    fun aNamedDocumentSaysWhenItRunsOutAndWhetherToRenew() {
        assertEquals(
            "Theo passport expires Sat 20 Feb 2027, in 4 months: it's time to renew it.",
            ask("When does Theo's passport run out?"),
        )
        assertEquals(
            "Driving licence expires Thu 12 Jun 2031, in 4 years. Navilife reminds you from Sun 13 Apr 2031.",
            ask("is my driving licence still valid?"),
        )
        // Not a name: every passport.
        val both = ask("is my passport still valid for Spain?")!!
        assertTrue(both.startsWith("Passport expires Sat 3 Mar 2029") || both.startsWith("Theo passport"), both)
        assertTrue("Passport expires" in both && "Theo passport expires" in both, both)
        // Someone's the Vault doesn't hold.
        assertEquals("There's no passport for Sam in your Vault. It holds: Passport, Theo passport.", ask("when does Sam's passport expire?"))
        assertEquals("Birth certificate Theo doesn't run out.", ask("when does Theo's birth certificate expire?"))
    }

    @Test
    fun copiesAndKindsItDoesNotHold() {
        assertEquals(
            "Passport expires Sat 3 Mar 2029, in 2 years. Navilife reminds you from Fri 1 Sep 2028. " +
                "A copy is kept: PDF · 412 KB · encrypted · added on this Fold, Sun 18 Oct.",
            ask("do I have a copy of my passport?", docs.take(1)),
        )
        assertTrue(ask("do I have a scan of Theo's passport?")!!.endsWith("No copy kept yet: open it in the Vault and Add a copy."))
        assertTrue(ask("when does my railcard run out?")!!.startsWith("There's no railcard in your Vault yet."))
        // Insurance the Vault doesn't hold is the renewals radar's (the AI's).
        assertNull(ask("when does the home insurance renew?"))
    }

    @Test
    fun theWholeVaultAndWhatNeedsRenewing() {
        val all = ask("what's in my vault?")!!
        assertTrue(all.startsWith("Your Vault holds 4 documents, 1 to renew. Theo passport expires"), all)
        assertEquals("Theo passport expires Sat 20 Feb 2027, in 4 months: it's time to renew it.", ask("which documents need renewing?"))
        assertEquals(
            "Nothing in your Vault needs renewing yet. Next to run out: Passport, Sat 3 Mar 2029.",
            ask("any documents to renew?", docs.filter { it.id != "2" }),
        )
        assertEquals(VaultAskRules.EMPTY, ask("what's in my vault?", emptyList()))
        assertEquals(HelpPlace.VAULT, VaultAskRules.link.place)
    }
}
