package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Who MEKA reads for requests (build plan V1, requests slice 3: the watch list and which messages are read). */
class RequestWatchTest {
    private val lists = PeopleLists(family = setOf("Ada", "mum"))
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60_000L

    private fun msg(id: String, who: String, text: String? = "can you pick up milk", group: String? = null, ago: Long = 0, kind: CaptureKind = CaptureKind.MESSAGE) =
        CapturedItem(id, CaptureApp.WHATSAPP, kind, who, text, group, now - ago)

    @Test
    fun namesAreCleanedAndNeverListedTwice() {
        assertEquals("Wife", RequestWatchRules.clean("  Wife \n"))
        assertNull(RequestWatchRules.clean("   "))
        assertEquals(RequestWatchRules.MAX_NAME, RequestWatchRules.clean("x".repeat(100))!!.length)
        var w = RequestWatchRules.addPerson(RequestWatch(), lists, "Wife")
        w = RequestWatchRules.addPerson(w, lists, " wife ")
        w = RequestWatchRules.addPerson(w, lists, "ADA") // already on the Family list
        assertEquals(setOf("Wife"), w.people)
        assertEquals(emptySet(), RequestWatchRules.removePerson(w, "WIFE").people)
        var g = RequestWatchRules.addGroup(RequestWatch(), "Football dads")
        g = RequestWatchRules.addGroup(g, "football  DADS")
        assertEquals(setOf("Football dads"), g.groups)
        assertEquals(emptySet(), RequestWatchRules.removeGroup(g, "Football Dads").groups)
    }

    @Test
    fun theStatusLineSaysWhoIsReadOrWhatIsMissing() {
        val w = RequestWatch(people = setOf("Wife"))
        assertEquals(listOf("Ada", "mum", "Wife"), RequestWatchRules.everyone(lists, w))
        assertEquals("Reading requests from Ada, mum and Wife · all day", RequestWatchRules.statusLine(lists, w, listening = true))
        assertEquals("Reading requests from Wife · all day", RequestWatchRules.statusLine(PeopleLists(), w, listening = true))
        assertEquals(
            "Reading requests from Ada, mum and 2 more · all day",
            RequestWatchRules.statusLine(lists, w.copy(people = setOf("Wife", "Nanny")), listening = true),
        )
        assertEquals("Add someone to watch for requests", RequestWatchRules.statusLine(PeopleLists(), RequestWatch(), listening = true))
        assertEquals("Needs notification access to read requests", RequestWatchRules.statusLine(lists, w, listening = false))
        assertEquals("Voice notes only until MEKA's AI is on", RequestWatchRules.statusLine(lists, w, listening = true, aiOn = false))
        assertEquals("1:1 chats only", RequestWatchRules.groupsLine(w))
        assertEquals("1:1 chats and Family, football dads", RequestWatchRules.groupsLine(w.copy(groups = setOf("football dads", "Family"))))
    }

    @Test
    fun onlyNewRecentMessagesFromWatchedPeopleAreRead() {
        val w = RequestWatch(people = setOf("Wife"), groups = setOf("Family"))
        val items = listOf(
            msg("a", "Wife"),
            msg("b", "Tunde"), // not watched
            msg("c", "Ada", group = "Barça lads"), // a group nobody named
            msg("d", "Ada", group = "family"), // a named group
            msg("e", "Wife", ago = 25 * hour), // too old
            msg("f", "Wife", text = null, kind = CaptureKind.MISSED_CALL),
            msg("g", "mum"), // already read
            msg("h", "Wife", text = "Voice message (0:12)", ago = hour), // voice notes are read too (no AI)
        )
        val read = RequestWatchRules.toRead(items, lists, w, seen = setOf("g"), nowMs = now)
        assertEquals(listOf("h", "a", "d"), read.map { it.id })
    }

    @Test
    fun aBusyNotificationSendsOnlyTheNewestFew() {
        val many = (1..9).map { msg("m$it", "Wife", ago = (10 - it) * 60_000L) }
        val read = RequestWatchRules.toRead(many + many.first(), lists, RequestWatch(people = setOf("Wife")), emptySet(), now)
        assertEquals((5..9).map { "m$it" }, read.map { it.id })
    }

    @Test
    fun seenIdsAreForgottenAfterAWeek() {
        val seen = mapOf("old" to now - 8 * 24 * hour, "new" to now - hour)
        assertEquals(setOf("new"), RequestWatchRules.pruneSeen(seen, now).keys)
    }
}
