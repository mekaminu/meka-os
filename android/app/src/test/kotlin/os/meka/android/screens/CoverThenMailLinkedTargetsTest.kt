package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.fold.NOW_CARD_TAG
import os.meka.android.fold.NowCard
import os.meka.android.fold.NowHandlers
import os.meka.android.today.MailNeedsRowView
import os.meka.core.domain.Lifecycle
import os.meka.core.domain.MailDraft
import os.meka.core.domain.MailLinkedRow
import os.meka.core.domain.MailNeedsRow
import os.meka.core.domain.NowKind
import os.meka.core.domain.NowView
import os.meka.core.domain.Task

/**
 * Walk-through 2026-11-15 A20: the cover's "Then: …" under the now card and a mail row's "+ 1 linked" line were the
 * words plus 4 dp, about 28 dp to hit. "Then" now takes a whole 48 dp tap that hangs into the 24 dp under the card and
 * keeps its own height in the layout, so the cover's Today doesn't move and the card keeps its own bottom edge; the
 * linked line (only on an email with linked ones) is a plain 48 dp row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class CoverThenMailLinkedTargetsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun task(id: String, title: String) = Task(
        id = id, title = title, notes = null, lifecycle = Lifecycle.ACTIVE, dueAtMs = null, scheduledAtMs = null,
        estimateMinutes = null, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0L, completedAtMs = null,
        hasConflict = false,
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    content()
                    Text("Below", Modifier.testTag("below"))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theCoversThenLineIsAWholeTargetInPlace() {
        val opened = mutableListOf<String>()
        val now = NowView(
            kind = NowKind.TASK, label = "Up next", lit = false, title = "Book the MOT", line = "Anytime today",
            event = null, task = task("t1", "Book the MOT"), join = null, mapsQuery = null, actions = emptyList(),
            thenLine = "Then: Call the garage", thenEvent = null, thenTask = task("t2", "Call the garage"), needsYouLine = null,
        )
        val handlers = NowHandlers(complete = {}, tomorrow = {}, openTask = { opened += it }, openEvent = {}, openNeedsYou = null)
        // As Today lays the cover's card: 24 dp under it before the next thing.
        show { NowCard(now, handlers, Modifier.padding(bottom = MekaSpace.l)) }

        val then = hasText("Then: Call the garage") and hasClickAction()
        ScreenShots.assertTallTarget(compose, "Then", then)
        val d = compose.density.density
        val card = compose.onNodeWithTag(NOW_CARD_TAG).fetchSemanticsNode().boundsInRoot
        val target = compose.onNode(then).fetchSemanticsNode().boundsInRoot
        val below = compose.onNodeWithTag("below").fetchSemanticsNode().boundsInRoot
        // The target starts below the card's own edge (it never takes the card's taps) and stays above the next thing.
        if (target.top < card.bottom - 0.5f) fail("Then: its target reaches ${(card.bottom - target.top) / d} dp into the card")
        if (target.bottom > below.top + 0.5f) fail("Then: its target reaches into what comes after the card")
        // The layout keeps the line's own height: 8 dp gap, the 28 dp line and the 24 dp under the card, under 64 dp.
        val kept = (below.top - card.bottom) / d
        if (kept >= 64f) fail("Then: the line and the gap under the card take $kept dp; they should keep about 60")

        // A tap 2 dp inside the target's bottom edge (under the line's own height) opens the next task.
        compose.onNode(then).performTouchInput { click(Offset(centerX, height - 2f * d)) }
        compose.waitForIdle()
        assertEquals(listOf("t2"), opened)
    }

    @Test
    fun aMailRowsLinkedLineIsAWholeTarget() {
        val row = MailNeedsRow(
            id = "m1", from = "Sam Taylor", line = "Personal · 09:12", subject = "Your order", preview = null,
            business = false, spoken = "Sam Taylor, Your order", replyLine = "To Sam Taylor",
            linked = listOf(MailLinkedRow("m2", "Sam Taylor", "Your order has shipped", "Personal · yesterday 18:40")),
            linkedLine = "+ 1 linked · same order 112-4455",
        )
        show {
            MailNeedsRowView(
                row, onDone = {}, onTask = {}, onWaiting = {}, onSnooze = {},
                onDraft = { MailDraft(null, "") }, onSend = { _, _ -> },
            )
        }
        val linked = hasText("+ 1 linked · same order 112-4455") and hasClickAction()
        ScreenShots.assertTallTarget(compose, "Linked", linked)
        compose.onNode(linked).performTouchInput { click(Offset(centerX, 2f * compose.density.density)) }
        compose.waitForIdle()
        compose.onNodeWithText("Your order has shipped").assertExists()
    }
}
