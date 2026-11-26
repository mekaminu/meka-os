package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Appearance's theme, motion, done tasks and news ticker are rows opening sheets, not chips. */
class AppearanceChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun theThemeRowSaysWhatsChosenAndUnknownIsDark() {
        assertEquals(NotifyChoiceRow("Theme", "Dark", "Theme, Dark. Opens the choices"), AppearanceChoiceRules.themeRow("dark"))
        assertEquals("Auto", AppearanceChoiceRules.themeRow("system").value)
        assertEquals("Auto", AppearanceChoiceRules.themeRow("SYSTEM").value)
        assertEquals("Dark", AppearanceChoiceRules.themeRow(null).value)
        assertEquals("Dark", AppearanceChoiceRules.themeRow("purple").value)
    }

    @Test
    fun theThemeSheetLightsTheOneInUseAndReadsBack() {
        val sheet = AppearanceChoiceRules.themeSheet("light")
        assertEquals(listOf("Dark", "Light", "Auto"), sheet.choices.map { it.label })
        assertEquals(listOf("Light"), sheet.lit())
        assertTrue(sheet.choices.all { it.line.isNotBlank() })
        assertTrue(sheet.choices.single { it.lit }.spoken.endsWith(", chosen"))
        assertEquals("system", AppearanceChoiceRules.themeNamed("light", "system"))
        assertNull(AppearanceChoiceRules.themeNamed("light", "light"))
        assertNull(AppearanceChoiceRules.themeNamed(null, "dark"))
        assertNull(AppearanceChoiceRules.themeNamed("dark", "sepia"))
    }

    @Test
    fun motionWithNothingChosenReadsExpressiveAndPickingItStoresIt() {
        assertEquals("Expressive", AppearanceChoiceRules.motionRow(null).value)
        assertEquals(listOf("Expressive"), AppearanceChoiceRules.motionSheet(null).lit())
        assertEquals(listOf("Expressive", "Subtle", "Off"), AppearanceChoiceRules.motionSheet(null).choices.map { it.label })
        assertEquals(listOf("Off"), AppearanceChoiceRules.motionSheet(MotionChoice.OFF).lit())
        assertEquals(MotionChoice.EXPRESSIVE, AppearanceChoiceRules.motionNamed(null, "expressive"))
        assertEquals(MotionChoice.SUBTLE, AppearanceChoiceRules.motionNamed(MotionChoice.OFF, "subtle"))
        assertNull(AppearanceChoiceRules.motionNamed(MotionChoice.SUBTLE, "subtle"))
        assertNull(AppearanceChoiceRules.motionNamed(null, "wobbly"))
    }

    @Test
    fun doneTasksAndTheTickerReadBackTheirChoices() {
        assertEquals(NotifyChoiceRow("Done tasks on Today", "Folded", "Done tasks on Today, Folded. Opens the choices"),
            AppearanceChoiceRules.doneRow(DoneShow.FOLDED))
        val done = AppearanceChoiceRules.doneSheet(DoneShow.HIDDEN)
        assertEquals(listOf("Folded", "Hidden", "Shown"), done.choices.map { it.label })
        assertEquals(listOf("Hidden"), done.lit())
        done.choices.filter { !it.lit }.forEach { assertTrue(AppearanceChoiceRules.doneNamed(DoneShow.HIDDEN, it.name) != null, it.name) }
        assertNull(AppearanceChoiceRules.doneNamed(DoneShow.HIDDEN, "H"))

        assertEquals("Always moving", AppearanceChoiceRules.tickerRow(TickerMode.MOVING).value)
        val ticker = AppearanceChoiceRules.tickerSheet(TickerMode.CALM)
        assertEquals(listOf("Calm", "Always moving", "Off"), ticker.choices.map { it.label })
        assertEquals(listOf("Calm"), ticker.lit())
        assertEquals(TickerMode.OFF, AppearanceChoiceRules.tickerNamed(TickerMode.CALM, "off"))
        assertNull(AppearanceChoiceRules.tickerNamed(TickerMode.CALM, "calm"))
    }
}
