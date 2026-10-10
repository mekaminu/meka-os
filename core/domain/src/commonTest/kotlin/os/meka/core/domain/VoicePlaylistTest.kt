package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Play my messages" in Talk (call assistant polish 8c). */
class VoicePlaylistTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_370L
    private fun at(h: Int, m: Int, d: Long = day) = d * CivilDate.DAY_MS + (h * 60 + m) * 60_000L
    private val now = at(19, 0)

    private fun voice(id: String, who: String, text: String?, atMs: Long, urgent: Boolean = false, audio: Boolean = false, transcribing: Boolean = false) =
        CapturedItem(id, CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, who, text, null, atMs, urgent = urgent, hasAudio = audio, transcribing = transcribing)

    private fun msg(who: String, text: String, atMs: Long) =
        CapturedItem(Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, null, atMs, text), CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, null, atMs)

    @Test
    fun theWaysMekaAsksForHisMessages() {
        listOf(
            "Play my messages", "play my voice messages", "Can you play me the voicemails please", "play messages",
            "listen to my messages", "read my voice messages", "hear my voicemail", "any voice messages?",
            "Any messages?", "do I have any messages", "have I got any new voicemails", "Who called?", "did anyone ring",
            "Meka, who rang?", "has anyone called", "any missed calls",
        ).forEach { assertTrue(VoicePlaylistRules.isPlayRequest(it), it) }
        listOf(
            "read my messages", "play some music", "play my messages from Tom tomorrow", "message Tom", "who is Tom",
            "any plans today", "what messages did Jeanette send", "", "play",
        ).forEach { assertFalse(VoicePlaylistRules.isPlayRequest(it), it) }
    }

    @Test
    fun aRequestIsPlayedOnTheDeviceWithoutAskingTheAi() {
        assertEquals(TalkReply.PlayMessages("play my messages"), TalkRules.reply("play my messages", 0))
        // With a card on offer, a yes is still a yes and a no a no.
        assertEquals(TalkReply.Confirm(listOf(0)), TalkRules.reply("yes", 1))
        val step = TalkFlow.heard(TalkFlow.start().session, "Who called?")
        assertEquals(listOf<TalkEffect>(TalkEffect.PlayMessages), step.effects)
        assertEquals(TalkPhase.SPEAKING, step.session.phase)
        assertEquals(0, step.session.questions) // not counted against the conversation's AI calls
        assertEquals(VoicePlaylistRules.PLAYED, step.session.conversation.turns.last().answer)
        // Said and played: listen again. Talked over: stop and listen.
        assertEquals(listOf<TalkEffect>(TalkEffect.Listen), TalkFlow.spoke(step.session).effects)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Listen), TalkFlow.bargeIn(step.session).effects)
    }

    @Test
    fun urgentFirstThenOldestEachIntroducedThenItsRecordingOrWords() {
        val items = listOf(
            voice("h1", "Dentist", "Please call back about Tuesday", at(10, 5), audio = true),
            voice("h2", "Mum", "Ring me, it's urgent", at(14, 30), urgent = true, audio = true),
            voice("h3", "07700 900123", null, at(18, 40, day - 1)),
            msg("Ada", "Milk?", at(12, 0)),
        )
        val list = VoicePlaylistRules.build(AfterWorkSummaries.build(items, PeopleLists()), now, cal)
        assertEquals(3, list.count)
        assertEquals(0, list.more)
        assertEquals(
            listOf(
                PlayStep.Say("You've got 3 voice messages."),
                PlayStep.Say("First, urgent, from Mum at 14:30."),
                PlayStep.Recording("h2", "They said: Ring me, it's urgent."),
                PlayStep.Say("Second, from 07700 900123 yesterday at 18:40."),
                PlayStep.Say("No words came through."),
                PlayStep.Say("Third, from Dentist at 10:05."),
                PlayStep.Recording("h1", "They said: Please call back about Tuesday."),
                PlayStep.Say("That's all of them. Anything else?"),
            ),
            list.steps,
        )
    }

    @Test
    fun oneMessageIsntNumberedAndWordsStillOnTheirWaySaySo() {
        val list = VoicePlaylistRules.build(AfterWorkSummaries.build(listOf(voice("h1", "Tom", null, at(18, 0), transcribing = true)), PeopleLists()), now, cal)
        assertEquals(
            listOf(
                PlayStep.Say("You've got one voice message."),
                PlayStep.Say("From Tom at 18:00."),
                PlayStep.Say("Their words are still on their way."),
                PlayStep.Say("That's it. Anything else?"),
            ),
            list.steps,
        )
        // A kept recording with no words: if it can't play, MEKA says so.
        val r = VoicePlaylistRules.build(AfterWorkSummaries.build(listOf(voice("h1", "Tom", null, at(18, 0), audio = true)), PeopleLists()), now, cal)
        assertEquals(PlayStep.Recording("h1", VoicePlaylistRules.COULDNT_PLAY), r.steps[2])
    }

    @Test
    fun atMostFiveThenTheRestWaitInTheSummary() {
        val items = (0 until 7).map { voice("h$it", "Caller $it", "Message $it", at(9 + it, 0)) }
        val list = VoicePlaylistRules.build(AfterWorkSummaries.build(items, PeopleLists()), now, cal)
        assertEquals(5, list.count)
        assertEquals(2, list.more)
        assertEquals(PlayStep.Say("You've got 7 voice messages. Here are the first 5."), list.steps.first())
        assertEquals(PlayStep.Say("And 2 more in the after-work summary. Anything else?"), list.steps.last())
        assertEquals(PlayStep.Say("First, from Caller 0 at 09:00."), list.steps[1])
        assertEquals(PlayStep.Say("Fifth, from Caller 4 at 13:00."), list.steps[9])
    }

    @Test
    fun noVoiceMessagesSaysWhatElseIsHeld() {
        assertEquals(
            listOf<PlayStep>(PlayStep.Say("No voice messages waiting. Anything else?")),
            VoicePlaylistRules.build(AfterWorkSummary(emptyList()), now, cal).steps,
        )
        val held = AfterWorkSummaries.build(listOf(msg("Ada", "Milk?", at(12, 0)), msg("Tom", "Hi", at(12, 5))), PeopleLists())
        assertEquals(
            PlayStep.Say("No voice messages. There are 2 messages in the after-work summary. Anything else?"),
            VoicePlaylistRules.build(held, now, cal).steps.single(),
        )
    }

    @Test
    fun olderDaysAndLongWordsReadNaturally() {
        assertEquals("on ${CivilDate.longLabel(day - 3)} at 09:12", VoicePlaylistRules.spokenWhen(at(9, 12, day - 3), now, cal))
        val long = "This is a sentence about the boiler. ".repeat(20).trim()
        val line = VoicePlaylistRules.wordsLine(voice("h1", "Tom", long, at(9, 0)))
        assertTrue(line.length > TalkRules.MAX_SPOKEN_CHARS, "a caller's words aren't cut like an answer")
        assertTrue(line.length <= VoicePlaylistRules.MAX_WORDS_CHARS + 20)
    }
}
