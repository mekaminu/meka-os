package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The group digest's gist (build plan V1, messages assistant slice 4b): what is sent, what the answer may become, the synced cards. */
class GroupGistTest {
    private val cal = LocalCalendar.UTC
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int) = cal.toEpochMs(fri, h * 60 + m)
    private fun msg(who: String, text: String?, group: String? = "Barça lads", atMs: Long) =
        CapturedItem("$who-$text-$atMs", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, atMs)
    private fun hhmm(ms: Long) = TaskWhenRules.timeLabel(cal.minuteOfDay(ms))

    private val lads = (0 until 7).map { i -> msg(listOf("Tunde", "Femi", "Obi")[i % 3], "line $i about Getafe", atMs = at(11, i)) }
    private val family = listOf(msg("Mum", "pizza Saturday?", "Family", at(11, 30)), msg("Ada", "yes!", "Family", at(11, 31)))
    private val items = lads + family
    private val cards = GroupDigestRules.cards(items, TriageSettings(), emptyMap())

    @Test
    fun onlyBusyGroupsAreSentWithTheirLatestLinesSinceMekaCaughtUp() {
        val groups = GroupGistRules.groups(cards, items, emptyMap(), emptySet(), ::hhmm)
        // Family has two messages: its card's three lines say it all, so it isn't sent.
        assertEquals(listOf("barça lads"), groups.map { it.groupKey })
        assertEquals(7, groups[0].lines.size)
        assertEquals(GroupGistRules.Line("Tunde", "11:00", "line 0 about Getafe"), groups[0].lines.first())
        // Caught up at 11:01: only what came after (5 lines) — still busy enough.
        val seen = mapOf("barça lads" to at(11, 1))
        val since = GroupGistRules.groups(GroupDigestRules.cards(items, TriageSettings(), seen), items, seen, emptySet(), ::hhmm)
        assertEquals(5, since.single().lines.size)
        // Already gisted this slot: nothing to send.
        assertTrue(GroupGistRules.groups(cards, items, emptyMap(), setOf("barça lads"), ::hhmm).isEmpty())
        // A long line is cut at a word.
        val long = items + msg("Femi", "word ".repeat(100), atMs = at(11, 50))
        val cut = GroupGistRules.groups(GroupDigestRules.cards(long, TriageSettings(), emptyMap()), long, emptyMap(), emptySet(), ::hhmm)
        assertTrue(cut[0].lines.last().text.length <= GroupGistRules.MAX_LINE_CHARS && cut[0].lines.last().text.endsWith("…"))
    }

    @Test
    fun theAnswerIsCheckedAgainstWhatWasSent() {
        val sent = GroupGistRules.groups(cards, items, emptyMap(), emptySet(), ::hhmm)
        val raw = listOf(
            GroupGistRules.RawGroup(
                "BARÇA LADS", "  Lineup debate for Getafe;\nTunde has tickets  ",
                listOf(
                    GroupGistRules.RawAsk("needs_reply", "tunde", "Asks if you want a ticket"),
                    // Not someone who wrote in the group: dropped.
                    GroupGistRules.RawAsk("needs_reply", "Stranger", "Wants your bank details"),
                    GroupGistRules.RawAsk("action", "Femi", "Asks you to bring the ball", listOf(RawRequestProposal("task", "Bring the ball", words = "tomorrow"))),
                    // An action with nothing MEKA can do: dropped.
                    GroupGistRules.RawAsk("action", "Obi", "Wants £20", listOf(RawRequestProposal("transfer", "Send £20"))),
                    GroupGistRules.RawAsk("fyi", "Obi", "Nothing"),
                ),
            ),
            // A group that wasn't sent: ignored.
            GroupGistRules.RawGroup("Work", "Secret plans", listOf(GroupGistRules.RawAsk("needs_reply", "Boss", "Reply now"))),
        )
        val checked = GroupGistRules.check(sent, raw, fri, 12 * 60 + 30)
        assertEquals(listOf(GroupGistRules.Gist("barça lads", "Lineup debate for Getafe; Tunde has tickets")), checked.gists)
        assertEquals(listOf("Tunde" to TriageLane.NEEDS_REPLY, "Femi" to TriageLane.ACTION), checked.asks.map { it.from to it.lane })
        assertEquals("Barça lads", checked.asks[0].group)
        assertEquals(fri + 1, checked.asks[1].proposals.single().day)
        // A group the model skipped still gets a row (no gist), so it isn't sent again this slot.
        assertEquals(listOf(GroupGistRules.Gist("barça lads", null)), GroupGistRules.check(sent, emptyList(), fri, 750).gists)
    }

    @Test
    fun theCardsSyncWithoutTheMessagesAndCaughtUpClearsThemOnBothDevicesWithUndo() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        world.clock.nowMs = at(12, 31)
        val fold = GroupGists(a.replica) { world.clock.nowMs }
        val mac = GroupGists(m.replica) { world.clock.nowMs }
        val slot = at(12, 30)
        assertTrue(fold.save(cards[0], slot, GroupDigestRules.LUNCH_MINUTE, "Lineup debate for Getafe"))
        assertTrue(fold.save(cards[1], slot, GroupDigestRules.LUNCH_MINUTE, null))
        assertFalse(fold.save(cards[0], slot, GroupDigestRules.LUNCH_MINUTE, "again"))
        assertEquals(setOf("barça lads", "family"), fold.gisted(slot))
        a.sync(); m.sync()
        val shown = mac.open()
        assertEquals(listOf("Barça lads", "Family"), shown.map { it.title })
        assertEquals("7 messages", shown[0].countLine)
        assertEquals("Lunchtime digest", shown[0].slotLabel)
        assertEquals("Lineup debate for Getafe", shown[0].gist)
        assertNull(shown[1].gist)
        assertEquals("Lunchtime digest · 2 groups · 9 messages", GroupGistRules.headLine(shown))
        assertEquals("Barça lads, 7 messages from Tunde, Obi and Femi. Lineup debate for Getafe.", shown[0].spoken)
        // The messages never travel.
        assertTrue(m.replica.entities(EntityTypes.GROUP_GIST).none { e -> e.fields.values.any { it.toString().contains("about Getafe") } })

        // Caught up on the Mac: gone on both; the Fold's digest learns when.
        world.clock.nowMs = at(12, 40)
        val before = mac.caughtUp(listOf("barça lads"))
        m.sync(); a.sync()
        assertEquals(listOf("Family"), fold.open().map { it.title })
        assertEquals(mapOf("barça lads" to at(12, 40)), fold.caughtUpTimes())
        // Undo brings it back everywhere.
        mac.undo(before)
        m.sync(); a.sync()
        assertEquals(listOf("Barça lads", "Family"), fold.open().map { it.title })
        assertTrue(fold.caughtUpTimes().isEmpty())

        // The evening slot's card replaces the lunchtime one; a day later both are gone.
        world.clock.nowMs = at(18, 31)
        fold.save(cards[0], at(18, 30), GroupDigestRules.EVENING_MINUTE, "Saturday at 7")
        assertEquals(listOf("Evening digest" to "Barça lads", "Lunchtime digest" to "Family"), fold.open().map { it.slotLabel to it.title })
        world.clock.nowMs = at(18, 31) + GroupGists.RETENTION_MS + 1
        assertTrue(fold.open().isEmpty())
    }
}
