package os.meka.android.screens

import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** HangWatch (CI runs 1461–1465): a quick test passes untouched; a hung one is interrupted and fails, with a report. */
class HangWatchTest {
    private val desc = Description.createTestDescription(HangWatchTest::class.java, "sample")

    private fun run(watch: HangWatch, body: () -> Unit) = watch.apply(object : Statement() {
        override fun evaluate() = body()
    }, desc).evaluate()

    @Test fun aQuickTestPassesAndLeavesNoInterrupt() {
        var ran = false
        run(HangWatch(limitMs = 5_000)) { ran = true }
        assertTrue(ran)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun aHungTestIsInterruptedAndFails() {
        val started = System.currentTimeMillis()
        try {
            run(HangWatch(limitMs = 300, graceMs = 60_000)) { Thread.sleep(60_000) }
            fail("a hung test passed")
        } catch (_: InterruptedException) {
        }
        assertTrue(System.currentTimeMillis() - started < 30_000, "the hang wasn't cut short")
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun theReportNamesTheTestAndEachThread() {
        val t = Thread.currentThread()
        val r = HangWatch.report("CarScreensTest.carCoverByHand", 300_000, mapOf(t to t.stackTrace))
        assertTrue(r.startsWith("HangWatch: CarScreensTest.carCoverByHand still running after 300 s"))
        assertTrue(r.contains("\"${t.name}\""))
        assertEquals(true, r.contains("theReportNamesTheTestAndEachThread"))
    }
}
