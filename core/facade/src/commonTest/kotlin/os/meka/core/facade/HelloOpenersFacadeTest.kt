package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.HelloOpeners
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The orb says hello like a person (Fix first, Meka 2026-10-20 18:13) through the facade. */
class HelloOpenersFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(3), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun everyTapSaysHisNameAndTheGreetingChanges() = runTest(timeout = SLOW_RUNNER) {
        val c = core()
        c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
        // The day's first hello: Obi introduces himself.
        val first = c.hello()
        assertTrue("Alex" in first.opening, first.opening)
        assertTrue("Obi" in first.opening, first.opening)
        assertTrue(first.closing.endsWith("?"), first.closing)
        // A quick second tap: "again".
        now += 2 * 60_000
        val again = c.hello()
        assertTrue("Alex" in again.opening)
        assertTrue(HelloOpeners.AGAIN.any { it.openFor("Alex") == again.opening }, again.opening)
        // Later taps through the day: no repeats within ten, no Obi again.
        val seen = mutableListOf(first.spoken, again.spoken)
        repeat(8) {
            now += HelloOpeners.AGAIN_WITHIN_MS + 60_000
            val g = c.hello()
            assertTrue("Alex" in g.opening, g.opening)
            assertFalse("Obi" in g.opening, g.opening)
            assertFalse(g.spoken in seen, g.spoken)
            seen += g.spoken
        }
        assertNotEquals(seen[2], seen[3])
    }

    @Test
    fun adaIntroducesHerselfOncePerDayPerDevice() = runTest(timeout = SLOW_RUNNER) {
        val c = core()
        c.setAboutYou(AboutField.SHOWN_NAME, "Alex")
        c.hello()
        val c2 = core()
        c2.setAboutYou(AboutField.SHOWN_NAME, "Alex")
        // A second hello the same day on the same device never re-introduces.
        now += HelloOpeners.AGAIN_WITHIN_MS + 60_000
        assertFalse("Obi" in c.hello().opening)
        // A fresh device (its own store) does, once.
        assertTrue("Obi" in c2.hello().opening)
        now += HelloOpeners.AGAIN_WITHIN_MS + 60_000
        assertFalse("Obi" in c2.hello().opening)
    }

    @Test
    fun theFoldAndTheMacDontEchoEachOther() = runTest(timeout = SLOW_RUNNER) {
        val ops = InMemoryServerOpStore()
        fun dev(id: String) = MekaCore(
            householdId = "hh", deviceId = id, store = InMemoryReplicaStore(),
            transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
            secureRandom = Random(id.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val fold = dev("android")
        val mac = dev("mac")
        fold.setAboutYou(AboutField.SHOWN_NAME, "Alex")
        fold.syncNow(); mac.syncNow()
        // Obi introduces himself on each device; then the ordinary openers alternate between the two apps.
        fold.hello(); fold.syncNow(); mac.syncNow(); mac.hello(); mac.syncNow(); fold.syncNow()
        val said = mutableListOf<String>()
        repeat(8) { i ->
            now += HelloOpeners.AGAIN_WITHIN_MS + 60_000
            val c = if (i % 2 == 0) fold else mac
            val other = if (i % 2 == 0) mac else fold
            val g = c.hello()
            assertFalse(g.opening in said, "${g.opening} said already: $said")
            said += g.opening
            c.syncNow(); other.syncNow()
        }
    }
}
