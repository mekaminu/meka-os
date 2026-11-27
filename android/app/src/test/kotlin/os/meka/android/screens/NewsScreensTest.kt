package os.meka.android.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import os.meka.android.goals.GOAL_ADD_SHEET_TAG
import os.meka.android.news.NEWS_FOLLOWING_ROW_TAG
import os.meka.android.news.NEWS_FOLLOW_ROW_TAG
import os.meka.android.news.NEWS_TOPICS_ROW_TAG
import os.meka.android.news.NewsPane
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import os.meka.android.news.PICTURE_TAG
import os.meka.android.news.TILE_TAG
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeadlineFields
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.NewsChoiceRules
import os.meka.core.domain.NewsRules
import os.meka.core.facade.MekaCore
import os.meka.core.facade.NewsImagesApi
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
 * (rule 13). Walk-through 2026-11-22 A14: every story's picture was a tile of initials, as no story named one; the
 * stories now carry pictures (drawn here: a pitch, a chip board, a city sky) served by the stand-in server, as real
 * feeds do, and only the headline-only story keeps its source's tile. The shots fail on a tile where a picture is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NewsScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val ops = InMemoryServerOpStore()
    private val clock = HlcClock("server", { now })
    private var n = 0

    /** The pictures the stand-in server holds, by key. */
    private val pictures = mutableMapOf<String, ByteArray>()

    /** A story's picture key: 32 hex digits, as the server names them. */
    private fun keyOf(id: String): String = id.toByteArray().joinToString("") { "%02x".format(it) }.padEnd(32, '0').take(32)

    /** An invented picture for [topic], 240 × 180 like the server's small JPEGs: a pitch, a chip board or a city sky. */
    private fun picture(topic: String, seed: Int): ByteArray {
        val w = 240; val h = 180
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (topic) {
            "barca" -> {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1F7A3A.toInt(), 0xFF0E4D22.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
                p.color = 0x22FFFFFF
                for (i in 0 until 6 step 2) c.drawRect(i * 40f, 0f, i * 40f + 40f, h.toFloat(), p)
                p.color = 0xCCFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                c.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), p)
                c.drawCircle(w / 2f, h / 2f, 34f + seed * 4, p)
            }
            "ai" -> {
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1B2440.toInt(), 0xFF3A2D6B.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
                p.color = 0x5539C6E8; p.strokeWidth = 2f
                for (x in 0..w step 24) c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), p)
                for (y in 0..h step 24) c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p)
                p.color = 0xFF2A3358.toInt(); c.drawRoundRect(70f - seed * 6, 50f, 170f + seed * 6, 130f, 10f, 10f, p)
                p.color = 0xFF7FD8F0.toInt(); c.drawRoundRect(96f, 72f, 144f, 108f, 6f, 6f, p)
            }
            else -> {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF8FB4D9.toInt(), 0xFFE7D2B0.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
                p.color = 0xFF4A5566.toInt()
                listOf(20 to 70, 60 to 100, 105 to 60, 150 to 90, 195 to 75).forEach { (x, top) ->
                    c.drawRect(x.toFloat(), (top + seed * 5).toFloat(), x + 36f, h.toFloat(), p)
                }
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun headline(id: String, title: String, topic: String, agoMin: Long, source: String, url: String? = "https://news.example/$id", summary: String? = null) {
        // A feed that sends a link sends a picture too; the headline-only one sends neither (it keeps its source's tile).
        val image = url?.let { keyOf(id).also { k -> pictures[k] = picture(topic, pictures.size % 3) } }
        listOfNotNull(
            image?.let { HeadlineFields.IMAGE to FieldValue.Text(it) },
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

    /** Navilife's server, as far as News asks it: sync (the headlines it mirrors) and the stories' small pictures. */
    private inner class Server : SyncTransport, NewsImagesApi {
        val service = SyncService(ops)
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
        override suspend fun newsImage(key: String): ByteArray? = pictures[key]
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

    private fun thumbs(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Waits (real time: pictures decode off the main thread) until every story on screen that has a picture shows it. */
    private fun untilPictures(core: MekaCore) {
        val bare = core.newsPlace.value.items.count { it.imageKey == null }
        var i = 0
        while ((thumbs(TILE_TAG) > bare || thumbs(PICTURE_TAG) == 0) && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
    }

    private fun check(core: MekaCore, name: String, lanesInView: Boolean = true) {
        if (lanesInView && core.newsPlace.value.items.any { it.imageKey != null }) untilPictures(core)
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
        // A14: a story whose feed names a picture shows it, never its source's initials in its place.
        val bareStories = place.items.count { it.imageKey == null }
        if (thumbs(TILE_TAG) > bareStories) fail("$name: ${thumbs(TILE_TAG)} initials tiles drawn, but only $bareStories stories have no picture")
        if (lanesInView && place.items.any { it.imageKey != null } && thumbs(PICTURE_TAG) == 0) fail("$name: stories with pictures, but none drawn")
        val lines = listOfNotNull(place.emptyLine, place.unreadLine, place.followsLine, place.sourcesCaption, place.sourcesLine) +
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
        if (lanesInView && place.lanes.isNotEmpty() && headers.isEmpty()) fail("$name: ${place.lanes.size} lanes, but no lane header drawn")
        headers.forEach { h -> if (h !in wanted) fail("$name: a lane's header reads \"$h\", not one of $wanted in one text") }
        if (lanesInView && place.lanes.any { it.unreadLabel != null } && headers.none { "·" in it }) fail("$name: no lane header carries its count ($headers)")
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

    /**
     * Topics opens the chips (with Spanish sources a row, walk-through week 5 A17) and Done folds them away again. The
     * row opens On · Off saying what each does; On turns the Spanish sources on and the row says so; Off turns them back.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun topicsOpenAndCloseByHand() {
        val name = "cover-dark-topics"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        untilShows(name, core.newsPlace.value.items.first().title)
        tap(name, "Topics", hasTestTag("news-topics"))
        fun rowShown(on: Boolean) = compose.onAllNodes(hasContentDescription(NewsChoiceRules.spanishRow(on).spoken) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        until { rowShown(false) }
        if (!rowShown(false)) fail("$name: no \"Spanish sources\" · \"Off\" row under the topics")
        // The old "Off · English only" line under an open Off · On chip row is gone.
        if (compose.onAllNodes(hasText(NewsRules.spanishLine(false), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            fail("$name: the old \"${NewsRules.spanishLine(false)}\" line is still on the page")
        ScreenShots.assertShows(compose, name, "Done")
        // Walk-through week 5, A17: Topics and Sources are one row each, not open walls of chips.
        val topicsRow = NewsChoiceRules.topicsRow(core.newsPlace.value.topics)
        val sourcesRow = NewsChoiceRules.sourcesRow(core.newsPlace.value.sources) ?: fail("$name: no sources to check")
        listOf(topicsRow, sourcesRow).forEach { r ->
            if (compose.onAllNodes(hasContentDescription(r.spoken) and hasClickAction()).fetchSemanticsNodes().isEmpty()) fail("$name: no \"${r.title}\" · \"${r.value}\" row")
        }
        val unchosen = core.newsPlace.value.topics.first { !it.chosen }.label
        if (compose.onAllNodes(hasText(unchosen) and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            fail("$name: the \"$unchosen\" topic is an open chip on the page")
        core.newsPlace.value.sources.forEach { src ->
            if (compose.onAllNodes(hasText(src.label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) fail("$name: \"${src.label}\" is an open chip on the page")
        }
        check(core, name, lanesInView = false)

        fun sheetOpen() = compose.onAllNodes(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()
        fun inSheet(line: String) = compose.onAllNodes(
            SemanticsMatcher("reads \"$line\"") { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.replace('\u00A0', ' ') == line }
                and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)), useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        fun choose(on: Boolean, label: String) {
            tap(name, "Spanish sources", hasContentDescription(NewsChoiceRules.spanishRow(on).spoken))
            until { sheetOpen() }
            if (!sheetOpen()) fail("$name: Spanish sources opened no sheet")
            listOf(NewsChoiceRules.SPANISH_ON_LINE, NewsChoiceRules.SPANISH_OFF_LINE).forEach { l -> if (!inSheet(l)) fail("$name: the sheet doesn't say \"$l\"") }
            compose.onAllNodes(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            until { !sheetOpen() && core.newsPlace.value.spanishSources == !on }
            if (sheetOpen()) fail("$name: choosing $label didn't close the sheet")
        }
        choose(false, "On")
        if (!core.newsPlace.value.spanishSources) fail("$name: On in Spanish sources' sheet left them off")
        until { rowShown(true) }
        if (!rowShown(true)) fail("$name: the row doesn't say On")
        choose(true, "Off")
        if (core.newsPlace.value.spanishSources) fail("$name: Off in Spanish sources' sheet left them on")

        tap(name, "Done", hasTestTag("news-topics"))
        turns()
        ScreenShots.assertShows(compose, name, "Topics")
        if (rowShown(false)) fail("$name: Done left the topics open")
    }

    /**
     * Walk-through week 5, A17: Topics opens a sheet of ticks that stays open while he picks (UK added, then taken off
     * again) and Done closes it; Sources does the same, a source ticked off leaving the row saying "1 off".
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun topicsAndSourcesTickByHand() {
        val name = "cover-dark-topics-sheet"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        untilShows(name, core.newsPlace.value.items.first().title)
        tap(name, "Topics", hasTestTag("news-topics"))
        until { compose.onAllNodes(hasTestTag(NEWS_TOPICS_ROW_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        fun sheetOpen() = compose.onAllNodes(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()
        fun tick(label: String) = compose.onAllNodes(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        fun uk() = core.newsPlace.value.topics.first { it.id == "uk" }.chosen

        tap(name, "Topics row", hasContentDescription(NewsChoiceRules.topicsRow(core.newsPlace.value.topics).spoken))
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: the Topics row opened no sheet")
        if (uk()) fail("$name: UK chosen before the tick")
        tick("UK")
        until { uk() }
        if (!uk()) fail("$name: ticking UK didn't choose it")
        if (!sheetOpen()) fail("$name: a tick closed the Topics sheet (several may be picked)")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        tick("UK")
        until { !uk() }
        if (uk()) fail("$name: a second tick left UK chosen")
        tap(name, "Done", hasText("Done") and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))
        until { !sheetOpen() }
        if (sheetOpen()) fail("$name: Done left the Topics sheet open")

        val first = core.newsPlace.value.sources.first()
        tap(name, "Sources row", hasContentDescription(NewsChoiceRules.sourcesRow(core.newsPlace.value.sources)!!.spoken))
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: the Sources row opened no sheet")
        tick(first.name)
        until { core.newsPlace.value.sources.any { it.key == first.key && !it.on } }
        if (core.newsPlace.value.sources.none { it.key == first.key && !it.on }) fail("$name: ticking ${first.name} off left it on")
        tap(name, "Done", hasText("Done") and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))
        until { !sheetOpen() }
        val row = NewsChoiceRules.sourcesRow(core.newsPlace.value.sources)!!
        if (row.value != "1 off") fail("$name: the Sources row reads \"${row.value}\" with one off")
        until { compose.onAllNodes(hasContentDescription(row.spoken) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasContentDescription(row.spoken) and hasClickAction()).fetchSemanticsNodes().isEmpty()) fail("$name: the row doesn't say 1 off")
    }

    /**
     * Walk-through week 5, A17: Follow has no open box or chip row on the page. "+ Follow a name or word" opens the Follow
     * sheet (Follow dark until a name is typed); following one puts a "Following" · "Arsenal" row there, which opens the
     * list; choosing a name stops following it and the page offers Undo.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun followAndStopByHand() {
        val name = "cover-dark-follow"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        untilShows(name, core.newsPlace.value.items.first().title)
        tap(name, "Topics", hasTestTag("news-topics"))
        until { compose.onAllNodes(hasTestTag(NEWS_FOLLOW_ROW_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            fail("$name: an open input box is on the page")
        if (compose.onAllNodes(hasTestTag(NEWS_FOLLOWING_ROW_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            fail("$name: a Following row with nothing followed")

        fun addOpen() = compose.onAllNodes(hasTestTag(GOAL_ADD_SHEET_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val box = hasContentDescription(NewsChoiceRules.followSheet.spokenHint) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        val follow = hasContentDescription("${NewsChoiceRules.followSheet.addLabel}, ${NewsChoiceRules.followSheet.title}")
        fun followDark() = compose.onAllNodes(follow, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config?.contains(SemanticsProperties.Disabled) == true
        compose.onNodeWithTag(NEWS_FOLLOW_ROW_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        until { addOpen() }
        if (!addOpen()) fail("$name: + Follow a name or word opened no sheet")
        if (!followDark()) fail("$name: Follow is lit with nothing typed")
        compose.onAllNodes(box, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Arsenal")) }
        turns(500)
        if (followDark()) fail("$name: Follow stayed dark with a name typed")
        compose.onAllNodes(follow, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !addOpen() && core.newsPlace.value.follows.any { it.name == "Arsenal" } }
        if (addOpen()) fail("$name: the Follow sheet stayed open after Follow")
        if (core.newsPlace.value.follows.none { it.name == "Arsenal" }) fail("$name: Follow didn't follow Arsenal")

        val row = NewsChoiceRules.followingRow(core.newsPlace.value.follows) ?: fail("$name: no Following row in the rules")
        until { compose.onAllNodes(hasContentDescription(row.spoken) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        tap(name, "Following", hasContentDescription(row.spoken))
        fun sheetOpen() = compose.onAllNodes(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: Following opened no sheet")
        val line = NewsChoiceRules.followingSheet(core.newsPlace.value.follows).choices.first { it.label == "Arsenal" }.line
        // The sheet keeps a lone last word with its neighbour (a no-break space), so it is read with plain spaces.
        val reads = SemanticsMatcher("reads \"$line\"") { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.replace('\u00A0', ' ') == line }
        if (compose.onAllNodes(reads and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            fail("$name: the Following sheet doesn't say \"$line\"")
        compose.onAllNodes(hasText("Arsenal") and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { !sheetOpen() && core.newsPlace.value.follows.isEmpty() }
        if (core.newsPlace.value.follows.isNotEmpty()) fail("$name: choosing Arsenal didn't stop following it")
        untilShows(name, LoneWordGlue.kept("Stopped following Arsenal"))
        ScreenShots.assertShows(compose, name, "Undo")
    }

    /** Waits (real time too: the core writes on its own thread) until [done]. */
    private fun until(done: () -> Boolean) {
        var i = 0
        while (!done() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(500)
    }

    /** Mark all read says how many it marked, with Undo; Undo brings the dots back. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun markAllReadAndUndoByHand() {
        val name = "cover-dark-marked"
        val core = setUp(ThemeChoice.DARK, Fill.MANY, readOne = true)
        untilShows(name, core.newsPlace.value.items.first().title)
        tap(name, "Mark all read", hasText("Mark all read"))
        untilShows(name, LoneWordGlue.kept("Marked 6 read"))
        untilPictures(core)
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
        // The story leads with its picture (A14), not its source's initials.
        var w = 0
        while (thumbs(PICTURE_TAG) == 0 && w++ < 80) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50); compose.mainClock.advanceTimeBy(100) }
        turns(1_000)
        if (thumbs(PICTURE_TAG) == 0) fail("$name: the story's picture never drew")
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
        // One story at a time: each Next waits for its page before the next tap (taps sent faster than the pages turn
        // could land on the page going out, and the count fell short on a busy runner, CI run 1166).
        repeat(i) { k ->
            tap(name, "Next", hasText("Next ›"))
            untilShows(name, "${k + 2} of 7")
        }
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(NewsRules.noSummary(bare.source, hasLink = false)))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("news", name))
        ScreenShots.assertSensible(compose, name)
        val shown = ScreenShots.texts(compose)
        if (shown.any { "Read full story" in it || "Read the full story" in it }) fail("$name: a story with no link points at Read full story")
    }
}
