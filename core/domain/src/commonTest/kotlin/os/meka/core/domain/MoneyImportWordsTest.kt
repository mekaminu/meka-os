package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-through week 5 A14: the import sheet speaks to him, not to a developer, and its field hint fits one line. */
class MoneyImportWordsTest {
    private val devWords = listOf("server", "its key", "API", "token", "secret")

    @Test
    fun theNoBankFeedLineSaysWhatHeCanDoNotHowTheServerWorks() {
        val line = MoneyRules.CONNECT_LATER
        devWords.forEach { assertFalse(line.contains(it, ignoreCase = true), "developer word \"$it\" in: $line") }
        assertTrue(line.contains("Lunch Flow") && line.contains("statement file"), line)
        assertFalse(line.trimEnd().endsWith("·"), line)
    }

    @Test
    fun theAccountHintIsShortEnoughForOneLineOnTheCover() {
        // The cover's sheet field holds about 30 body characters before it wraps.
        assertTrue(MoneyRules.ACCOUNT_HINT.length <= 30, "hint too long (${MoneyRules.ACCOUNT_HINT.length}): ${MoneyRules.ACCOUNT_HINT}")
        assertFalse(MoneyRules.ACCOUNT_HINT.contains("("), MoneyRules.ACCOUNT_HINT)
    }
}
