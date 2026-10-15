package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Family (family sharing, slice 4): names, each link's line, the summary and the Activity entries. */
class FamilyTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private val now = at(sat, 10)

    private fun member(id: String, state: FamilyState, made: Long, claimed: Long? = null, seen: Long? = null, name: String = "jordan") =
        FamilyMember(id, name, state, made, claimed, seen)

    @Test
    fun namesAreTheOnesTheServerTakesAndReadCapitalised() {
        assertEquals("Jordan", FamilyRules.validName("  Jordan "))
        assertEquals("Mary Jane", FamilyRules.validName("Mary   Jane"))
        assertEquals("O'Neil-Smith", FamilyRules.validName("O'Neil-Smith"))
        assertNull(FamilyRules.validName(""))
        assertNull(FamilyRules.validName("Jordan2"))
        assertNull(FamilyRules.validName("meka"))
        assertNull(FamilyRules.validName("x".repeat(31)))
        assertEquals("Jordan", FamilyRules.display("jordan"))
        assertEquals("Mary Jane", FamilyRules.display("mary jane"))
        assertEquals(FamilyState.JOINED, FamilyRules.state("joined"))
        assertEquals(FamilyState.OFF, FamilyRules.state("revoked"))
        assertEquals(FamilyState.WAITING, FamilyRules.state("waiting"))
    }

    @Test
    fun eachLinkSaysWhereItStands() {
        val waiting = member("fam1", FamilyState.WAITING, at(sat, 9))
        assertEquals("Waiting · link made today", FamilyRules.line(waiting, now, cal))
        val joined = member("fam2", FamilyState.JOINED, at(sat - 2, 9), claimed = at(sat - 2, 20), seen = at(sat - 1, 8))
        assertEquals("Joined Thu 8 Oct · seen yesterday", FamilyRules.line(joined, now, cal))
        assertEquals("Joined today · seen today", FamilyRules.line(joined.copy(claimedAtMs = at(sat, 9), lastSeenAtMs = at(sat, 9)), now, cal))
        assertEquals("Turned off", FamilyRules.line(joined.copy(state = FamilyState.OFF), now, cal))

        val row = FamilyRules.row(waiting, now, cal)
        assertEquals("Jordan", row.title)
        assertTrue(row.lit && row.canTurnOff)
        assertFalse(FamilyRules.row(joined.copy(state = FamilyState.OFF), now, cal).canTurnOff)
    }

    @Test
    fun theSummaryAndRowsPutWhatIsOnFirstAndOfferALinkOnlyWhenNoneIsOn() {
        // Rule 13: the link is for About you's partner (an invented name here); with none, nothing is offered.
        val nobody = FamilyRules.view(emptyList(), now, cal, partner = null)
        assertEquals(FamilyRules.NO_PARTNER, nobody.summary)
        assertFalse(nobody.canInvite)
        assertNull(nobody.inviteName)
        assertEquals("", nobody.inviteLabel)
        val empty = FamilyRules.view(emptyList(), now, cal, partner = " Jordan ")
        assertEquals("No one yet · make a link so Jordan can add to the shopping list", empty.summary)
        assertTrue(empty.canInvite)
        assertEquals("Jordan", empty.inviteName)
        assertEquals("Make a link for Jordan", empty.inviteLabel)
        assertEquals(FamilyRules.SHARED, empty.shared)

        val waiting = FamilyRules.view(listOf(member("fam1", FamilyState.WAITING, at(sat, 9))), now, cal, partner = "Jordan")
        assertEquals("Waiting for Jordan to open the link", waiting.summary)
        assertFalse(waiting.canInvite)

        val offs = (1..5).map { member("famoff$it", FamilyState.OFF, at(sat - 3, it)) }
        val v = FamilyRules.view(offs + member("famj", FamilyState.JOINED, at(sat - 3, 0), claimed = at(sat - 1, 9)), now, cal, problem = "x", partner = "Jordan")
        assertEquals("Jordan can see and add to the shopping list and dinners", v.summary)
        assertEquals(listOf("famj", "famoff5", "famoff4", "famoff3"), v.rows.map { it.id })
        assertFalse(v.canInvite)
        assertEquals("x", v.problem)

        // Every link off: offer a new one.
        assertTrue(FamilyRules.view(offs, now, cal, partner = "Jordan").canInvite)
        // Two people joined.
        val two = FamilyRules.view(
            listOf(member("a", FamilyState.JOINED, 1, 1), member("b", FamilyState.JOINED, 2, 2, name = "ada")), now, cal,
        )
        assertEquals("Ada and Jordan can see and add to the shopping list and dinners", two.summary)
    }

    @Test
    fun theLinkCarriesTheShareTextAndTheNote() {
        val l = FamilyRules.link("fam1", "jordan", "https://meka.example/family#abc")
        assertEquals("Jordan", l.name)
        assertEquals("Jordan, here's our shopping list and the week's dinners. Open it on your phone and add to it any time: https://meka.example/family#abc", l.shareText)
        assertEquals(FamilyRules.NOTE, l.note)
    }

    @Test
    fun activityEntriesAreWrittenOnceWhicheverDeviceSeesThemFirst() {
        val world = SyncWorld()
        world.clock.nowMs = now
        val fold = world.device("android")
        val mac = world.device("mac")
        val logFold = ActivityLog(fold.replica, { "x" }, { world.clock.nowMs }, cal)
        val logMac = ActivityLog(mac.replica, { "y" }, { world.clock.nowMs }, cal)
        val id = FamilyRules.joinedId("fam1")
        logFold.recordFamily(id, at(sat, 8), FamilyRules.joinedSummary("jordan"), FamilyRules.WHY_JOINED)
        logFold.recordFamily(id, at(sat, 9), "again", FamilyRules.WHY_JOINED)
        fold.sync(); mac.sync()
        logMac.recordFamily(id, at(sat, 9), "again", FamilyRules.WHY_JOINED)
        val items = logMac.items().filter { it.kind == ActivityKind.FAMILY }
        assertEquals(1, items.size)
        assertEquals("Jordan joined the shopping list", items[0].summary)
        assertEquals(at(sat, 8), items[0].atMs)
        assertFalse(items[0].canUndo)
        assertTrue(FamilyRules.joinedId("fam1") != FamilyRules.invitedId("fam1"))
        assertEquals("Turned off Jordan's link", FamilyRules.turnedOffSummary("jordan"))
        assertEquals("Made a shopping list link for Jordan", FamilyRules.invitedSummary("jordan"))
    }
}
