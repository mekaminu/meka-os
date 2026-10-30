package os.meka.android.screens

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A test that hangs fails within minutes, naming where it hung, instead of holding the android job to its 40-minute
 * limit (CI runs 1461–1465: CarScreensTest.carByHand sat silent until the job was cancelled, and other screens were
 * blamed). If the test is still running after [limitMs], every thread's stack is printed to stderr (the unit tests'
 * log shows it), the test thread is interrupted (a wait in Thread.sleep or a latch ends with an exception, so the test
 * fails) and, if it still hasn't finished [graceMs] later, the test JVM stops, so the Gradle task fails at once.
 */
class HangWatch(private val limitMs: Long = 5 * 60_000L, private val graceMs: Long = 30_000L) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val testThread = Thread.currentThread()
            val done = java.util.concurrent.CountDownLatch(1)
            val watcher = Thread({
                try {
                    if (done.await(limitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return@Thread
                    System.err.println(report(description.displayName, limitMs))
                    System.err.flush()
                    testThread.interrupt()
                    if (done.await(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return@Thread
                    System.err.println("HangWatch: ${description.displayName} still running after the interrupt; stopping the test JVM")
                    System.err.flush()
                    Runtime.getRuntime().halt(3)
                } catch (_: InterruptedException) {
                }
            }, "hang-watch")
            watcher.isDaemon = true
            watcher.start()
            try {
                base.evaluate()
            } finally {
                done.countDown()
                Thread.interrupted() // a late interrupt never leaks into the next test
            }
        }
    }

    companion object {
        /** The hang's report: the test, how long, then every live thread's stack (the test's own first). */
        fun report(test: String, limitMs: Long, stacks: Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces()): String =
            buildString {
                append("HangWatch: ").append(test).append(" still running after ").append(limitMs / 1000).append(" s\n")
                stacks.entries.sortedBy { (t, _) -> if (t.name.contains("main", ignoreCase = true) || t.name.startsWith("SDK")) 0 else 1 }
                    .forEach { (t, frames) ->
                        append("\"").append(t.name).append("\" ").append(t.state).append('\n')
                        frames.take(60).forEach { append("    at ").append(it).append('\n') }
                    }
            }
    }
}
