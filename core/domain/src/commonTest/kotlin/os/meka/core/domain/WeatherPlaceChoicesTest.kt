package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walk-through week 5, A17 (and g4a 9): Calendars' weather Home and Work are rows opening a sheet, not two open "Your
 * town" boxes, and the row's line never sends him to About you to set what the row itself sets. Invented places only.
 */
class WeatherPlaceChoicesTest {
    private val found = WeatherForecast.EMPTY.copy(asked = "Oakfield", found = true, place = "Oakfield")

    @Test
    fun aRowSaysTheTownOrNotSet() {
        val set = WeatherPlaceRules.view("Oakfield", found, home = null)
        assertEquals("Home", WeatherPlaceChoiceRules.homeRow(set).title)
        assertEquals("Oakfield", WeatherPlaceChoiceRules.homeRow(set).value)
        assertEquals("Home weather, Oakfield", WeatherPlaceChoiceRules.homeRow(set).spoken)
        assertEquals("Not set", WeatherPlaceChoiceRules.homeRow(WeatherPlaceView.NO_HOME).value)
        assertEquals("Not set", WeatherPlaceChoiceRules.workRow(WeatherPlaceView.NO_WORK).value)
        assertEquals("Work weather, Not set", WeatherPlaceChoiceRules.workRow(WeatherPlaceView.NO_WORK).spoken)
    }

    @Test
    fun theLineWithNoPlaceSaysWhatIsShownNotWhereElseToSetIt() {
        val home = WeatherPlaceChoiceRules.line(WeatherPlaceView.NO_HOME, home = true)
        assertEquals("Showing London's weather until you add your town", home)
        assertFalse("About you" in home)
        assertFalse("About you" in WeatherPlaceChoiceRules.line(WeatherPlaceView.NO_WORK, home = false))
        val back = WeatherPlaceView("", "Going back to London's weather… add your home in Ask → Settings → About you", pending = true)
        assertEquals("Going back to London's weather…", WeatherPlaceChoiceRules.line(back, home = true))
        // A set place keeps the forecast's own line.
        val set = WeatherPlaceRules.view("Oakfield", found, home = null)
        assertEquals(set.line, WeatherPlaceChoiceRules.line(set, home = true))
    }

    @Test
    fun theSheetHoldsTheTownAndOffersTheWayBack() {
        val own = WeatherPlaceRules.view("Oakfield", found, home = "Riverside")
        val sheet = WeatherPlaceChoiceRules.homeSheet(own, aboutHome = "Riverside")
        assertEquals("Home", sheet.title)
        assertEquals("Oakfield", sheet.value)
        assertEquals("Riverside", sheet.hint)
        assertEquals("Use About you's home", sheet.clearLabel)
        assertTrue(sheet.blankSaves)
        assertEquals(WeatherPlaceRules.MAX_NAME, sheet.maxLength)
        assertTrue("Open-Meteo" in sheet.note)
        // Following About you: nothing to go back to.
        val following = WeatherPlaceRules.view(null, found.copy(asked = "Riverside", place = "Riverside"), home = "Riverside")
        assertNull(WeatherPlaceChoiceRules.homeSheet(following, aboutHome = "Riverside").clearLabel)
        // No About you home: the example is "Your town" and the way back is Clear.
        val alone = WeatherPlaceChoiceRules.homeSheet(own, aboutHome = null)
        assertEquals("Your town", alone.hint)
        assertEquals("Clear", alone.clearLabel)
        assertNull(WeatherPlaceChoiceRules.homeSheet(WeatherPlaceView.NO_HOME, aboutHome = null).clearLabel)
    }

    @Test
    fun workSheetFollowsAboutYousWork() {
        val work = PlacesRules.workView("Cambridge", WeatherForecast.EMPTY.copy(asked = "Cambridge", found = true, place = "Cambridge"), work = "Riverside")
        val sheet = WeatherPlaceChoiceRules.workSheet(work, aboutWork = "Riverside")
        assertEquals("Work", sheet.title)
        assertEquals("Cambridge", sheet.value)
        assertEquals("Use About you's work", sheet.clearLabel)
        assertNull(WeatherPlaceChoiceRules.workSheet(WeatherPlaceView.NO_WORK, aboutWork = null).clearLabel)
    }

    @Test
    fun aNameThatCantBeATownIsRefusedAndABlankFollowsAboutYou() {
        assertEquals(WeatherPlaceChoiceRules.BAD, WeatherPlaceChoiceRules.refusal("<b>"))
        assertNull(WeatherPlaceChoiceRules.refusal(""))
        assertNull(WeatherPlaceChoiceRules.refusal("St Neots"))
    }
}
