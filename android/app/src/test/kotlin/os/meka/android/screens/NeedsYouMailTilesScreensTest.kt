package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.NEEDS_YOU_TILE_TAG
import os.meka.android.today.NeedsYouHead
import os.meka.android.today.NeedsYouMailCard
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.MailTidyBatch
import os.meka.core.domain.MailUnopenedCard
import os.meka.core.domain.MailUnopenedRow
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.NeedsYouMailRules
import kotlin.test.fail

/**
 * The approved Needs you look, slice 2 on the Fold (`docs/design/needs-you/needs-you.dc.html`): MAIL NAVILIFE TIDIED
 * TODAY as four number tiles, the one unsubscribe question and See all ›, from invented tidies (rule 13), on the cover
 * and inner screens, Dark and Light. Saved under `needs-you/`. Each tile's number and label stay on one line, and the
 * question's buttons answer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouMailTilesScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val cal = LocalCalendar.UTC
    private val now = cal.toEpochMs(20_000L, 12 * 60)

    private fun batch(id: String, minute: Int, kinds: Map<MailTidy, Int>): MailTidyBatch {
        val (summary, detail) = MailAutopilotRules.lines(kinds, "Personal")
        return MailTidyBatch(id, cal.toEpochMs(20_000L, minute), "Personal", summary, detail, kinds.values.sum(), null, null, null, kinds)
    }

    private val tidies = listOf(
        batch("a", 8 * 60, mapOf(MailTidy.NEWSLETTER to 20, MailTidy.ARCHIVE to 40, MailTidy.RECEIPT to 1)),
        batch("b", 9 * 60, mapOf(MailTidy.NEWSLETTER to 13, MailTidy.ARCHIVE to 31)),
        batch("c", 10 * 60, mapOf(MailTidy.JUNK_TRASH to 30, MailTidy.SCAM_JUNK to 3)),
    )

    private val offer = MailUnopenedCard(
        title = "Not opened in 30 days", headline = "Unsubscribe from 3?", unsubscribeLabel = "Unsubscribe from 3", note = "",
        rows = listOf(MailUnopenedRow("s1", "Oakfield Kids Club", "Personal · 5 emails, none opened · last Mon 12 Oct")),
        moreRows = emptyList(), showAllLabel = null, moreLine = null, ids = listOf("s1", "s2", "s3"), spoken = "",
    )

    private var unsubscribed = 0
    private var seeAll = 0

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun draw(theme: ThemeChoice) {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), theme)
        compose.mainClock.autoAdvance = false
        val summary = NeedsYouMailRules.summary(tidies, offer, now, cal) ?: fail("no summary")
        compose.setContent {
            MekaTheme {
                Column(
                    Modifier.fillMaxWidth().background(Meka.colors.background).testTag("screen")
                        .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NeedsYouHead(NeedsYouLayoutRules.MAIL_HEAD)
                    NeedsYouMailCard(summary, { unsubscribed++ }, {}, { seeAll++ })
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        listOf("138", "33", "71", "tidied", "newsletters", "promos", "junk & scams").forEach { if (!onScreen(it)) fail("$name: $it is missing") }
        if (compose.onAllNodesWithTag(NEEDS_YOU_TILE_TAG).fetchSemanticsNodes().size != 4) fail("$name: not four tiles")
        if (!onScreen("Unsubscribe from Oakfield Kids Club?")) fail("$name: the one question is missing")
        if (!onScreen(NeedsYouMailRules.FOOT) || !onScreen("See all")) fail("$name: the foot is missing")
        ScreenShots.assertEachOneLine(compose, name, listOf("138", "71", "tidied", "promos", NeedsYouMailRules.UNSUBSCRIBE, NeedsYouMailRules.SEE_ALL))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun mailCoverDark() = shoot("mail-cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun mailCoverLight() = shoot("mail-cover-light", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun mailInnerDark() = shoot("mail-inner-dark")

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun unsubscribeAndSeeAllAnswer() {
        draw(ThemeChoice.DARK)
        compose.onNodeWithText(NeedsYouMailRules.UNSUBSCRIBE).performClick()
        compose.onNodeWithText(NeedsYouMailRules.SEE_ALL).performClick()
        turns(300)
        if (unsubscribed != 1) fail("Unsubscribe didn't answer")
        if (seeAll != 1) fail("See all didn't open the list")
    }
}
