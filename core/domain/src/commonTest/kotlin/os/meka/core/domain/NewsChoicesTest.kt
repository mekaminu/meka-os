package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: News's Spanish sources is a row opening On · Off, not an open chip row. */
class NewsChoicesTest {
    private val rules = NewsChoiceRules

    @Test
    fun spanishIsARowSayingWhatIsInForce() {
        assertEquals(NotifyChoiceRow("Spanish sources", "Off", "Spanish sources, Off. Opens the choices"), rules.spanishRow(false))
        assertEquals("On", rules.spanishRow(true).value)
    }

    @Test
    fun itsSheetSaysWhatEachDoesWithTheOneInForceLit() {
        val s = rules.spanishSheet(on = false)
        assertEquals("Spanish sources", s.title)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Adds Mundo Deportivo, Sport and Spanish Google News to News, the ticker and the brief", s.choices[0].line)
        assertEquals("English only, wherever news shows", s.choices[1].line)
        assertTrue(s.choices[1].spoken.endsWith(", chosen"))
        assertEquals(listOf("On"), rules.spanishSheet(on = true).choices.filter { it.lit }.map { it.label })
    }

    @Test
    fun aChoiceWritesOnlyWhatChanges() {
        assertEquals(true, rules.named(false, CallChoiceRules.ON))
        assertEquals(false, rules.named(true, CallChoiceRules.OFF))
        assertNull(rules.named(false, CallChoiceRules.OFF))
        assertNull(rules.named(true, "later"))
    }

    @Test
    fun followIsARowOpeningASheetThatWaitsForAName() {
        assertEquals("+ Follow a name or word", rules.followRow.label)
        assertEquals("Follow", rules.followSheet.addLabel)
        assertFalse(rules.canFollow(""))
        assertFalse(rules.canFollow(" , a ,"))
        assertTrue(rules.canFollow("Arsenal"))
        assertTrue(rules.canFollow("x, Lamine Yamal"))
    }

    @Test
    fun followingIsOneRowNamingThemAndASheetToStopOne() {
        assertNull(rules.followingRow(emptyList()))
        val two = listOf(NewsFollowChoice("arsenal", "Arsenal", 3), NewsFollowChoice("lamine yamal", "Lamine Yamal", 0))
        assertEquals(NotifyChoiceRow("Following", "Arsenal, Lamine Yamal", "Following 2: Arsenal, Lamine Yamal. Opens the list"), rules.followingRow(two))
        assertEquals("Arsenal, Lamine Yamal +1", rules.followingRow(two + NewsFollowChoice("bitcoin", "Bitcoin", 1))?.value)
        val s = rules.followingSheet(two + NewsFollowChoice("bitcoin", "Bitcoin", 1))
        assertEquals(listOf("Arsenal", "Lamine Yamal", "Bitcoin"), s.choices.map { it.label })
        assertEquals(listOf("3 stories in the last two days · stop following", "No stories yet · stop following", "1 story in the last two days · stop following"), s.choices.map { it.line })
        assertTrue(s.choices.none { it.lit })
        assertEquals("Arsenal", rules.unfollowNamed(two, "Arsenal"))
        assertNull(rules.unfollowNamed(two, "Chelsea"))
    }

    @Test
    fun topicsAreOneRowOpeningTicksThatSayWhatTheyDo() {
        val topics = listOf(NewsTopicChoice("barca", "Barça", true), NewsTopicChoice("ai", "AI", true), NewsTopicChoice("top", "Top stories", true),
            NewsTopicChoice("uk", "UK", false))
        assertEquals(NotifyChoiceRow("Topics", "Barça, AI +1", "Topics 3 of 4: Barça, AI, Top stories. Opens the list"), rules.topicsRow(topics))
        assertEquals("Barça", rules.topicsRow(topics.take(1)).value)
        assertEquals("None chosen", rules.topicsRow(topics.map { it.copy(chosen = false) }).value)
        val s = rules.topicsSheet(topics)
        assertEquals(listOf("Barça", "AI", "Top stories", "UK"), s.choices.map { it.label })
        assertEquals(listOf(true, true, true, false), s.choices.map { it.lit })
        assertEquals(rules.TOPIC_ON_LINE, s.choices[0].line)
        assertEquals(rules.TOPIC_OFF_LINE, s.choices[3].line)
        assertEquals("uk", rules.topicNamed(topics, "uk")?.id)
        assertNull(rules.topicNamed(topics, "UK"))
    }

    @Test
    fun sourcesAreOneRowOpeningTicksWithTheirStories() {
        assertNull(rules.sourcesRow(emptyList()))
        val on = listOf(NewsSourceChoice("bbc news", "BBC News", 3, true), NewsSourceChoice("the verge", "The Verge", 1, true, down = true),
            NewsSourceChoice("sky sports", "Sky Sports", 0, true))
        assertEquals(NotifyChoiceRow("Sources", "All on", "Sources 3, all on. Opens the list"), rules.sourcesRow(on))
        val some = on.mapIndexed { i, s -> if (i == 2) s.copy(on = false) else s }
        assertEquals("1 off", rules.sourcesRow(some)?.value)
        assertEquals("All off", rules.sourcesRow(on.map { it.copy(on = false) })?.value)
        val sheet = rules.sourcesSheet(some)
        assertEquals(listOf("BBC News", "The Verge", "Sky Sports"), sheet.choices.map { it.label })
        assertEquals(listOf("3 stories just now", "1 story just now · not coming through", "Off · its stories stay out"), sheet.choices.map { it.line })
        assertEquals(listOf(true, true, false), sheet.choices.map { it.lit })
        assertEquals("Sky Sports", rules.sourceNamed(some, "sky sports")?.name)
        assertNull(rules.sourceNamed(some, "Sky Sports"))
    }
}
