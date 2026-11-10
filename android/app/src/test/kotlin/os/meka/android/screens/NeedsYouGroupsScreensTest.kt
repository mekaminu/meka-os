package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.NEEDS_YOU_FYI_TAG
import os.meka.android.today.NEEDS_YOU_GROUP_TAG
import os.meka.android.today.NEEDS_YOU_PAGER_TAG
import os.meka.android.today.NeedsYouChips
import os.meka.android.today.NeedsYouFyiRowView
import os.meka.android.today.NeedsYouGroupHeadView
import os.meka.android.today.NeedsYouGroupLead
import os.meka.android.today.NeedsYouGroupStore
import os.meka.android.today.NeedsYouHeader
import os.meka.android.today.NeedsYouPager
import os.meka.android.today.rememberOpenGroups
import os.meka.core.domain.NeedsYouFilter
import os.meka.core.domain.NeedsYouFyiRow
import os.meka.core.domain.NeedsYouGroup
import os.meka.core.domain.NeedsYouGroupRules
import os.meka.core.domain.NeedsYouLayoutRules
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Needs you grouped and folding, slice 1 on the folded Fold (APPROVED 2026-11-10 21:51,
 * `docs/design/needs-you/needs-you-grouped.dc.html`): every group a box head with its count and peek, To decide and
 * Messages open, the rest closed; a tap opens a group, FYI shows five a page with ‹ ›, Close all closes them, and what he
 * left open is kept on the phone. Invented names (rule 13). Saved under `needs-you/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouGroupsScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val fyi = listOf("Alex", "Sam", "Jo", "Kim", "Robin", "Casey", "Morgan").mapIndexed { i, who ->
        NeedsYouFyiRow("f$i", who, "Says thanks for Saturday", "$who: Says thanks for Saturday. No reply needed.")
    }

    private val peeks = mapOf(
        NeedsYouGroup.DECIDE to NeedsYouGroupRules.decidePeek(0, 0, 0, 1),
        NeedsYouGroup.MESSAGES to NeedsYouGroupRules.names(listOf("Alex", "Game testing group", "Sam")),
        NeedsYouGroup.FYI to NeedsYouGroupRules.fyiPeek(fyi.map { it.who }),
        NeedsYouGroup.CHATS to "1 group · 3 messages · digest at 12:30",
        NeedsYouGroup.MAIL to "3 emails need you",
        NeedsYouGroup.RENEWALS to NeedsYouGroupRules.renewalsPeek(listOf("Streamly", "Music Plus")),
        NeedsYouGroup.TIDIED to "41 tidied · 12 newsletters · 24 promos · 5 junk & scams",
    )
    private val counts = mapOf(
        NeedsYouGroup.DECIDE to 1, NeedsYouGroup.MESSAGES to 3, NeedsYouGroup.FYI to fyi.size, NeedsYouGroup.CHATS to 1,
        NeedsYouGroup.MAIL to 3, NeedsYouGroup.RENEWALS to 2, NeedsYouGroup.TIDIED to 41,
    )

    private fun turns(ms: Long = 1_500L) {
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
                var open by rememberOpenGroups()
                var filter by remember { mutableStateOf(NeedsYouFilter.ALL) }
                var page by remember { mutableIntStateOf(0) }
                val shown = NeedsYouGroup.entries.filter { NeedsYouGroupRules.shows(filter, it) }
                val pg = NeedsYouGroupRules.page(fyi.size, page)
                Column(
                    Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen").verticalScroll(rememberScrollState())
                        .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NeedsYouHeader(NeedsYouGroupRules.subtitle(1, 3, fyi.size, 3), true,
                        onClear = { open = NeedsYouGroupRules.all(open, shown) }, actionLabel = NeedsYouGroupRules.allLabel(open, shown))
                    NeedsYouChips(NeedsYouLayoutRules.chips(1, 3, fyi.size, 3), filter, {
                        filter = it; open = NeedsYouGroupRules.pick(open, it)
                    })
                    shown.forEach { g ->
                        val o = g in open
                        NeedsYouGroupHeadView(
                            NeedsYouGroupRules.head(g, counts.getValue(g), peeks.getValue(g), o, if (g == NeedsYouGroup.FYI) pg else null),
                            { open = NeedsYouGroupRules.toggle(open, g) },
                        )
                        if (o && g == NeedsYouGroup.FYI) {
                            NeedsYouGroupLead(NeedsYouGroupRules.FYI_LEAD, NeedsYouGroupRules.CLEAR_ALL, {})
                            pg.of(fyi).forEach { NeedsYouFyiRowView(it, {}) }
                            NeedsYouPager(pg, { page = it })
                        }
                    }
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }
    private fun fyiRows() = compose.onAllNodesWithTag(NEEDS_YOU_FYI_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tapHead(g: NeedsYouGroup) {
        compose.onAllNodesWithTag(NEEDS_YOU_GROUP_TAG, useUnmergedTree = true)
            .filter(hasContentDescription(g.title, substring = true))[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        if (!onScreen("1 to decide · 3 to reply · 7 FYI · 3 mail")) fail("$name: the header's counts aren't shown")
        if (!onScreen(NeedsYouGroupRules.CLOSE_ALL)) fail("$name: Close all is missing while To decide and Messages are open")
        NeedsYouGroup.entries.forEach { if (!onScreen(it.title)) fail("$name: the ${it.title} box is missing") }
        if (fyiRows() != 0) fail("$name: FYI starts open")
        if (!onScreen(peeks.getValue(NeedsYouGroup.TIDIED))) fail("$name: a closed box doesn't peek")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "groups-$name"))

        tapHead(NeedsYouGroup.FYI)
        assertEquals(NeedsYouGroupRules.PAGE_SIZE, fyiRows(), "$name: FYI opened shows one page of five")
        if (!onScreen("1–5 of 7")) fail("$name: FYI's page line is missing")
        if (compose.onAllNodesWithTag(NEEDS_YOU_PAGER_TAG).fetchSemanticsNodes().isEmpty()) fail("$name: no pager under FYI")
        compose.onAllNodes(hasContentDescription("Next page"))[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        assertEquals(2, fyiRows(), "$name: › shows the last two")
        if (!onScreen("6–7 of 7")) fail("$name: the page line didn't move on")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "groups-$name-fyi-open"))

        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        assertEquals(setOf(NeedsYouGroup.DECIDE, NeedsYouGroup.MESSAGES, NeedsYouGroup.FYI), NeedsYouGroupStore.load(context),
            "$name: what he left open isn't kept")

        compose.onAllNodes(hasText(NeedsYouGroupRules.CLOSE_ALL))[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        if (fyiRows() != 0) fail("$name: Close all left FYI open")
        if (!onScreen(NeedsYouGroupRules.OPEN_ALL)) fail("$name: Close all didn't turn to Open all")
        assertEquals(emptySet(), NeedsYouGroupStore.load(context), "$name: Close all isn't kept")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverDark() = shoot("cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverLight() = shoot("cover-light", ThemeChoice.LIGHT)
}
