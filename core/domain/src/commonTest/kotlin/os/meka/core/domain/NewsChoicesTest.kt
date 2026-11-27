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
}
