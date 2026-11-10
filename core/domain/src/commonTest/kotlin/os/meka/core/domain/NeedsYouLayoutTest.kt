package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsYouLayoutTest {
    private fun card(
        id: String, who: String, at: Long, gist: String, lane: TriageLane = TriageLane.FYI, group: String? = null,
    ): TriageCard {
        val time = "14:" + (at.toInt() % 60).toString().padStart(2, '0')
        val from = if (group != null) "$who in $group · $time" else "$who · $time"
        return TriageCard(id, lane, People.key(who), from, gist, null, CaptureApp.WHATSAPP, group != null, at, "spoken", group)
    }

    private val alex = TriageChatRules.chats(listOf(
        card("a1", "Alex", 7, "All is well, homework done"),
        card("a2", "Alex", 8, "Asks if you can work from home on the show day", TriageLane.NEEDS_REPLY),
    )).single()
    private val sam = TriageChatRules.chats(listOf(card("s1", "Sam", 12, "Thanked you, no reply needed"))).single()
    private val group = TriageChatRules.chats(listOf(
        card("g1", "Jo", 20, "Asks you to post the time", TriageLane.NEEDS_REPLY, group = "Game testing group"),
        card("g2", "Kim", 21, "Says she isn't in the group", TriageLane.NEEDS_REPLY, group = "Game testing group"),
    )).single()

    @Test
    fun aThankYouOnlyChatIsFyiAndOneNeedingAReplyIsAMessage() {
        val chats = listOf(alex, sam, group)
        assertTrue(NeedsYouLayoutRules.isFyi(sam))
        assertFalse(NeedsYouLayoutRules.isFyi(alex))
        assertEquals(listOf(alex.key, group.key), NeedsYouLayoutRules.messages(chats).map { it.key })
        assertEquals(listOf(sam.key), NeedsYouLayoutRules.fyi(chats).map { it.key })
        assertEquals(2, NeedsYouLayoutRules.replyCount(chats))
    }

    @Test
    fun theHeaderCountsAndLeavesOutNothing() {
        assertEquals("2 to decide · 4 to reply · 4 FYI", NeedsYouLayoutRules.subtitle(2, 4, 4))
        assertEquals("4 to reply", NeedsYouLayoutRules.subtitle(0, 4, 0))
        assertEquals("All clear", NeedsYouLayoutRules.subtitle(0, 0, 0))
    }

    @Test
    fun theChipsCarryTheirCountsAndEachShowsItsOwnSection() {
        val chips = NeedsYouLayoutRules.chips(2, 0, 4, 138)
        assertEquals(listOf("All", "Decide 2", "Reply", "FYI 4", "Mail 138"), chips.map { it.label })
        assertTrue(NeedsYouSection.entries.all { NeedsYouLayoutRules.shows(NeedsYouFilter.ALL, it) })
        assertTrue(NeedsYouLayoutRules.shows(NeedsYouFilter.REPLY, NeedsYouSection.MESSAGES))
        assertFalse(NeedsYouLayoutRules.shows(NeedsYouFilter.REPLY, NeedsYouSection.FYI))
        assertFalse(NeedsYouLayoutRules.shows(NeedsYouFilter.MAIL, NeedsYouSection.DECIDE))
    }

    @Test
    fun aRowIsInitialsNameTimeOneLineAndABadge() {
        val row = NeedsYouLayoutRules.row(group)
        assertEquals("GT", row.initials)
        assertEquals("Game testing group", row.name)
        assertEquals("2 to reply", row.badge)
        assertTrue(row.hot)
        assertEquals("14:21", row.time)
        assertFalse(row.line.contains('\n'))
        val quiet = NeedsYouLayoutRules.row(sam)
        assertEquals("1 new", quiet.badge)
        assertFalse(quiet.hot)
        assertEquals("S", quiet.initials)
    }

    @Test
    fun aOneToOneLineDropsItsOwnName() {
        val c = alex.copy(summary = null, top = listOf("Alex: Asks if you can work from home"))
        assertEquals("Asks if you can work from home", NeedsYouLayoutRules.line(c))
        val busy = alex.copy(summary = "All is well; the show is Wed")
        assertEquals("All is well; the show is Wed", NeedsYouLayoutRules.line(busy))
    }

    @Test
    fun initialsSkipALeadingYourOrA() {
        assertEquals("W", NeedsYouLayoutRules.initials("Your wife"))
        assertEquals("F", NeedsYouLayoutRules.initials("A friend"))
        assertEquals("?", NeedsYouLayoutRules.initials("🙂"))
        assertEquals("FYI cleared · 1 chat", NeedsYouLayoutRules.clearedLine(1))
    }
}
