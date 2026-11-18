package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.AiSpendRow
import os.meka.android.ask.LifePageCard
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaLightColors
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.work.ObiCallsEntry
import os.meka.android.goals.ProgressRing
import os.meka.android.today.NeedsYouGroupBoxEnd
import os.meka.android.today.NeedsYouGroupHeadView
import os.meka.android.today.HeaderTilesRow
import os.meka.android.today.EveningCardView
import os.meka.core.domain.EveningCard
import os.meka.core.domain.HeaderTile
import os.meka.core.domain.HeaderTileKind
import os.meka.core.domain.NeedsYouGroup
import os.meka.core.domain.NeedsYouGroupHead
import os.meka.core.domain.PageCard
import os.meka.core.domain.PageRow
import androidx.compose.ui.test.onAllNodesWithTag
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import os.meka.android.designsystem.SwitchPill
import os.meka.android.news.TodayNewsRow
import os.meka.core.domain.NewsItem
import os.meka.core.domain.NewsTicker
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Walk-through 2026-11-15, A21 part 2 (Light contrast): the white cards on AI, Obi on calls, Setup, Health, Activity
 * and Recovery sat on Light's off-white page with no edge; part 3 adds every Life page's card (Trips, School, Reading…). In Light they now draw a 1 dp edge in the track ink (as
 * Money's, [MoneyLightEdgeTest]); in Dark none. Measured on the card's left edge, half way down. Invented words only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class LightCardEdgeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.04f && abs(a.green - b.green) < 0.04f && abs(a.blue - b.blue) < 0.04f

    private fun leftEdge(choice: ThemeChoice, card: @Composable (Modifier) -> Unit): Color {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(320.dp).background(Meka.colors.background).padding(16.dp)) {
                    card(Modifier.testTag("card"))
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("card").captureToImage().toPixelMap()
        return px[0, px.height / 2]
    }

    private val obiCalls: @Composable (Modifier) -> Unit = { m -> ObiCallsEntry("Obi rings businesses for you", m) {} }
    private val ai: @Composable (Modifier) -> Unit = { m -> AiSpendRow(null, m) }

    @Test
    fun obiOnCallsHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, obiCalls)
        if (!close(edge, MekaLightColors.track)) fail("Light: Obi on calls' edge is $edge, not the track ink")
    }

    @Test
    fun theAiRowHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, ai)
        if (!close(edge, MekaLightColors.track)) fail("Light: the AI row's edge is $edge, not the track ink")
    }

    private val lifeCard: @Composable (Modifier) -> Unit = { m ->
        LifePageCard(
            PageCard("trips", "Your trips", 1, listOf(PageRow("trip:1", "Lisbon", "3 nights", "Fri 4 Dec", false)), null),
            m, onMore = {}, onRow = {},
        )
    }

    @Test
    fun aLifePageCardHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, lifeCard)
        if (!close(edge, MekaLightColors.track)) fail("Light: a Life page card's edge is $edge, not the track ink")
    }

    @Test
    fun aLifePageCardHasNoEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, lifeCard)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: a Life page card's edge is $edge, not the surface")
    }

    @Test
    fun noEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, obiCalls)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: Obi on calls' edge is $edge, not the surface")
    }

    /** A21 part 4: Goals' empty ring drew its track in surfaceRaised (~1.1:1 on Light's page); now the bars' track ink. */
    private fun ringTop(choice: ThemeChoice): Color {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.testTag("ring").background(Meka.colors.background).padding(16.dp)) { ProgressRing(0f, 0) }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("ring").captureToImage().toPixelMap()
        // The tag is on the outer box (the tag after the padding would name only the 40 dp ring's own space): the ring is
        // 40 dp inside 16 dp of padding (72 dp across), so its 4 dp stroke's middle sits 18 dp from the top.
        return px[px.width / 2, px.height * 18 / 72]
    }

    @Test
    fun goalsEmptyRingIsSeenInLight() {
        val top = ringTop(ThemeChoice.LIGHT)
        if (!close(top, MekaLightColors.track)) fail("Light: Goals' empty ring is $top, not the track ink")
    }

    @Test
    fun goalsEmptyRingIsUnchangedInDark() {
        val top = ringTop(ThemeChoice.DARK)
        if (!close(top, MekaDarkColors.surfaceRaised)) fail("Dark: Goals' empty ring is $top, not surfaceRaised")
    }

    /** A21 part 5: Needs you's group box (head, rows, foot) is the surface on Light's page; in Light it gets the edge. */
    private fun groupHead(open: Boolean): @Composable (Modifier) -> Unit = { m ->
        NeedsYouGroupHeadView(NeedsYouGroupHead(NeedsYouGroup.MESSAGES, "Messages", "2", "Alex, Sam", open, "Messages, 2"), {}, m)
    }

    @Test
    fun aClosedNeedsYouGroupHasAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, groupHead(open = false))
        if (!close(edge, MekaLightColors.track)) fail("Light: a closed Needs you group's edge is $edge, not the track ink")
    }

    @Test
    fun anOpenNeedsYouGroupHeadKeepsItsSidesInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, groupHead(open = true))
        if (!close(edge, MekaLightColors.track)) fail("Light: an open Needs you group head's side is $edge, not the track ink")
    }

    @Test
    fun aNeedsYouGroupHasNoEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, groupHead(open = false))
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: a Needs you group's edge is $edge, not the surface")
    }

    @Test
    fun theNeedsYouBoxFootClosesWithAnEdgeInLight() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.LIGHT)
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(320.dp).background(Meka.colors.background).padding(16.dp)) {
                    NeedsYouGroupBoxEnd(Modifier.testTag("foot"))
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("foot").captureToImage().toPixelMap()
        val bottom = px[px.width / 2, px.height - 1]
        if (!close(bottom, MekaLightColors.track)) fail("Light: the box's foot is $bottom along its bottom, not the track ink")
    }

    /** A21 part 6: Today's tiles (TASKS · NEXT · MAIL, Also today, the habit chips, the day tiles) are the surface on Light's page. */
    private val headerTile: @Composable (Modifier) -> Unit = { m ->
        HeaderTilesRow(listOf(HeaderTile(HeaderTileKind.TASKS, "TASKS", "3 left", "of 5 today", "Tasks, 3 left")), {}, m)
    }

    @Test
    fun todaysTilesHaveAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, headerTile)
        if (!close(edge, MekaLightColors.track)) fail("Light: Today's TASKS tile's edge is $edge, not the track ink")
    }

    @Test
    fun todaysTilesHaveNoEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, headerTile)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: Today's TASKS tile's edge is $edge, not the surface")
    }

    /** A21 part 7: Today's raised cards (the evening card, the motion card, the mail cards) on Light's page. */
    private val eveningCard: @Composable (Modifier) -> Unit = { m ->
        EveningCardView(EveningCard("This evening", emptyList(), 0), onStep = {}, onLater = null, modifier = m)
    }

    @Test
    fun todaysRaisedCardsHaveAnEdgeInLight() {
        val edge = leftEdge(ThemeChoice.LIGHT, eveningCard)
        if (!close(edge, MekaLightColors.track)) fail("Light: Today's evening card's edge is $edge, not the track ink")
    }

    @Test
    fun todaysRaisedCardsHaveNoEdgeInDark() {
        val edge = leftEdge(ThemeChoice.DARK, eveningCard)
        if (!close(edge, MekaDarkColors.surfaceRaised)) fail("Dark: Today's evening card's edge is $edge, not the raised surface")
    }

    /** A21 part 8: Today's NEWS cards (and the bedside clock's headline strip, the same card ink) on Light's page. */
    private fun newsCardEdge(choice: ThemeChoice): Color {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(3), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 0L },
        )
        val ticker = NewsTicker(null, listOf(NewsItem("s1", "A town opens a new library", null, "BBC News", "top", "BBC News · 49 min ago", null, 0L)))
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(360.dp).background(Meka.colors.background).padding(16.dp)) {
                    TodayNewsRow(core, ticker, openStory = {}, openMatch = {})
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onAllNodesWithTag("today-news-card")[0].captureToImage().toPixelMap()
        return px[0, px.height / 2]
    }

    @Test
    fun todaysNewsCardsHaveAnEdgeInLight() {
        val edge = newsCardEdge(ThemeChoice.LIGHT)
        if (!close(edge, MekaLightColors.track)) fail("Light: Today's news card's edge is $edge, not the track ink")
    }

    @Test
    fun todaysNewsCardsHaveNoEdgeInDark() {
        val edge = newsCardEdge(ThemeChoice.DARK)
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: Today's news card's edge is $edge, not the surface")
    }

    /** A21 part 8: an Off pill's word was onAccent on the hairline fill (1.1:1 in Dark); now the secondary ink. */
    private fun offPillInk(choice: ThemeChoice): List<Float> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.background(Meka.colors.surface).padding(16.dp)) {
                    SwitchPill(on = false, fill = Meka.colors.hairline, modifier = Modifier.testTag("card"))
                }
            }
        }
        compose.waitForIdle()
        val px = compose.onNodeWithTag("card").captureToImage().toPixelMap()
        val lum = ArrayList<Float>()
        for (x in 0 until px.width) for (y in px.height / 4 until px.height * 3 / 4) {
            val c = px[x, y]
            lum += 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        }
        return lum
    }

    @Test
    fun anOffPillsWordReadsInDark() {
        val brightest = offPillInk(ThemeChoice.DARK).max()
        if (brightest < 0.45f) fail("Dark: the Off pill's word peaks at $brightest, too close to its hairline fill")
    }

    @Test
    fun anOffPillsWordReadsInLight() {
        val darkest = offPillInk(ThemeChoice.LIGHT).min()
        if (darkest > 0.55f) fail("Light: the Off pill's word bottoms at $darkest, too close to its hairline fill")
    }

    /**
     * A21 part 9: Train queue's and Watch's white cards, and Tomorrow's and Talk's pale pills, on the page. Returns the
     * pixel nearest the edge colour among the first three columns at half height (a pill's round end is antialiased).
     */
    private fun edgeOf(choice: ThemeChoice, tag: String, want: Color, content: @Composable () -> Unit): Color {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, choice)
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(360.dp).background(Meka.colors.background).padding(16.dp)) { content() }
            }
        }
        compose.waitForIdle()
        val px = compose.onAllNodesWithTag(tag)[0].captureToImage().toPixelMap()
        val y = px.height / 2
        return (0 until minOf(3, px.width)).map { px[it, y] }
            .minBy { abs(it.red - want.red) + abs(it.green - want.green) + abs(it.blue - want.blue) }
    }

    private val queueRow = os.meka.core.domain.QueueRow(
        "q1", "https://example.org/a", "Why trains run late", "example.org · Article", "article", false, "Why trains run late",
    )

    private fun queueCard(): @Composable () -> Unit = {
        os.meka.android.ask.QueueRowView(
            queueRow, Modifier, offline = null, onOpen = {}, primary = "Done", onPrimary = {}, onRemove = {},
        )
    }

    private fun watchRow(): @Composable () -> Unit = {
        os.meka.android.ask.WatchLinkRowView(
            os.meka.core.domain.LinkedWatchRow("w1", "Galaxy Watch", "Linked today"), enabled = true, modifier = Modifier, onUnlink = {},
        )
    }

    private fun tomorrowChip(): @Composable () -> Unit = { os.meka.android.today.TomorrowChip("Day after") {} }

    private fun talkPill(): @Composable () -> Unit = {
        os.meka.android.ask.TalkSection(
            os.meka.core.domain.TalkSetupSection("Side key", "Not set up yet", false, listOf("Hold the side key."), "Open default apps"),
            Modifier, onAction = {},
        )
    }

    @Test
    fun trainQueuesCardsHaveAnEdgeInLight() {
        val edge = edgeOf(ThemeChoice.LIGHT, "queue-card", MekaLightColors.track, queueCard())
        if (!close(edge, MekaLightColors.track)) fail("Light: Train queue's card's edge is $edge, not the track ink")
    }

    @Test
    fun trainQueuesCardsHaveNoEdgeInDark() {
        val edge = edgeOf(ThemeChoice.DARK, "queue-card", MekaDarkColors.surface, queueCard())
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: Train queue's card's edge is $edge, not the surface")
    }

    @Test
    fun watchsRowsHaveAnEdgeInLight() {
        val edge = edgeOf(ThemeChoice.LIGHT, "watch-row", MekaLightColors.track, watchRow())
        if (!close(edge, MekaLightColors.track)) fail("Light: Watch's row's edge is $edge, not the track ink")
    }

    @Test
    fun watchsRowsHaveNoEdgeInDark() {
        val edge = edgeOf(ThemeChoice.DARK, "watch-row", MekaDarkColors.surface, watchRow())
        if (!close(edge, MekaDarkColors.surface)) fail("Dark: Watch's row's edge is $edge, not the surface")
    }

    @Test
    fun tomorrowsAndTalksPillsHaveAnEdgeInLight() {
        val chip = edgeOf(ThemeChoice.LIGHT, "tomorrow-chip", MekaLightColors.track, tomorrowChip())
        if (!close(chip, MekaLightColors.track)) fail("Light: Tomorrow's chip's edge is $chip, not the track ink")
        val talk = edgeOf(ThemeChoice.LIGHT, "talk-action", MekaLightColors.track, talkPill())
        if (!close(talk, MekaLightColors.track)) fail("Light: Talk's pill's edge is $talk, not the track ink")
    }

    @Test
    fun tomorrowsPillsHaveNoEdgeInDark() {
        val chip = edgeOf(ThemeChoice.DARK, "tomorrow-chip", MekaDarkColors.track, tomorrowChip())
        if (close(chip, MekaDarkColors.track) && !close(MekaDarkColors.track, MekaDarkColors.surfaceRaised)) fail("Dark: Tomorrow's chip has a track edge")
    }
}
