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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import os.meka.android.today.NeedsYouHead
import os.meka.android.today.NeedsYouMessageRowView
import os.meka.android.today.NeedsYouOpenView
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.NeedsYouOpenRules
import os.meka.core.domain.People
import os.meka.core.domain.TriageCard
import os.meka.core.domain.TriageChatRules
import os.meka.core.domain.TriageLane
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Needs you in the approved look, slice 6 (the Fold, `docs/design/needs-you/needs-you.dc.html`, MESSAGES): an open row
 * is the chat's messages as lines with Reply · Done · Later · Task; Reply shows the chat's own card under them; a swipe
 * right on a row is Done (and TalkBack offers Done). Invented chats (rule 13), cover and inner, Dark and Light; shots
 * under `needs-you/open-*`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouOpenScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun card(id: String, who: String, at: Long, gist: String, lane: TriageLane = TriageLane.NEEDS_REPLY, group: String? = null): TriageCard {
        val time = "19:" + (at % 60).toString().padStart(2, '0')
        val from = if (group != null) "$who in $group · $time" else "$who · $time"
        return TriageCard(id, lane, People.key(who), from, gist, null, CaptureApp.WHATSAPP, group != null, at, "spoken", group)
    }

    private val chats = TriageChatRules.chats(listOf(
        card("a1", "Alex", 7, "All is well; homework and activities are done", TriageLane.FYI),
        card("a2", "Alex", 8, "The Christmas show is Wed 16 Dec, maybe work from home"),
        card("g1", "Jo", 20, "Asks you to put the time in the group", group = "Game testing group"),
        card("g2", "Kim", 21, "Says she isn't in the group and asks you to add her", group = "Game testing group"),
    ))

    private val taps = mutableListOf<String>()

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun draw(theme: ThemeChoice) {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), theme)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                var open by remember { mutableStateOf<String?>(chats.first().key) }
                Column(
                    Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")
                        .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.MESSAGES_HEAD, chats.size))
                    chats.forEach { c ->
                        NeedsYouMessageRowView(
                            NeedsYouLayoutRules.row(c), open == c.key, { open = if (open == c.key) null else c.key },
                            onSwipeDone = { taps += "swiped ${c.title}" },
                        ) {
                            NeedsYouOpenView(
                                NeedsYouOpenRules.lines(c), NeedsYouOpenRules.spoken(c),
                                onDone = { taps += "done ${c.title}" }, onLater = { taps += "later ${c.title}" },
                                onTask = { taps += "task ${c.title}" },
                            ) {
                                Text("Suggested reply: Sure, I'll sort it", style = MekaType.caption, color = Meka.colors.textSecondary,
                                    modifier = Modifier.testTag("full-card"))
                            }
                        }
                    }
                    Text(NeedsYouLayoutRules.MESSAGES_HINT, style = MekaType.micro, color = Meka.colors.textTertiary)
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }
    private fun tap(label: String) {
        compose.onAllNodesWithText(label)[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        val first = chats.first()
        NeedsYouOpenRules.lines(first).forEach { if (!onScreen(it)) fail("$name: the open row's line \"$it\" is missing") }
        listOf("Reply", "Done", "Later", "Task").forEach { if (!onScreen(it)) fail("$name: $it is missing") }
        if (!onScreen("swipe right for Done")) fail("$name: the hint doesn't say swipe right for Done")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun openCoverDark() = shoot("open-cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun openCoverLight() = shoot("open-cover-light", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun openInnerDark() = shoot("open-inner-dark")

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun replyShowsTheChatsOwnCardAndTheOtherTapsAnswer() {
        draw(ThemeChoice.DARK)
        val first = chats.first().title
        if (compose.onAllNodesWithTag("full-card").fetchSemanticsNodes().isNotEmpty()) fail("the full card shows before Reply")
        tap(NeedsYouOpenRules.REPLY)
        if (compose.onAllNodesWithTag("full-card").fetchSemanticsNodes().size != 1) fail("Reply didn't show the chat's card")
        if (!onScreen(NeedsYouOpenRules.SHOW_LESS)) fail("Reply didn't turn to Hide reply")
        tap(NeedsYouOpenRules.SHOW_LESS)
        if (compose.onAllNodesWithTag("full-card").fetchSemanticsNodes().isNotEmpty()) fail("Hide reply didn't close the card")
        tap(NeedsYouOpenRules.DONE); tap(NeedsYouOpenRules.LATER); tap(NeedsYouOpenRules.TASK)
        assertEquals(listOf("done $first", "later $first", "task $first"), taps)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aSwipeRightIsDoneAndAShortOneSpringsBack() {
        draw(ThemeChoice.DARK)
        compose.mainClock.autoAdvance = true
        val second = chats[1].title
        compose.onAllNodesWithTag(NEEDS_YOU_ROW_TAG)[1].performTouchInput { swipeRight(startX = left + 10f, endX = left + 40f) }
        turns()
        if (taps.isNotEmpty()) fail("a short swipe did something: $taps")
        compose.onAllNodesWithTag(NEEDS_YOU_ROW_TAG)[1].performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        turns()
        assertEquals(listOf("swiped $second"), taps)
        // TalkBack: Done is an action on the row.
        val actions = compose.onAllNodesWithTag(NEEDS_YOU_ROW_TAG)[0].fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.single { it.label == NeedsYouOpenRules.DONE }.action()
        assertEquals("swiped ${chats.first().title}", taps.last())
    }
}
