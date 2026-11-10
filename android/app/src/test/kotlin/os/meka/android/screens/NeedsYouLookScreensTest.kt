package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.NEEDS_YOU_ROW_TAG
import os.meka.android.today.NeedsYouChips
import os.meka.android.today.NeedsYouFyiRowView
import os.meka.android.today.NeedsYouHead
import os.meka.android.today.NeedsYouHeader
import os.meka.android.today.NeedsYouMessageRowView
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.NeedsYouFilter
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.People
import os.meka.core.domain.TriageCard
import os.meka.core.domain.TriageChatRules
import os.meka.core.domain.TriageLane
import kotlin.test.fail

/**
 * The approved Needs you look, slice 1 on the Fold (Meka 2026-11-09 22:33, `docs/design/needs-you/needs-you.dc.html`):
 * the header's counts, the chips, MESSAGES as one row per chat (the first open in place) and FYI as thin rows, from
 * invented chats (rule 13), on the cover and inner screens, Dark and Light. Saved under `needs-you/`. Each row's name,
 * gist and badge stay on one line; a tap on a row opens it and a second closes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouLookScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun card(id: String, who: String, at: Long, gist: String, lane: TriageLane = TriageLane.FYI, group: String? = null): TriageCard {
        val time = "19:" + (at % 60).toString().padStart(2, '0')
        val from = if (group != null) "$who in $group · $time" else "$who · $time"
        return TriageCard(id, lane, People.key(who), from, gist, null, CaptureApp.WHATSAPP, group != null, at, "spoken", group)
    }

    private val chats = TriageChatRules.chats(listOf(
        card("a1", "Alex", 7, "All is well, homework and activities done"),
        card("a2", "Alex", 8, "Asks if you can work from home on the show day", TriageLane.NEEDS_REPLY),
        card("g1", "Jo", 20, "Asks you to post the time in the group", TriageLane.NEEDS_REPLY, group = "Game testing group"),
        card("g2", "Kim", 21, "Says she isn't in the group and asks you to fix it", TriageLane.NEEDS_REPLY, group = "Game testing group"),
        card("h1", "Sam", 23, "Did you see my missed call and voice note?", TriageLane.NEEDS_REPLY),
        card("n1", "Robin", 31, "Will call you back in about five minutes"),
        card("n2", "Robin", 32, "Jokes the boys have eaten his money"),
        card("f1", "Casey", 40, "Thanked you, regards to the footballers"),
    ))

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun draw(theme: ThemeChoice) {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), theme)
        compose.mainClock.autoAdvance = false
        val messages = NeedsYouLayoutRules.messages(chats)
        val fyi = NeedsYouLayoutRules.fyi(chats)
        compose.setContent {
            MekaTheme {
                var open by remember { mutableStateOf<String?>(messages.first().key) }
                var filter by remember { mutableStateOf(NeedsYouFilter.ALL) }
                Column(
                    Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")
                        .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NeedsYouHeader(NeedsYouLayoutRules.subtitle(2, NeedsYouLayoutRules.replyCount(chats), fyi.size), fyi.isNotEmpty(), {})
                    NeedsYouChips(NeedsYouLayoutRules.chips(2, NeedsYouLayoutRules.replyCount(chats), fyi.size, 12), filter, { filter = it })
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.MESSAGES_HEAD, messages.size))
                    messages.forEach { c ->
                        NeedsYouMessageRowView(NeedsYouLayoutRules.row(c), open == c.key, { open = if (open == c.key) null else c.key }) {
                            Text(c.cards.joinToString("\n") { it.gist }, style = MekaType.caption, color = Meka.colors.textSecondary,
                                modifier = Modifier.padding(MekaSpace.xs).testTag("open-body"))
                        }
                    }
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.FYI_HEAD, fyi.size))
                    fyi.forEach { c -> NeedsYouFyiRowView(NeedsYouLayoutRules.fyiRow(c), {}) }
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        val messages = NeedsYouLayoutRules.messages(chats)
        if (!onScreen("2 to decide · 3 to reply · 2 FYI")) fail("$name: the header's counts aren't shown")
        if (!onScreen("Clear FYI")) fail("$name: Clear FYI is missing")
        listOf("All", "Decide 2", "Reply 3", "FYI 2", "Mail 12").forEach { if (!onScreen(it)) fail("$name: the $it chip is missing") }
        messages.forEach { c -> if (!onScreen(c.title)) fail("$name: ${c.title}'s row is missing") }
        if (!onScreen("2 to reply") || !onScreen("1 to reply")) fail("$name: the badges aren't read")
        if (!onScreen("Casey") || !onScreen("Robin")) fail("$name: the FYI rows are missing")
        val rows = messages.map { NeedsYouLayoutRules.row(it) }
        ScreenShots.assertEachOneLine(compose, name, rows.map { it.line } + rows.map { it.badge })
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverDark() = shoot("cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverLight() = shoot("cover-light", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerDark() = shoot("inner-dark")

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aTapOpensARowAndASecondClosesIt() {
        draw(ThemeChoice.DARK)
        // The newest chat needing a reply leads (Sam), open; its message shows inside it.
        if (!onScreen("Did you see my missed call")) fail("the first row isn't open")
        compose.onAllNodesWithTag(NEEDS_YOU_ROW_TAG)[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        if (compose.onAllNodesWithTag("open-body").fetchSemanticsNodes().isNotEmpty()) fail("a second tap didn't close the row")
        compose.onAllNodesWithTag(NEEDS_YOU_ROW_TAG)[1].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        if (compose.onAllNodesWithTag("open-body").fetchSemanticsNodes().size != 1) fail("a tap didn't open the group's row")
    }
}
