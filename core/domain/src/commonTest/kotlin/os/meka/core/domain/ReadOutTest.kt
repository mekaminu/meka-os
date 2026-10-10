package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadOutTest {
    private val brief = ReadOut(ReadOut.Kind.BRIEF)
    private val headlines = ReadOut(ReadOut.Kind.HEADLINES)
    private fun topic(id: String) = ReadOut(ReadOut.Kind.TOPIC, id)

    @Test
    fun theBriefAndTheHeadlinesAreAskedForTheWaysPeopleAsk() {
        listOf(
            "read my brief", "Read me my morning brief, please", "brief me", "what's in my morning brief?", "the brief",
            "MEKA, read the briefing", "go through my brief",
        ).forEach { assertEquals(brief, ReadOutRules.request(it), it) }
        listOf(
            "read the headlines", "the headlines", "what's the news?", "any news?", "what's in the news today",
            "tell me the news", "give me the headlines", "read me the news headlines please", "News.", "top stories",
            "is there any news",
        ).forEach { assertEquals(headlines, ReadOutRules.request(it), it) }
    }

    @Test
    fun oneTopicsNewsIsNamedBeforeOrAfterTheNewsWord() {
        assertEquals(topic(NewsTopics.BARCA.id), ReadOutRules.request("Barça news"))
        assertEquals(topic(NewsTopics.BARCA.id), ReadOutRules.request("what's the latest Barca news?"))
        assertEquals(topic(NewsTopics.BARCA.id), ReadOutRules.request("any news about Barcelona"))
        assertEquals(topic(NewsTopics.BARCA.id), ReadOutRules.request("the latest on Barça"))
        assertEquals(topic(NewsTopics.BARCA.id), ReadOutRules.request("is there any Barça news"))
        assertEquals(topic(NewsTopics.AI.id), ReadOutRules.request("read me the AI headlines"))
        assertEquals(topic(NewsTopics.AI.id), ReadOutRules.request("A.I. news"))
        assertEquals(topic(NewsTopics.SPAIN.id), ReadOutRules.request("Spanish football news"))
        assertEquals(topic(NewsTopics.TECH.id), ReadOutRules.request("tech news"))
        assertEquals(topic(NewsTopics.WORLD.id), ReadOutRules.request("world news this morning"))
    }

    @Test
    fun anythingWithMoreInItIsAQuestionForMeka() {
        listOf(
            "what's the news on the strike and should I drive?", "read my messages", "play my messages",
            "what's new", "when do Barça play next?", "read the email from school", "Barça", "the latest",
            "brief me on the meeting", "add news to my list",
        ).forEach { assertNull(ReadOutRules.request(it), it) }
    }

    @Test
    fun talkReadsItOnTheDeviceWithoutAskingTheAiAndRemembersOnlyThatItRead() {
        val start = TalkFlow.start().session
        val step = TalkFlow.heard(start, "read the Barça news")
        assertEquals(listOf<TalkEffect>(TalkEffect.ReadOut(topic(NewsTopics.BARCA.id))), step.effects)
        assertEquals(TalkPhase.SPEAKING, step.session.phase)
        assertEquals(0, step.session.questions) // not a question: doesn't count towards the cap
        assertEquals("Read Meka the latest Barça news aloud.", step.session.conversation.turns.single().answer)
        assertEquals("Reading the latest Barça news…", ReadOutRules.showing(topic(NewsTopics.BARCA.id)))
        assertEquals("Reading your morning brief…", ReadOutRules.showing(brief))
        // Then it listens again, and talking over it stops it as for any line.
        assertEquals(listOf<TalkEffect>(TalkEffect.Listen), TalkFlow.spoke(step.session).effects)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Listen), TalkFlow.bargeIn(step.session).effects)
        // With a card on offer, "yes" is still a yes; "the headlines" is a read-out.
        assertEquals(TalkReply.ReadOut("the headlines", headlines), TalkRules.reply("the headlines", 1))
    }

    private fun item(id: String, title: String, source: String, topic: String, at: Long) =
        NewsItem(id, title, null, source, topic, "$source · 2 h ago", null, at)

    private fun place(lanes: List<NewsLane>, chosen: List<String>) = NewsPlace(
        lanes = lanes,
        topics = NewsTopics.ALL.map { NewsTopicChoice(it.id, it.label, it.id in chosen) },
        emptyLine = null,
    )

    @Test
    fun aTopicsNewestStoriesAreReadWithTheirSourcesAndNoMoreThanFive() {
        val items = (1..7).map { item("b$it", "Barça story $it", "Mundo Deportivo", "barca", 1_000L - it) }
        val p = place(listOf(NewsLane("barca", "Barça", "From Mundo Deportivo", items)), listOf("barca"))
        val text = ReadOutRules.text(topic("barca"), MorningBriefView.EMPTY, p)
        assertTrue(text.startsWith("The latest Barça news. From Mundo Deportivo: Barça story 1. "), text)
        assertTrue(text.contains("Barça story 5.") && !text.contains("Barça story 6"), text)
        assertTrue(text.endsWith("Anything else?"), text)
    }

    @Test
    fun aTopicNotChosenOrQuietIsSaidPlainly() {
        val none = place(emptyList(), listOf("barca"))
        assertEquals("AI isn't one of your news topics. You can turn it on in News. Anything else?", ReadOutRules.text(topic("ai"), MorningBriefView.EMPTY, none))
        assertEquals("There's no Barça news in the last two days. Anything else?", ReadOutRules.text(topic("barca"), MorningBriefView.EMPTY, none))
        assertEquals(ReadOutRules.NO_HEADLINES, ReadOutRules.text(headlines, MorningBriefView.EMPTY, none))
    }

    @Test
    fun theHeadlinesAndTheBriefAreReadAsTheBriefReadsThem() {
        val v = MorningBriefView.EMPTY.copy(
            dateLabel = "Sat 10 Oct",
            headlines = listOf(
                BriefHeadline("n1", "Barça v Getafe: the line-ups", null, "Mundo Deportivo · Barça · 2 h ago"),
                BriefHeadline("n2", "Markets rise", null, "BBC News · Business · 1 h ago"),
            ),
        )
        assertEquals(
            "In the news. From Mundo Deportivo: Barça versus Getafe: the line-ups. From BBC News: Markets rise. Anything else?",
            ReadOutRules.text(headlines, v, NewsPlace.EMPTY),
        )
        assertEquals(BriefSpeech.script(v) + " Anything else?", ReadOutRules.text(brief, v, NewsPlace.EMPTY))
    }
}
