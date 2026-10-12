package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fold review 2026-10-12, bug 3: Jeanette asking "Is there no match this Saturday?" in BUFC U7s lifts that group's card
 * to the top of Needs you with "Jeanette asked" — someone Meka watches for counts in groups too.
 */
class GroupAskTest {
    private val watching = TriageSettings(watched = setOf("Jeanette", "Mum"))

    private fun msg(who: String, text: String, group: String? = "BUFC U7s", at: Long = 0) =
        CapturedItem("id-$who-$text-$at", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, at)

    @Test
    fun aQuestionFromSomeoneWatchedInAGroupIsReadLikeAMention() {
        assertEquals(
            TriageRoute.AskAi(mentioned = true, watched = true),
            MessageTriageRules.route(msg("Jeanette", "Is there no match this Saturday?"), watching),
        )
        assertEquals(
            TriageRoute.AskAi(mentioned = true, watched = true),
            MessageTriageRules.route(msg("jeanette ", "Can someone take Rex to training"), watching),
        )
        // Her chatter that asks nothing stays in the digest; so does anyone else's question.
        assertEquals(TriageRoute.Digest("bufc u7s"), MessageTriageRules.route(msg("Jeanette", "Great game today 👏"), watching))
        assertEquals(TriageRoute.Digest("bufc u7s"), MessageTriageRules.route(msg("Coach Dave", "Is everyone ok for Saturday?"), watching))
        // A mention from her is still a mention, marked as hers.
        assertEquals(TriageRoute.AskAi(mentioned = true, watched = true), MessageTriageRules.route(msg("Jeanette", "Meka has the kit"), watching))
        // A 1:1 message is triaged as before, never "asked in a group".
        assertEquals(TriageRoute.AskAi(mentioned = false), MessageTriageRules.route(msg("Jeanette", "Milk?", group = null), watching))
        // An ignored group is never read, not even her; WhatsApp's count in the title doesn't hide the group.
        val ignored = watching.copy(groupModes = mapOf("BUFC U7s" to GroupMode.IGNORE))
        assertEquals(TriageRoute.Skip, MessageTriageRules.route(msg("Jeanette", "Is there no match?", "BUFC U7s (2 messages)"), ignored))
        // With no one watched (the Mac, or before the Fold fills it), a group question is digest chatter.
        assertEquals(TriageRoute.Digest("bufc u7s"), MessageTriageRules.route(msg("Jeanette", "Is there no match this Saturday?"), TriageSettings()))
        // A notification holding her question is not cleared from the shade like chatter.
        assertFalse(GroupDigestRules.clearsNotification(listOf(msg("Jeanette", "Who's taking Logan?")), watching, setOf("id-Jeanette-Who's taking Logan?-0")))
    }

    @Test
    fun asksReadsAQuestionOrARequestsUsualWords() {
        assertTrue(MessageTriageRules.asks("Is there no match this Saturday?"))
        assertTrue(MessageTriageRules.asks("Please bring oranges"))
        assertTrue(MessageTriageRules.asks("Don’t forget the subs"))
        assertTrue(MessageTriageRules.asks("who's taking them"))
        assertFalse(MessageTriageRules.asks("Great game today"))
        assertFalse(MessageTriageRules.asks("Pleased with the boys"))
    }

    @Test
    fun herQuestionsCardSaysJeanetteAskedAndItsGroupLeadsNeedsYou() {
        val world = SyncWorld()
        val fold = TriageCards(world.device("android").replica, { world.clock.nowMs }, LocalCalendar.UTC)
        world.clock.nowMs = 10 * 60_000L
        val fyi = MessageTriage(TriageLane.FYI, summary = "Asks if there's no match this Saturday")
        val hers = assertNotNull(fold.save(msg("Jeanette", "Is there no match this Saturday?", at = 9 * 60_000L), fyi, watched = true))
        assertEquals("Jeanette", hers.askedBy)
        assertTrue(hers.spoken.startsWith("Jeanette asked. Jeanette in BUFC U7s"))
        // A 1:1 message is never "asked in a group", even marked watched.
        val dm = assertNotNull(fold.save(msg("Jeanette", "Milk?", group = null, at = 8 * 60_000L), fyi, watched = true))
        assertNull(dm.askedBy)
        val tunde = assertNotNull(fold.save(
            msg("Tunde", "@Meka coming Saturday?", group = "Barça lads", at = 9 * 60_000L + 30_000),
            MessageTriage(TriageLane.NEEDS_REPLY, draft = "Yes", summary = "Asks if you're coming"),
        ))
        assertNull(tunde.askedBy)
        val coach = assertNotNull(fold.save(msg("Coach Dave", "@Meka kit?", at = 9 * 60_000L + 40_000), fyi))

        val chats = TriageChatRules.chats(fold.open())
        // Her group first (even above a chat waiting on a reply), with her line on top.
        assertEquals(listOf("BUFC U7s", "Barça lads", "Jeanette"), chats.map { it.title })
        val bufc = chats.first()
        assertEquals("Jeanette", bufc.askedBy)
        assertEquals("Jeanette asked", TriageChatRules.askedLine(bufc.askedBy!!))
        assertEquals("Jeanette: Asks if there's no match this Saturday", bufc.top.first())
        assertEquals(listOf(hers.id, coach.id), bufc.ids)
        assertTrue(bufc.spoken.startsWith("Jeanette asked in BUFC U7s, 2 new"))
        assertNull(chats[1].askedBy)
        assertNull(chats[2].askedBy)
    }
}
