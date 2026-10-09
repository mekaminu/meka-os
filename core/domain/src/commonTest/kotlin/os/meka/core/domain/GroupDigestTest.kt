package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The group digest in Needs you (build plan V1, messages assistant slice 4): when it's due, its cards, a group opened. */
class GroupDigestTest {
    private val day = 1_000_000_000L
    private fun at(h: Int, m: Int) = day + (h * 60 + m) * 60_000L
    private fun msg(who: String, text: String?, group: String? = "Barça lads", atMs: Long = at(10, 0), id: String = "$who-$text-$atMs") =
        CapturedItem(id, CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, atMs)

    private val chatter = listOf(
        msg("Tunde", "lineup for Getafe?", atMs = at(9, 0)),
        msg("Femi", "Lewandowski up top", atMs = at(9, 5)),
        msg("Obi", "pizza at mine Saturday", "Family", at(11, 0)),
    )

    @Test
    fun theDigestIsDueFromLunchtimeAndTheEveningUntilMekaCatchesUp() {
        val s = TriageSettings()
        val morning = GroupDigestRules.view(chatter, s, emptyMap(), day, 11 * 60 + 30)!!
        assertFalse(morning.due)
        assertEquals("Group chats", morning.title)
        assertEquals("2 groups · 3 messages · Digest at 12:30", morning.line)

        val lunch = GroupDigestRules.view(chatter, s, emptyMap(), day, 12 * 60 + 30)!!
        assertTrue(lunch.due)
        assertEquals("Lunchtime digest", lunch.title)
        assertEquals("2 groups · 3 messages", lunch.line)
        assertEquals(listOf("barça lads", "family"), lunch.cards.map { it.groupKey })

        // Caught up at 12:40: nothing until a group has news; news at 13:00 waits quietly for the evening digest.
        val seen = GroupDigestRules.caughtUp(emptyMap(), lunch.cards.map { it.groupKey }, at(12, 40))
        assertNull(GroupDigestRules.view(chatter, s, seen, day, 12 * 60 + 45))
        val more = chatter + msg("Femi", "who's driving?", atMs = at(13, 0))
        val afternoon = GroupDigestRules.view(more, s, seen, day, 15 * 60)!!
        assertFalse(afternoon.due)
        assertEquals("1 group · 1 message · Digest at 18:30", afternoon.line)
        val evening = GroupDigestRules.view(more, s, seen, day, 18 * 60 + 31)!!
        assertTrue(evening.due)
        assertEquals("Evening digest", evening.title)
        assertEquals("Next digest tomorrow at 12:30", GroupDigestRules.nextLine(19 * 60))
    }

    @Test
    fun aGroupSwitchedToNormalOrIgnoreLeavesTheDigest() {
        val normal = TriageSettings(groupModes = GroupDigestRules.setMode(emptyMap(), "barça LADS", GroupMode.NORMAL))
        assertEquals(listOf("family"), GroupDigestRules.cards(chatter, normal, emptyMap()).map { it.groupKey })
        val ignored = TriageSettings(groupModes = mapOf("Family" to GroupMode.IGNORE, "Barça lads" to GroupMode.IGNORE))
        assertNull(GroupDigestRules.view(chatter, ignored, emptyMap(), day, 13 * 60))
        // Back to Digest isn't stored (it's the default).
        assertEquals(emptyMap(), GroupDigestRules.setMode(mapOf("Barça lads" to GroupMode.NORMAL), "barça lads", GroupMode.DIGEST))
        assertEquals("Barça lads · back to WhatsApp as usual", GroupDigestRules.modeLine("Barça lads", GroupMode.NORMAL))
    }

    @Test
    fun anOpenedGroupShowsItsLatestMessagesOldestFirst() {
        val many = (0 until 15).map { msg("P$it", "message $it", atMs = at(9, it)) } + msg("Obi", "elsewhere", "Family", at(9, 30))
        val t = GroupDigestRules.expanded(many, "barça lads", 0L) { "t$it" }
        assertEquals(GroupDigestRules.EXPANDED_LINES, t.lines.size)
        assertEquals("message 3", t.lines.first().text)
        assertEquals("message 14", t.lines.last().text)
        assertEquals("P14 · t${at(9, 14)}", t.lines.last().who)
        assertEquals(3, t.earlier)
        assertEquals("+3 earlier", t.earlierLine)
        // Only what came after Meka caught up.
        assertEquals(listOf("message 14"), GroupDigestRules.expanded(many, "barça lads", at(9, 13)) { "" }.lines.map { it.text })
        assertNull(GroupDigestRules.expanded(many, "family", 0L) { "" }.earlierLine)
    }

    @Test
    fun onlyANotificationWhollyInTheDigestIsCleared() {
        val s = TriageSettings()
        val kept = chatter.map { it.id }.toSet()
        assertTrue(GroupDigestRules.clearsNotification(chatter.take(2), s, kept))
        // Not yet kept (offline), a mention of Meka, a 1:1 chat, a Normal group: left alone.
        assertFalse(GroupDigestRules.clearsNotification(chatter.take(2), s, emptySet()))
        val mention = msg("Tunde", "@Meka you in?", atMs = at(9, 10))
        assertFalse(GroupDigestRules.clearsNotification(chatter.take(2) + mention, s, kept + mention.id))
        val oneToOne = msg("Tunde", "hi", group = null)
        assertFalse(GroupDigestRules.clearsNotification(listOf(oneToOne), s, setOf(oneToOne.id)))
        val normal = TriageSettings(groupModes = mapOf("Barça lads" to GroupMode.NORMAL))
        assertFalse(GroupDigestRules.clearsNotification(chatter.take(2), normal, kept))
        assertFalse(GroupDigestRules.clearsNotification(emptyList(), s, kept))
    }

    @Test
    fun caughtUpForgetsGroupsAfterAWeek() {
        val week = MessageTriageRules.DIGEST_RETENTION_MS
        val seen = GroupDigestRules.caughtUp(mapOf("old" to 1L), listOf("barça lads"), week + 10)
        assertEquals(mapOf("barça lads" to week + 10), seen)
    }
}
