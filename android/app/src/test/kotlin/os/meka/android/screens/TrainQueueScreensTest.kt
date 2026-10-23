package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.TrainQueuePane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.QueueRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 50 (build plan: "the rest of Ask's places' shots", the Train queue next): Ask → More → Train
 * queue with nothing saved, one article, and a full queue (two articles, one kept to read offline and one that couldn't
 * be, a podcast with its "opens in your podcast app", a long title, one done lately), each on the Fold's cover and
 * inner screens, Dark (and the full one in Light). Saved under `train-queue/` and read for nonsense, cut lines, broken
 * words, split dates, times and numbers, a word left alone or a line starting with a dot, the field's hint on one line,
 * and every button answering a 48 dp touch. By hand: a pasted link is saved at the top; Done moves a row to Done lately,
 * Back brings it back, Remove takes it off. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TrainQueueScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val LONG_TITLE = "What the new timetable means for every stopping service between Bedford and Brighton this winter"
        val PARA = "Engineers will work through the weekend to replace the signal cables between the two stations, so trains run later."
        val HTML = "<html><head><title>Why the trains run late</title></head><body><article>" +
            (1..4).joinToString("") { "<p>$PARA ($it)</p>" } + "</article></body></html>"
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(50), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun add(core: MekaCore, url: String, title: String?): String {
        now += 60_000
        return core.addToQueue(url, title).id ?: fail("couldn't save $url")
    }

    private suspend fun fill(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> add(core, "https://www.bbc.co.uk/news/rail-1", "Why the trains run late")
            Fill.MANY -> {
                val done = add(core, "https://www.theguardian.com/travel/sleeper-trains", "Sleeper trains are back")
                val kept = add(core, "https://www.bbc.co.uk/news/rail-1", "Why the trains run late")
                add(core, "https://open.spotify.com/episode/4abc", "The Rail Podcast: signals explained")
                val online = add(core, "https://www.ft.com/content/timetable", LONG_TITLE)
                core.queueToFetch()
                if (!core.saveQueuePage(kept, HTML)) fail("the article's words weren't kept")
                core.saveQueuePage(online, null) // the fetch failed: online only for now
                now += 60_000
                core.queueDone(done, true)
            }
        }
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /** Every button drawn answers a 48 dp touch (Done and Remove were ~32 dp). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { TrainQueuePane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("train-queue", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Train queue")
        val v = core.queueView.value
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertEachOneLine(compose, name, listOf(QueueRules.HINT))
        val rows = v.waiting + v.done
        val lines = listOf(v.summary) + rows.map { it.meta } + core.queuePages.value.values + core.queueOnline.value.values
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, rows.map { it.title }, mustShow = false)
        when (fill) {
            Fill.EMPTY -> if (v.summary != QueueRules.EMPTY_SUMMARY) fail("$name: the empty queue says \"${v.summary}\"")
            Fill.ONE -> if (v.summary != "1 article for the train") fail("$name: one article reads \"${v.summary}\"")
            Fill.MANY -> {
                if (v.waiting.size != 3 || v.done.size != 1) fail("$name: ${v.waiting.size} waiting, ${v.done.size} done")
                if (core.queuePages.value.size != 1) fail("$name: ${core.queuePages.value.size} articles kept offline")
                if (core.queueOnline.value.size != 1) fail("$name: ${core.queueOnline.value.size} online-only lines")
                ScreenShots.assertShows(compose, name, "WAITING")
                // The podcast's line, the one cut to "…" on the cover screen, reads whole.
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.waiting.first { it.kind == "podcast" }.meta))
            }
        }
        return core
    }

    /**
     * Works by hand: paste a link and press Done on the keyboard: it is saved at the top; Done on a row moves it to
     * Done lately, Back brings it back, Remove takes it off the queue.
     */
    private fun byHand(core: MekaCore) {
        val name = "train-queue-by-hand"
        now += 60_000 // a minute after the saved article, so the pasted link is the newest
        compose.onAllNodes(hasContentDescription(QueueRules.HINT_SPOKEN) and hasSetTextAction())[0]
            .performTextInput("Strike dates https://www.bbc.co.uk/news/strike-2")
        turns(500)
        compose.onAllNodes(hasContentDescription(QueueRules.HINT_SPOKEN) and hasSetTextAction())[0].performImeAction()
        until { core.queueView.value.waiting.size == 2 }
        val top = core.queueView.value.waiting.firstOrNull() ?: fail("$name: the pasted link wasn't saved")
        if (!top.url.contains("strike-2")) fail("$name: the pasted link isn't at the top (${top.url})")

        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        tap("Done: ${top.title}")
        until { core.queueView.value.done.any { it.id == top.id } }
        if (core.queueView.value.done.none { it.id == top.id }) fail("$name: Done left \"${top.title}\" waiting")
        ScreenShots.assertShows(compose, name, "DONE LATELY")

        tap("Back: ${top.title}")
        until { core.queueView.value.waiting.any { it.id == top.id } }
        if (core.queueView.value.waiting.none { it.id == top.id }) fail("$name: Back didn't bring \"${top.title}\" back")

        tap("Remove ${top.title}")
        until { (core.queueView.value.waiting + core.queueView.value.done).none { it.id == top.id } }
        if ((core.queueView.value.waiting + core.queueView.value.done).any { it.id == top.id }) fail("$name: Remove left it")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE)) }
}
