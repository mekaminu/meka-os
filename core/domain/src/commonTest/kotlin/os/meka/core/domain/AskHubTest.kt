package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskHubTest {
    @Test
    fun theSwitchHasLifeDailyAndSettingsAndRemembersTheTab() {
        assertEquals(listOf("Life", "Daily", "Settings"), AskHubRules.TABS.map { it.label })
        assertEquals(AskTab.LIFE, AskTab.of(null))
        assertEquals(AskTab.LIFE, AskTab.of("nonsense"))
        assertEquals(AskTab.SETTINGS, AskTab.of("SETTINGS"))
    }

    @Test
    fun lifeShowsEightTilesAPageOnThePhoneAndTwelveOnTheOpenFold() {
        assertEquals(8, AskHubRules.perPage(2))
        assertEquals(12, AskHubRules.perPage(3))
        assertEquals(2, AskHubRules.pages(15, 2))
        assertEquals(1, AskHubRules.pages(15 - 3, 3))
        assertEquals(1, AskHubRules.pages(0, 2))
        assertEquals(0, AskHubRules.pageStart(0, 15, 2))
        assertEquals(8, AskHubRules.pageEnd(0, 15, 2))
        assertEquals(8, AskHubRules.pageStart(1, 15, 2))
        assertEquals(15, AskHubRules.pageEnd(1, 15, 2))
        assertEquals("1–8 of 15 · today’s first", AskHubRules.pageLine(0, 15, 2))
        assertEquals("9–15 of 15 · today’s first", AskHubRules.pageLine(1, 15, 2))
        assertNull(AskHubRules.pageLine(0, 8, 2), "one page needs no line or arrows")
    }

    @Test
    fun turningStaysInsideThePagesAndAShrunkListPullsThePageBack() {
        assertEquals(1, AskHubRules.turn(0, 1, 15, 2))
        assertEquals(1, AskHubRules.turn(1, 1, 15, 2))
        assertEquals(0, AskHubRules.turn(0, -1, 15, 2))
        assertFalse(AskHubRules.canTurn(0, -1, 15, 2))
        assertTrue(AskHubRules.canTurn(0, 1, 15, 2))
        assertFalse(AskHubRules.canTurn(1, 1, 15, 2))
        // Tiles set up or gone: page 2 of a list that now fits one page shows the first.
        assertEquals(0, AskHubRules.page(1, 6, 2))
        assertEquals(0, AskHubRules.pageStart(1, 6, 2))
    }

    @Test
    fun everySettingSitsInExactlyOneBoxInTheDesignsOrder() {
        assertEquals(
            listOf("You and your day", "Obi", "Phone, watch and look", "Privacy and data"),
            AskHubRules.BOXES.map { it.title },
        )
        val settings = listOf(
            HelpPlace.ABOUT_YOU, HelpPlace.WORK, HelpPlace.PROTECTED, HelpPlace.NOTIFICATIONS, HelpPlace.SPEAK_UP,
            HelpPlace.APPEARANCE, HelpPlace.VOICE, HelpPlace.TALK, HelpPlace.CALENDARS, HelpPlace.MAIL, HelpPlace.FAMILY,
            HelpPlace.WATCH, HelpPlace.RECOVERY, HelpPlace.AUTONOMY, HelpPlace.AI, HelpPlace.ACTIVITY, HelpPlace.SETUP,
            HelpPlace.HEALTH, HelpPlace.VAULT, HelpPlace.YOUR_DATA,
        )
        val all = AskHubRules.BOXES.flatMap { it.rows }
        assertEquals(all.size, all.toSet().size, "a setting in two boxes")
        assertEquals(settings.toSet(), all.toSet())
        assertEquals(SettingsBox.YOU, AskHubRules.boxOf(HelpPlace.ABOUT_YOU))
        assertEquals(SettingsBox.OBI, AskHubRules.boxOf(HelpPlace.AI))
        assertEquals(SettingsBox.DEVICE, AskHubRules.boxOf(HelpPlace.APPEARANCE))
        assertEquals(SettingsBox.DATA, AskHubRules.boxOf(HelpPlace.HEALTH))
        assertNull(AskHubRules.boxOf(HelpPlace.CAR))
    }

    @Test
    fun youAndYourDayIsOpenFirstAndOpenBoxesAreRemembered() {
        assertEquals(setOf(SettingsBox.YOU), AskHubRules.openBoxes(null))
        assertEquals(emptySet(), AskHubRules.openBoxes(""))
        val opened = AskHubRules.toggled(null, SettingsBox.DATA)
        assertEquals("YOU,DATA", opened)
        assertTrue(AskHubRules.isOpen(opened, SettingsBox.DATA))
        val folded = AskHubRules.toggled(opened, SettingsBox.YOU)
        assertEquals("DATA", folded)
        assertFalse(AskHubRules.isOpen(folded, SettingsBox.YOU))
        assertEquals("", AskHubRules.toggled(folded, SettingsBox.DATA))
    }

    @Test
    fun peeksAndHelpTargets() {
        assertEquals("About you · Work mode", AskHubRules.peek(listOf("About you", "Work mode")))
        assertEquals("Bins, Gifts, Annual leave", AskHubRules.setUpPeek(listOf("Bins", "Gifts", "Annual leave")))
        assertEquals(SettingsBox.YOU, AskHubRules.settingsTarget(HelpPlace.SETTINGS))
        assertEquals(SettingsBox.OBI, AskHubRules.settingsTarget(HelpPlace.AI))
        assertEquals(SettingsBox.DEVICE, AskHubRules.settingsTarget(HelpPlace.APPEARANCE))
        assertNull(AskHubRules.settingsTarget(HelpPlace.WORK))
    }
}
