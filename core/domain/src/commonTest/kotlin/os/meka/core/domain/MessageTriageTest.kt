package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The messages assistant (build plan V1, slice 1): routing before any AI, the model's triage checked, the group digest. */
class MessageTriageTest {
    // Friday 9 October 2026, the message came at 14:02.
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val now = 14 * 60 + 2

    private fun msg(
        who: String,
        text: String?,
        group: String? = null,
        at: Long = 0,
        kind: CaptureKind = CaptureKind.MESSAGE,
        id: String = "id-$who-$text-$at",
    ) = CapturedItem(id, CaptureApp.WHATSAPP, kind, who, text, group, at)

    @Test
    fun aGroupMessageMentionsMekaOnlyByHisNameAsAWord() {
        assertTrue(MessageTriageRules.mentions("@Meka are you coming?"))
        assertTrue(MessageTriageRules.mentions("meka, you in?"))
        assertTrue(MessageTriageRules.mentions("Ask CHUKWUEMEKA about the tickets"))
        assertTrue(MessageTriageRules.mentions("Tickets for Meka."))
        assertFalse(MessageTriageRules.mentions("Mekanism is a band"))
        assertFalse(MessageTriageRules.mentions("Ameka said hi"))
        assertFalse(MessageTriageRules.mentions("lineup for Getafe?"))
        assertTrue(MessageTriageRules.mentions("Emeka: your turn", listOf("Emeka")))
    }

    @Test
    fun oneToOneMessagesGoToTheAiAndGroupChatterToTheDigest() {
        val s = TriageSettings()
        assertEquals(TriageRoute.AskAi(mentioned = false), MessageTriageRules.route(msg("Tunde", "are you coming Saturday?"), s))
        assertEquals(TriageRoute.Digest("barça lads"), MessageTriageRules.route(msg("Tunde", "lineup for Getafe?", "Barça Lads"), s))
        assertEquals(TriageRoute.AskAi(mentioned = true), MessageTriageRules.route(msg("Tunde", "@Meka you coming Saturday?", "Barça lads"), s))
        // Missed calls and empty messages are nothing to triage.
        assertEquals(TriageRoute.Skip, MessageTriageRules.route(msg("Tunde", null, kind = CaptureKind.MISSED_CALL), s))
        assertEquals(TriageRoute.Skip, MessageTriageRules.route(msg("Tunde", "  "), s))
        // Voice notes and photos never go to the AI.
        assertEquals(TriageRoute.VoiceNote, MessageTriageRules.route(msg("Tunde", "🎤 Voice message (0:12)"), s))
        assertEquals(TriageRoute.VoiceNote, MessageTriageRules.route(msg("Tunde", "0:12", kind = CaptureKind.VOICE_MESSAGE), s))
        assertEquals(TriageRoute.LocalFyi, MessageTriageRules.route(msg("Tunde", "📷 Photo"), s))
    }

    @Test
    fun eachGroupsModeAndTheNeverToAiListAreHonoured() {
        val s = TriageSettings(
            groupModes = mapOf("School mums" to GroupMode.IGNORE, " work  chat" to GroupMode.NORMAL),
            neverToAi = setOf("Dr Obi", "Family"),
        )
        // Ignored: not even a mention is read.
        assertEquals(TriageRoute.Skip, MessageTriageRules.route(msg("Ada", "Meka can you bring cake?", "school mums"), s))
        // Normal: chatter is left as WhatsApp shows it; a mention is triaged.
        assertEquals(TriageRoute.Skip, MessageTriageRules.route(msg("Kemi", "standup moved", "Work chat"), s))
        assertEquals(TriageRoute.AskAi(true), MessageTriageRules.route(msg("Kemi", "Meka can you join?", "Work chat"), s))
        // Kept from the AI: FYI with no AI, for the person or the whole group.
        assertEquals(TriageRoute.LocalFyi, MessageTriageRules.route(msg("dr obi", "your results are in"), s))
        assertEquals(TriageRoute.LocalFyi, MessageTriageRules.route(msg("Mum", "Meka call me", "Family"), s))
        assertEquals(TriageRoute.Digest("family"), MessageTriageRules.route(msg("Mum", "dinner at 7", "Family"), s))
        assertEquals(GroupMode.DIGEST, GroupMode.of("nonsense"))
        assertEquals(GroupMode.IGNORE, GroupMode.of("ignore"))
    }

    @Test
    fun aDraftIsKeptOnlyForAReplyAndNeverCarriesAnAddress() {
        val reply = MessageTriageRules.check(RawTriage("needs_reply", "  \"Yes, I'll be there —\n what time?\" ", "Tunde asks if you're coming Saturday"), fri, now)
        assertEquals(TriageLane.NEEDS_REPLY, reply.lane)
        assertEquals("Yes, I'll be there — what time?", reply.draft)
        assertEquals("Tunde asks if you're coming Saturday", reply.summary)
        // A message can't get MEKA to put a link, an email address or a number in Meka's mouth.
        assertNull(MessageTriageRules.cleanDraft("Sure, here: https://evil.example/x"))
        assertNull(MessageTriageRules.cleanDraft("Sure, see bit.ly/abc"))
        assertNull(MessageTriageRules.cleanDraft("Send it to a@b.co please"))
        assertNull(MessageTriageRules.cleanDraft("Call 07700 900123"))
        assertNull(MessageTriageRules.cleanDraft("x".repeat(401)))
        assertEquals("See you 2026-10-15 at 7.30", MessageTriageRules.cleanDraft("See you 2026-10-15 at 7.30"))
        // Still needs a reply without a usable draft (Meka writes his own).
        assertEquals(MessageTriage(TriageLane.NEEDS_REPLY), MessageTriageRules.check(RawTriage("needs_reply", "www.x.com"), fri, now))
        // Only a reply keeps a draft.
        assertNull(MessageTriageRules.check(RawTriage("fyi", "Thanks!"), fri, now).draft)
    }

    @Test
    fun anActionKeepsOnlyCheckedProposalsElseItIsFyi() {
        val action = MessageTriageRules.check(
            RawTriage("action", proposals = listOf(
                RawRequestProposal("task", "pick up the tickets", words = "tomorrow"),
                RawRequestProposal("send_money", "£50 to Tunde"),
            )),
            fri, now,
        )
        assertEquals(TriageLane.ACTION, action.lane)
        assertEquals(listOf(RequestProposal(RequestKind.TASK, "Pick up the tickets", fri + 1, null)), action.proposals)
        val none = MessageTriageRules.check(RawTriage("action", summary = "x", proposals = listOf(RawRequestProposal("send_money", "£50"))), fri, now)
        assertEquals(MessageTriage(TriageLane.FYI, summary = "x"), none)
        // The model can't pick the digest or a lane MEKA doesn't know.
        assertEquals(TriageLane.FYI, MessageTriageRules.check(RawTriage("group_digest"), fri, now).lane)
        assertEquals(TriageLane.FYI, MessageTriageRules.check(RawTriage(null), fri, now).lane)
        val long = MessageTriageRules.check(RawTriage("fyi", summary = "word ".repeat(40)), fri, now).summary!!
        assertTrue(long.length <= MessageTriageRules.MAX_SUMMARY + 1 && long.endsWith("…"), long)
    }

    @Test
    fun theDigestHasOneCardPerBusyGroupBusiestFirst() {
        val s = TriageSettings(groupModes = mapOf("Work chat" to GroupMode.NORMAL))
        val items = listOf(
            msg("Tunde", "lineup for Getafe?", "Barça lads", at = 100),
            msg("Ade", "Pedri starts", "Barça lads", at = 200),
            msg("Tunde", "ticket link in a sec", "Barça lads", at = 300),
            msg("Femi", "Saturday 7pm?", "Barça lads", at = 400),
            msg("Femi", "Saturday 7pm?", "Barça lads", at = 400), // a re-post
            msg("Obi", "+1", "Barça lads", at = 500),
            msg("Mum", "dinner at 7", "Family", at = 450),
            msg("Kemi", "standup moved", "Work chat", at = 460), // Normal: not digested
            msg("Tunde", "@Meka you in?", "Barça lads", at = 470), // a mention: triaged, not digested
            msg("Wife", "milk?", at = 480), // 1:1
            msg("Ada", "old news", "Family", at = 50), // before the last digest
        )
        val cards = MessageTriageRules.digest(items, s, sinceMs = 60)
        assertEquals(listOf("barça lads", "family"), cards.map { it.groupKey })
        val barca = cards[0]
        assertEquals("Barça lads", barca.title)
        assertEquals("5 messages", barca.countLine)
        assertEquals("Obi, Femi and 2 others", barca.people)
        assertEquals(listOf("Tunde: ticket link in a sec", "Femi: Saturday 7pm?", "Obi: +1"), barca.recent)
        assertEquals(500, barca.latestMs)
        assertEquals("Barça lads, 5 messages from Obi, Femi and 2 others.", barca.spoken)
        assertEquals("1 message", cards[1].countLine)
        assertEquals("Mum", cards[1].people)
        assertTrue(MessageTriageRules.digest(items, s, sinceMs = 1_000).isEmpty())
    }
}
