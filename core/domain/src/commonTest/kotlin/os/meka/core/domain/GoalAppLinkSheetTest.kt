package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-through week 5, A17: a booked habit's workout-app link is a row opening a sheet, not an open box. */
class GoalAppLinkSheetTest {
    private val R = GoalAppLinkRules

    private fun habit(link: String? = null) = HabitItem(
        "h1", "Gym", 3, HabitTiming.ANYTIME, 60, null, 0, 3, false, HabitPace.ON_TRACK, 0, "week",
        List(7) { false }, "0 of 3 this week", null, false, booked = true, appLink = link,
    )

    @Test fun theRowSaysTheLinkAndWhatTodaysCardDoesOrWhatItsFor() {
        assertEquals("Workout app", R.row(habit()).label)
        assertEquals(R.EMPTY, R.row(habit()).line)
        assertEquals("hevy.com · Today's card opens Hevy", R.row(habit("https://hevy.com")).line)
        assertEquals("strava.com/dashboard · Today's card opens Strava", R.row(habit("https://www.strava.com/dashboard/")).line)
        // A synced link that isn't a web address reads as none, as Today's card does.
        assertEquals(R.EMPTY, R.row(habit("javascript:alert(1)")).line)
    }

    @Test fun aLongLinkIsCutOnTheRow() {
        val long = "https://connect.garmin.com/modern/activities/with/a/very/long/path/indeed"
        val line = R.row(habit(long)).line
        assertTrue(line.startsWith("connect.garmin.com/modern/activities/wi…"), line)
        assertTrue(line.endsWith("· Today's card opens Garmin"), line)
        assertEquals(R.MAX_SHOWN, R.shown(long).length)
    }

    @Test fun theSheetStartsWithTheLinkKeptAndSaysWhatItDoes() {
        assertEquals("", R.initial(habit()))
        assertEquals("hevy.com", R.initial(habit("https://hevy.com")))
        assertEquals("Save", R.sheet(habit()).addLabel)
        assertTrue(R.sheet(habit()).note.startsWith("Today's card for Gym opens this app"))
        assertTrue(R.sheet(habit("https://hevy.com")).note.contains("blank"))
        assertFalse(R.sheet(habit()).note.contains("blank"))
    }

    @Test fun saveLightsOnlyWhenTheLinkWouldChange() {
        assertFalse(R.canSave("  ", habit()))
        assertTrue(R.canSave("hevy.com", habit()))
        val kept = habit("https://hevy.com")
        assertFalse(R.canSave("hevy.com", kept))
        assertFalse(R.canSave(" https://hevy.com/ ", kept))
        assertTrue(R.canSave("strava.com", kept))
        assertTrue(R.canSave("", kept)) // removes it
        assertTrue(R.canSave("not a link", kept)) // tried, then said under the field
    }

    @Test fun aLineThatIsntAWebAddressIsRefused() {
        assertTrue(R.linkOk(""))
        assertTrue(R.linkOk("hevy.com"))
        assertFalse(R.linkOk("not a link"))
        assertFalse(R.linkOk("ftp://hevy.com"))
        assertEquals("That isn't a web address · try hevy.com", R.NOT_A_LINK)
    }
}
