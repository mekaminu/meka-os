package os.meka.android.screens

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The quality gate, works by hand (build plan: "other hand tests that pause the clock and use performScrollToNode get
 * the same bounded scroll"). Every screens test runs under [HangWatch], so any hang fails within minutes with its stack
 * instead of holding the android job to its 40-minute limit (CI runs 1461–1465); and no screens test asks
 * performScrollToNode while the test's clock is paused, the hang CI run 1467 caught (a lazy list's animated scroll
 * never lands, so it is asked for again for ever). Read from the test sources; nothing runs on screen.
 */
class ScreensHangGuardTest {
    private val dir = File("src/test/kotlin/os/meka/android/screens")
    private fun tests() = dir.listFiles { f -> f.name.endsWith("ScreensTest.kt") }!!.sortedBy { it.name }

    @Test fun everyScreensTestRunsUnderHangWatch() {
        val files = tests()
        assertTrue(files.size > 40, "screens tests not found under $dir")
        val missing = files.filterNot { it.readText().contains("val hangWatch = HangWatch(") }.map { it.name }
        assertTrue(missing.isEmpty(), "screens tests without HangWatch: $missing")
    }

    @Test fun noScrollToNodeWithTheClockPaused() {
        val paused = tests().flatMap { f -> pausedScrolls(f.name, f.readLines()) }
        assertTrue(paused.isEmpty(), "performScrollToNode with the test's clock paused (scroll the list in bounded steps): $paused")
    }

    @Test fun theGuardSeesAPausedScroll() {
        val src = """
            @Test fun byHand() {
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("x").performScrollToNode(hasTestTag("y"))
            }
            @Test fun running() {
                compose.mainClock.autoAdvance = true
                compose.onNodeWithTag("x").performScrollToNode(hasTestTag("y"))
            }
        """.trimIndent().lines()
        assertTrue(pausedScrolls("Sample.kt", src) == listOf("Sample.kt:3"))
    }

    companion object {
        /**
         * The lines that call performScrollToNode while the clock was last set paused in the same test or helper
         * (`autoAdvance = false` since the function began, with no `autoAdvance = true` after it).
         */
        fun pausedScrolls(name: String, lines: List<String>): List<String> {
            val starts = Regex("""^\s*(@Test|(private |internal )?fun )""")
            var paused = false
            return lines.mapIndexedNotNull { i, line ->
                val code = line.trim()
                when {
                    starts.containsMatchIn(line) -> { paused = false; null }
                    code.startsWith("*") || code.startsWith("//") || code.startsWith("import") -> null
                    "autoAdvance = false" in code -> { paused = true; null }
                    "autoAdvance = true" in code -> { paused = false; null }
                    "performScrollToNode(" in code && paused -> "$name:${i + 1}"
                    else -> null
                }
            }
        }
    }
}
