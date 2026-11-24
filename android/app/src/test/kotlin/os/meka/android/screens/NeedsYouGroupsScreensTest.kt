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
import os.meka.android.today.NEEDS_YOU_FOOT_TAG
import os.meka.android.today.NEEDS_YOU_FYI_TAG
import os.meka.android.today.NEEDS_YOU_GROUP_TAG
import os.meka.android.today.NEEDS_YOU_LISTS_LEAD_TAG
import os.meka.android.today.NEEDS_YOU_PAGER_TAG
import os.meka.android.today.NEEDS_YOU_ROW_TAG
import os.meka.android.today.NeedsYouMessageRowView
import os.meka.android.today.NeedsYouChips
import os.meka.android.today.NeedsYouFyiRowView
import os.meka.android.today.NeedsYouGroupFoot
import os.meka.android.today.NeedsYouGroupHeadView
import os.meka.android.today.NeedsYouGroupLead
import os.meka.android.today.NeedsYouGroupStore
import os.meka.android.today.NeedsYouHeader
import os.meka.android.today.NeedsYouPager
import os.meka.android.today.rememberOpenGroups
import os.meka.core.domain.AfterWorkSummary
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.DecisionCard
import os.meka.core.domain.DecisionEffect
import os.meka.core.domain.DecisionKind
import os.meka.core.domain.NeedsYouFilter
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.domain.PersonSummary
import os.meka.core.domain.NeedsYouFyiRow
import os.meka.core.domain.NeedsYouMessageRow
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

    // Walk-through 2026-11-22 A9: each FYI says its own thing (seven "Says thanks for Saturday" read as duplicates).
    private val fyi = listOf(
        "Alex" to "Says thanks for Saturday", "Sam" to "Is running ten minutes late", "Jo" to "Sent the photos from the party",
        "Kim" to "Got home safely", "Robin" to "Booked the table for Friday", "Casey" to "Loved your message",
        "Morgan" to "Shared the parking details",
    ).mapIndexed { i, (who, what) -> NeedsYouFyiRow("f$i", who, what, "$who: $what. No reply needed.") }

    // Week 4, A7: Messages opens onto its three chats (it showed open with nothing under it); one to reply each, as the header says.
    // Walk-through 2026-11-22 A9: the shot is at work until 17:30, so its chats came in during the day (they read 18:01 and
    // 19:02 under "At work until 17:30 · 37 held for later"), and each says its own thing, not one shared preview.
    private val messages = listOf(
        Triple("Alex", "AL", "Can you send the times for Saturday?"),
        Triple("Game testing group", "GT", "Who's bringing the controllers on Friday?"),
        Triple("Sam", "SA", "Are we still on for lunch tomorrow?"),
    ).mapIndexed { i, (who, letters, line) ->
        NeedsYouMessageRow("m$i", letters, who, listOf("12:10", "13:25", "14:40")[i], line, "1 to reply", true,
            "$who: $line 1 to reply.")
    }

    // Slice 2: the lists card as To decide's one row, the after-work card as its foot line.
    private val listsCard = DecisionCard(
        id = NeedsYouStackRules.LISTS_ID, kind = DecisionKind.LISTS, title = "From your lists", why = "1 renewal due",
        taskId = null, yesLabel = "Go through", laterLabel = "Later", openLabel = "Open Lists",
        yes = DecisionEffect.OPEN_LISTS, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_LISTS, urgent = false,
    )
    private val held = AfterWorkSummary(listOf(PersonSummary(
        personName = "Riley", isFamily = false, urgent = false, messages = 37, missedCalls = 0,
        apps = listOf(CaptureApp.WHATSAPP), firstAtMs = 0, latestAtMs = 0, latestText = null,
        items = listOf(CapturedItem("i-riley", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Riley", "Hi", null, 0L)),
    )))
    private val foot = NeedsYouGroupRules.afterWorkFoot(held, "At work until 17:30 · 37 held for later")!!
    private var goThrough = 0
    private var footTaps = 0

    private val peeks = mapOf(
        NeedsYouGroup.DECIDE to NeedsYouGroupRules.decidePeekOf(0, 0, 0, listOf(listsCard)),
        NeedsYouGroup.MESSAGES to NeedsYouGroupRules.names(listOf("Alex", "Game testing group", "Sam")),
        NeedsYouGroup.FYI to NeedsYouGroupRules.fyiPeek(fyi.map { it.who }),
        NeedsYouGroup.CHATS to "1 group · 3 messages · digest at 12:30",
        // Walk-through 2026-11-22 A15: "Mail to answer 3 / 3 emails need you" said the count twice; the peek is the
        // app's own, the senders.
        NeedsYouGroup.MAIL to NeedsYouGroupRules.names(listOf("Sam Taylor", "Oakfield School", "Council")),
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
                        if (o && g == NeedsYouGroup.DECIDE) {
                            NeedsYouGroupLead(NeedsYouGroupRules.listsLead(listsCard.why), listsCard.yesLabel, { goThrough++ },
                                Modifier.testTag(NEEDS_YOU_LISTS_LEAD_TAG))
                            NeedsYouGroupFoot(foot, { footTaps++ })
                        }
                        if (o && g == NeedsYouGroup.MESSAGES) {
                            messages.forEach { NeedsYouMessageRowView(it, false, {}) {} }
                        }
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

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { os.meka.core.domain.DateGlue.plain(it).contains(os.meka.core.domain.DateGlue.plain(text)) }
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
        val rows = compose.onAllNodesWithTag(NEEDS_YOU_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
        if (rows != counts.getValue(NeedsYouGroup.MESSAGES)) fail("$name: Messages is open with $rows rows under it, not 3")
        if (!onScreen(peeks.getValue(NeedsYouGroup.TIDIED))) fail("$name: a closed box doesn't peek")
        if (onScreen("emails need you")) fail("$name: Mail to answer's peek says its count again")
        if (!onScreen("1 renewal due · from your lists")) fail("$name: From your lists isn't one row inside To decide")
        if (!onScreen("At work until 17:30 · 37 held for later ›")) fail("$name: the after-work foot line is missing")
        // Walk-through 2026-11-22 A9: no chat shown under the at-work line came in after work ends, and no two say the same.
        val until = Regex("until (\\d{2}:\\d{2})").find(foot)!!.groupValues[1]
        messages.forEach { if (it.time >= until) fail("$name: ${it.name}'s ${it.time} is after work ends at $until, under \"$foot\"") }
        if (messages.map { it.line }.toSet().size != messages.size) fail("$name: two chats show the same preview")
        if (fyi.map { it.what }.toSet().size != fyi.size) fail("$name: two FYI rows say the same thing")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "groups-$name"))
        compose.onAllNodes(hasText("Go through"))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(NEEDS_YOU_FOOT_TAG).performSemanticsAction(SemanticsActions.OnClick)
        turns()
        assertEquals(1, goThrough, "$name: Go through didn't go through the lists")
        assertEquals(1, footTaps, "$name: the foot line doesn't open what's held")
        val footHeight = compose.onNodeWithTag(NEEDS_YOU_FOOT_TAG).fetchSemanticsNode().size.height
        val density = compose.onNodeWithTag(NEEDS_YOU_FOOT_TAG).fetchSemanticsNode().layoutInfo.density.density
        if (footHeight < (48 * density).toInt()) fail("$name: the foot line is under a 48 dp tap ($footHeight px)")

        tapHead(NeedsYouGroup.FYI)
        assertEquals(NeedsYouGroupRules.PAGE_SIZE, fyiRows(), "$name: FYI opened shows one page of five")
        if (!onScreen("1–5 of 7")) fail("$name: FYI's page line is missing")
        if (compose.onAllNodesWithTag(NEEDS_YOU_PAGER_TAG).fetchSemanticsNodes().isEmpty()) fail("$name: no pager under FYI")
        // Walk-through 2026-11-15 A20: the pager's ‹ › take the tap on a whole 48 × 48, not only their 32 dp circles.
        listOf("Next page", "Previous page").forEach { ScreenShots.assertTallTarget(compose, name, hasContentDescription(it), square = true) }
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
        if (onScreen("held for later")) fail("$name: the foot line shows with To decide closed")
        if (!onScreen(NeedsYouGroupRules.OPEN_ALL)) fail("$name: Close all didn't turn to Open all")
        assertEquals(emptySet(), NeedsYouGroupStore.load(context), "$name: Close all isn't kept")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverDark() = shoot("cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverLight() = shoot("cover-light", ThemeChoice.LIGHT)
}
