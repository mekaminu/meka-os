package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Vault, slice 1: documents typed with the day they run out, the rows, and the heads-ups onto the plan. */
class DocumentsTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 12)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val dFold = Documents(fold.replica, { "d${n++}" }, { world.clock.nowMs }, cal)
    private val dMac = Documents(mac.replica, { "d${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun doc(title: String, kind: DocKind, day: Long?) = DocumentRules.Read.Doc(DocumentEntry(title, kind, day))

    @Test
    fun aDocumentIsATitleWithTheDayItRunsOut() {
        assertEquals(doc("Passport", DocKind.PASSPORT, d(3, 3, 2029)), DocumentRules.read("Passport expires 3 Mar 2029", mon))
        assertEquals(doc("Rex passport", DocKind.PASSPORT, d(5, 20, 2027)), DocumentRules.read("Rex passport 20 May 2027", mon))
        assertEquals(doc("Driving licence", DocKind.DRIVING_LICENCE, d(6, 12, 2031)), DocumentRules.read("driving licence valid until 12/06/2031", mon))
        // No year: the next time the date comes round.
        assertEquals(doc("Home insurance", DocKind.INSURANCE, d(1, 4, 2027)), DocumentRules.read("Home insurance renews 4 Jan", mon))
        // A card's month: its last day.
        assertEquals(doc("Railcard", DocKind.RAILCARD, d(5, 31, 2027)), DocumentRules.read("Railcard 05/2027", mon))
        assertEquals(doc("GHIC Logan", DocKind.HEALTH_CARD, d(5, 31, 2028)), DocumentRules.read("GHIC Logan exp May 2028", mon))
        assertEquals(doc("Railcard", DocKind.RAILCARD, d(9, 30, 2027)), DocumentRules.read("Railcard 9/27", mon))
        // No date: it doesn't run out.
        assertEquals(doc("Birth certificate Rex", DocKind.CERTIFICATE, null), DocumentRules.read("Birth certificate Rex", mon))
        assertEquals(doc("ID card", DocKind.ID_CARD, d(2, 1, 2030)), DocumentRules.read("ID card 1 Feb 2030", mon))
        // An expired one may be noted to renew.
        assertEquals(doc("Passport", DocKind.PASSPORT, d(10, 1, 2026)), DocumentRules.read("Passport expired 1 Oct 2026", mon))
    }

    @Test
    fun documentNumbersAreNeverKept() {
        assertEquals(doc("Passport", DocKind.PASSPORT, d(3, 3, 2029)), DocumentRules.read("Passport 123456789 expires 3 Mar 2029", mon))
        assertEquals(doc("Driving licence", DocKind.DRIVING_LICENCE, d(6, 12, 2031)), DocumentRules.read("Driving licence AKUNE812345C99 12 Jun 2031", mon))
        assertTrue(DocumentRules.looksLikeNumber("AB123456"))
        assertFalse(DocumentRules.looksLikeNumber("Railcard"))
        assertFalse(DocumentRules.looksLikeNumber("2029"))
    }

    @Test
    fun whatCantBeReadIsRefused() {
        assertEquals(DocumentRules.Read.Refused(DocumentRules.NOT_READ), DocumentRules.read("", mon))
        assertEquals(DocumentRules.Read.Refused(DocumentRules.NOT_READ), DocumentRules.read("expires", mon))
        assertEquals(DocumentRules.Read.Refused(DocumentRules.BAD_DATE), DocumentRules.read("Passport 31 Feb 2029", mon))
        assertEquals(DocumentRules.Read.Refused(DocumentRules.BAD_DATE), DocumentRules.read("Passport 3 Mar 2060", mon))
    }

    @Test
    fun rowsSayHowLongIsLeftAndLightWhenItsTimeToRenew() {
        val items = listOf(
            DocumentItem("a", "Rex passport", DocKind.PASSPORT, d(5, 20, 2027), 0),
            DocumentItem("b", "Passport", DocKind.PASSPORT, d(4, 3, 2027), 0),
            DocumentItem("c", "Railcard", DocKind.RAILCARD, d(10, 1, 2026), 0),
            DocumentItem("e", "Birth certificate", DocKind.CERTIFICATE, null, 0),
        )
        val v = DocumentRules.view(items.sortedBy { it.expiresDay ?: Long.MAX_VALUE }, mon)
        assertEquals(listOf("c", "b", "a", "e"), v.rows.map { it.id })
        assertEquals("4 documents · 2 to renew", v.summary)
        val (railcard, passport, rex, cert) = v.rows
        assertEquals("Expired 11 days ago", railcard.status)
        assertTrue(railcard.statusLit)
        assertEquals("Expired Thu 1 Oct 2026", railcard.line)
        assertEquals("Renew now · expires in 5 months", passport.status)
        assertTrue(passport.statusLit)
        assertEquals("Expires Sat 3 Apr 2027", passport.line)
        assertEquals("Expires in 7 months", rex.status)
        assertFalse(rex.statusLit)
        assertEquals("Passport · expires Thu 20 May 2027", rex.line)
        assertEquals("No expiry", cert.status)
        assertEquals("Certificate", cert.line)
        assertEquals("Rex passport. Passport · expires Thu 20 May 2027. Expires in 7 months", rex.spoken)

        assertEquals("1 document · next to run out: Rex passport, May 2027", DocumentRules.view(listOf(items[0]), mon).summary)
        assertEquals(DocumentsView.EMPTY, DocumentRules.view(emptyList(), mon))
    }

    @Test
    fun theExpiryGoesOntoThePlanAsHeadsUps() {
        val rex = DocumentItem("a", "Rex passport", DocKind.PASSPORT, d(5, 20, 2027), 0)
        val e = rex.expiresDay!!
        fun at(day: Long) = DocumentRules.notices(listOf(rex), cal.toEpochMs(day, 8 * 60), cal)

        val opens = at(e - 183).single()
        assertEquals("Rex passport expires Thu 20 May 2027", opens.title)
        assertEquals("In 6 months · many countries want six months left on a passport, and renewing can take weeks", opens.text)
        assertEquals(NoticeSource.DOCUMENTS, opens.source)
        assertEquals(NoticeTarget.VAULT, opens.target)
        assertEquals(cal.toEpochMs(e - 183, 9 * 60), opens.atMs)
        assertEquals("In 4 weeks · many countries want six months left on a passport, and renewing can take weeks", at(e - 30).single().text)
        assertTrue(at(e - 7).single().text.startsWith("In 7 days · "))
        assertEquals("Rex passport expires today", at(e).single().title)
        assertTrue(at(e - 100).isEmpty())
        assertTrue(at(e + 1).isEmpty())
        // A railcard's window is two weeks: no month-before heads-up.
        val rail = DocumentItem("r", "Railcard", DocKind.RAILCARD, d(5, 31, 2027), 0)
        assertTrue(DocumentRules.notices(listOf(rail), cal.toEpochMs(rail.expiresDay!! - 30, 600), cal).isEmpty())
        assertEquals(1, DocumentRules.notices(listOf(rail), cal.toEpochMs(rail.expiresDay!! - 14, 600), cal).size)
        // Each key is its own: the same day posts once, a new date posts again.
        assertEquals("doc:a:$e:183", opens.key)
    }

    @Test
    fun typedOnTheFoldShowsOnTheMacAndARenewedDateReplacesTheOld() {
        assertEquals(DocumentRules.EMPTY_LINE, dFold.view().summary)
        val added = dFold.add("Rex passport 20 May 2027")
        assertFalse(added.refused)
        assertEquals("Added Rex passport · expires Thu 20 May 2027 · MEKA reminds you from Wed 18 Nov 2026", added.line)
        assertEquals("Added Railcard · expires Mon 31 May 2027 · MEKA reminds you from Mon 17 May 2027", dFold.add("Railcard 05/2027").line)
        assertTrue(dFold.add("Passport 31 Feb 2029").refused)
        sync()
        assertEquals(listOf("Rex passport", "Railcard"), dMac.items().map { it.title })
        val e35 = d(5, 20, 2035)
        assertEquals(
            "Updated Rex passport · expires ${DocumentRules.dateLabel(e35)} · MEKA reminds you from ${DocumentRules.dateLabel(e35 - 183)}",
            dMac.add("rex passport 20 May 2035").line,
        )
        sync()
        val rex = dFold.items().first { it.kind == DocKind.PASSPORT }
        assertEquals(d(5, 20, 2035), rex.expiresDay)
        assertEquals(2, dFold.items().size)
        assertTrue(dFold.remove(rex.id))
        assertFalse(dFold.remove(rex.id))
        sync()
        assertEquals(listOf("Railcard"), dMac.items().map { it.title })
        assertEquals("Added Passport · expires Thu 1 Oct 2026 · it has run out: renew it", dFold.add("Passport expired 1 Oct 2026").line)
        assertEquals("Added Will · no expiry", dFold.add("Will").line)
    }
}
