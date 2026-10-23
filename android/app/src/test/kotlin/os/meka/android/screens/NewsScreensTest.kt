package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.news.NewsPane
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeadlineFields
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.NewsRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 43 (build plan: "the rest of Ask's places"): Ask → More → News on the Fold's cover and inner
 * screens with no headlines, one story and a full set (Barça, AI and Top stories, one read, World quiet), Dark (and the
 * full set in Light), plus Topics open and a story's detail. Saved under `news/` and read for nonsense, cut lines,
 * broken words, split dates, times and numbers, a word left alone and a line starting with a dot. By hand: Topics opens
 * the chips and Done closes them; Mark all read says "Marked 6 read" with Undo, and Undo brings the dots back; a story
 * opens its detail ("1 of 7"), Next moves to the second; a story with no summary or link never points at a Read full
 * story button that isn't there. Headlines reach the core from a stand-in server held in the test. Invented data only
 * (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NewsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val ops = InMemoryServerOpStore()
    private val clock = HlcClock("server", { now })
    private var n = 0

    private fun headline(id: String, title: String, topic: String, agoMin: Long, source: String, url: String? = "https://news.example/$id", summary: String? = null) {
        listOfNotNull(
            HeadlineFields.TITLE to FieldValue.Text(title),
            url?.let { HeadlineFields.URL to FieldValue.Text(it) },
            HeadlineFields.SOURCE to FieldValue.Text(source),
            HeadlineFields.TOPIC to FieldValue.Text(topic),
            HeadlineFields.PUBLISHED_AT to FieldValue.Int64(now - agoMin * 60_000L),
            HeadlineFields.REMOVED to FieldValue.Bool(false),
            summary?.let { HeadlineFields.SUMMARY to FieldValue.Text(it) },
        ).forEach { (field, value) ->
            ops.append(Op("srv${n++}", "hh", EntityTypes.HEADLINE, id, field, value, clock.now(), emptyList(), "server"))
        }
    }

    private fun fill(fill: Fill) {
        if (fill == Fill.EMPTY) return
        headline("ai-0", "A new open model tops the coding charts", "ai", 25, "The Verge",
            summary = "The model leads three coding benchmarks and is free to download.")
        if (fill == Fill.ONE) return
        headline("ai-1", "Chip makers race to build smaller AI servers", "ai", 140, "TechCrunch")
        headline("ai-2", "What the new rules mean for AI at work", "ai", 300, "MIT Technology Review")
        headline("barca-0", "Barça name the squad for Saturday's trip", "barca", 50, "BBC Sport",
            summary = "Two young midfielders are in after impressing in training.")
        headline("barca-1", "The young winger signs a new deal to 2031", "barca", 400, "Football España")
        headline("top-0", "Rail fares to rise less than expected next year", "top", 80, "BBC News")
        // Some feeds send the headline only: no summary and no link.
        headline("top-1", "Storm warning for the coast this weekend", "top", 200, "BBC News", url = null)
    }

    /** Navilife's server, as far as News asks it: sync (the headlines it mirrors). */
    private inner class Server : SyncTransport {
        val service = SyncService(ops)
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
    }

    private fun core(connected: Boolean): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = if (connected) Server() else null,
        secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun setUp(theme: ThemeChoice, fill: Fill, readOne: Boolean = false): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        fill(fill)
        val core = core(connected = fill != Fill.EMPTY)
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) core.syncNow()
            core.tick()
            if (readOne) check(core.newsOpened("ai-2"))
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { NewsPane(core, onClose = {}) }
            }
        }
        turns()
        return core
    }

    /** Runs the main looper and the frames by turns (a screen's own read and a tap reach the recomposer through it). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (real time too: the core finishes on its own thread before the screen changes) until [text] is drawn. */
    private fun untilShows(name: String, text: String) {
        fun drawn() = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        var i = 0
        while (!drawn() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
        ScreenShots.assertShows(compose, name, text)
    }

    private fun tap(name: String, what: String, matcher: androidx.compose.ui.test.SemanticsMatcher) {
        val nodes = compose.onAllNodes(matcher and hasClickAction())
        if (nodes.fetchSemanticsNodes().isEmpty()) fail("$name: no $what to tap")
        nodes[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun check(core: MekaCore, name: String) {
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "News")
        val place = core.newsPlace.value
        val lines = listOfNotNull(place.emptyLine, place.unreadLine, place.followsLine, place.spanishLine, place.sourcesCaption, place.sourcesLine) +
            place.quiet + place.lanes.map { it.sources } + place.items.map { it.meta }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, place.items.map { it.title }, mustShow = false)
        // Every lane's story says where it's from and how old it is, whole.
        ScreenShots.assertEachOneLine(compose, name, place.items.map { LoneWordGlue.kept(it.meta) })
        // A lane's name and its "· 2 NEW" are one text, so they share a baseline (slice 44: "AI" sat higher than its count).
        val headers = compose.onAllNodes(hasTestTag("news-lane-header"), useUnmergedTree = true).fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }
        val wanted = place.lanes.map { lane -> lane.label.uppercase() + (lane.unreadLabel?.let { " · " + it.uppercase() } ?: "") }
        if (place.lanes.isNotEmpty() && headers.isEmpty()) fail("$name: ${place.lanes.size} lanes, but no lane header drawn")
        headers.forEach { h -> if (h !in wanted) fail("$name: a lane's header reads \"$h\", not one of $wanted in one text") }
        if (place.lanes.any { it.unreadLabel != null } && headers.none { "·" in it }) fail("$name: no lane header carries its count ($headers)")
        // Topics is a real button: 48 dp to tap, like the rest.
        val topics = compose.onAllNodes(hasTestTag("news-topics")).fetchSemanticsNodes().firstOrNull() ?: fail("$name: no Topics")
        val touchDp = topics.boundsInRoot.height / compose.density.density
        if (touchDp < 47.5f) fail("$name: Topics is ${touchDp.toInt()} dp tall to tap (at least 48)")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(theme, fill, readOne = fill == Fill.MANY)
        if (fill != Fill.EMPTY) untilShows(name, core.newsPlace.value.items.first().title)
        check(core, name)
        val place = core.newsPlace.value
        when (fill) {
            Fill.EMPTY -> {
                val empty = place.emptyLine ?: fail("$name: no headlines, but no line saying so")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(empty))
                if (place.unreadLine != null) fail("$name: \"${place.unreadLine}\" with no stories")
            }
            Fill.ONE -> {
                if (place.items.size != 1 || place.unreadLine != "1 new") fail("$name: one story, but ${place.items.size} and \"${place.unreadLine}\"")
                ScreenShots.assertShows(compose, name, "Mark all read")
            }
            Fill.MANY -> {
                if (place.items.size != 7) fail("$name: ${place.items.size} stories for seven")
                if (place.lanes.map { it.topicId }.take(2) != listOf("barca", "ai")) fail("$name: lanes in the order ${place.lanes.map { it.topicId }}")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept("6 new · 1 read"))
                // World is chosen by default and quiet: it says so after the lanes (below the fold on the cover screen).
                if (place.quiet.none { it.startsWith("World") }) fail("$name: World is quiet, but no line says so (${place.quiet})")
            }
        }
    }

    // ---- Shots ----

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() = shoot("cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() = shoot("cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() = shoot("inner-dark-many", Fill.MANY)

    // ---- By hand ----

    /** Topics opens the chips (with the Spanish switch and its line) and Done folds them away again. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun topicsOpenAndCloseByHand() {
        val name = "cover-dark-topics"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        untilShows(name, core.newsPlace.value.items.first().title)
        tap(name, "Topics", hasTestTag("news-topics"))
        untilShows(name, LoneWordGlue.kept(core.newsPlace.value.spanishLine))
        ScreenShots.assertShows(compose, name, "Done")
        check(core, name)
        tap(name, "Done", hasTestTag("news-topics"))
        turns()
        ScreenShots.assertShows(compose, name, "Topics")
        val left = compose.onAllNodes(hasText(LoneWordGlue.kept(core.newsPlace.value.spanishLine)), useUnmergedTree = true).fetchSemanticsNodes()
        if (left.isNotEmpty()) fail("$name: Done left the topics open")
    }

    /** Mark all read says how many it marked, with Undo; Undo brings the dots back. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun markAllReadAndUndoByHand() {
        val name = "cover-dark-marked"
        val core = setUp(ThemeChoice.DARK, Fill.MANY, readOne = true)
        untilShows(name, core.newsPlace.value.items.first().title)
        tap(name, "Mark all read", hasText("Mark all read"))
        untilShows(name, LoneWordGlue.kept("Marked 6 read"))
        if (core.newsPlace.value.unreadLine != "All read · new stories get a dot") fail("$name: after Mark all read \"${core.newsPlace.value.unreadLine}\"")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        tap(name, "Undo", hasText("Undo"))
        untilShows(name, LoneWordGlue.kept("6 new · 1 read"))
        ScreenShots.assertShows(compose, name, "Mark all read")
    }

    /** A story opens its detail with its place in the run; Next moves on; a bare story never offers a missing button. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aStoryOpensAndNextMovesOnByHand() {
        val name = "cover-dark-story"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        val first = core.newsPlace.value.items.first()
        untilShows(name, first.title)
        tap(name, "story", hasText(first.title))
        untilShows(name, "1 of 7")
        ScreenShots.assertShows(compose, name, "Read full story")
        ScreenShots.assertShows(compose, name, first.summary ?: fail("$name: the first Barça story has a summary"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        var i = 0
        while (!core.newsPlace.value.items.first { it.id == first.id }.read && i++ < 60) { Thread.sleep(50); turns(100) }
        if (!core.newsPlace.value.items.first { it.id == first.id }.read) fail("$name: opening the story didn't mark it read")
        tap(name, "Next", hasText("Next ›"))
        untilShows(name, "2 of 7")
        ScreenShots.assertShows(compose, name, core.newsPlace.value.items[1].title)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aStoryWithNoLinkSaysSoByHand() {
        val name = "cover-dark-bare-story"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        val bare = core.newsPlace.value.items.first { it.url == null }
        val i = core.newsPlace.value.items.indexOf(bare)
        val first = core.newsPlace.value.items.first()
        untilShows(name, first.title)
        tap(name, "story", hasText(first.title))
        untilShows(name, "1 of 7")
        repeat(i) { tap(name, "Next", hasText("Next ›")) }
        untilShows(name, "${i + 1} of 7")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(NewsRules.noSummary(bare.source, hasLink = false)))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        ScreenShots.assertSensible(compose, name)
        val shown = ScreenShots.texts(compose)
        if (shown.any { "Read full story" in it || "Read the full story" in it }) fail("$name: a story with no link points at Read full story")
    }
}
