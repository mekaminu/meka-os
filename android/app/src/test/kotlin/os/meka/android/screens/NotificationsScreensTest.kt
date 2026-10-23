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
import os.meka.android.notify.NotificationsPane
import os.meka.core.domain.DateGlue
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotificationSettings
import os.meka.core.domain.NotifyRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 57 (build plan: "the rest of Ask's places' shots", Notifications): Ask → More →
 * Notifications with nothing set (no quiet hours, no digests), as it comes (quiet 22:00–07:00, both digests) and set
 * up (quiet hours moved, three kinds of notice lowered), each on the Fold's cover and inner screens, Dark (and the
 * set-up one in Light). Saved under `notifications/` and read for nonsense, cut lines, broken words, split times and
 * numbers, the pane's lines leaving a word alone or starting a line with a dot, every chip whole on one line and inside
 * the pane (they used to scroll off the cover screen's edge), and every chip, switch and − / + answering a 48 dp touch.
 * By hand: quiet hours on, Until 15 minutes later, the midday digest off, a kind of notice moved to Digest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NotificationsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(57), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> {
                core.setQuietHours(false, 22 * 60, 7 * 60)
                core.setDigest(NotificationSettings.MIDDAY, false)
                core.setDigest(NotificationSettings.EVENING, false)
            }
            Fill.ONE -> Unit
            Fill.MANY -> {
                core.setQuietHours(true, 21 * 60 + 30, 6 * 60 + 45)
                core.setNoticeTier(NoticeSource.BRIEF, NoticeTier.DIGEST)
                core.setNoticeTier(NoticeSource.WEEKLY_REVIEW, NoticeTier.SILENT)
                core.setNoticeTier(NoticeSource.BINS, NoticeTier.DIGEST)
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

    /** [text] is on screen as the pane draws it (glued words read plain). */
    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    /** Every button, chip and switch drawn answers a 48 dp touch (the chips were ~32 dp, the switch rows ~36). */
    private fun assertTouchTargets(name: String) {
        val roles = listOf(Role.Button, Role.RadioButton, Role.Switch)
        val small = roles.flatMap { role ->
            compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, role)).fetchSemanticsNodes()
        }
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.width / compose.density.density).toInt()}×${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** Every chip drawn sits inside the pane's width (they wrap; on the cover screen they scrolled off the edge). */
    private fun assertChipsInView(name: String, labels: Set<String>) {
        val pane = compose.onNodeWithTag("notifications-pane").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().forEach { n ->
                val right = n.positionInRoot.x + n.size.width
                if (n.positionInRoot.x < pane.left - 1f || right > pane.right + 1f) {
                    fail("$name: \"$label\" sits outside the pane (${n.positionInRoot.x / compose.density.density}–${right / compose.density.density} dp)")
                }
            }
        }
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        context.governor.setDevice(DeviceAlerts.ALL)
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
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { NotificationsPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("notifications", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Notifications")
        val p = core.notificationPreview.value
        val s = core.notificationSettings.value
        val lines = listOfNotNull(p.quietLine, p.digestLine, p.explainLine.ifEmpty { null }, NotifyRules.DIGEST_NOTE, NotifyRules.TIER_NOTE) +
            (if (s.quiet.enabled) listOf(NotifyRules.QUIET_NOTE) else emptyList())
        lines.forEach { if (!onScreen(it)) fail("$name: \"$it\" isn't on screen") }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        val chips = DeviceAlerts.entries.map { it.label }.toSet() + NoticeSource.CHOICES.map { it.label }
        ScreenShots.assertEachOneLine(compose, name, chips)
        assertChipsInView(name, chips)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (s.quiet.enabled || s.digestMinutes.isNotEmpty()) fail("$name: something is set ($s)")
                ScreenShots.assertShows(compose, name, NotifyRules.QUIET_OFF)
                if (!onScreen("No digests")) fail("$name: doesn't say there are no digests")
            }
            Fill.ONE -> {
                if (!onScreen("Quiet hours 22:00–07:00")) fail("$name: the quiet hours line reads \"${p.quietLine}\"")
                if (!onScreen("Next digest 18:00")) fail("$name: the digest line reads \"${p.digestLine}\"")
            }
            Fill.MANY -> {
                if (!onScreen("On · 21:30–06:45")) fail("$name: quiet hours don't read 21:30–06:45")
                if (s.tierFor(NoticeSource.WEEKLY_REVIEW) != NoticeTier.SILENT) fail("$name: the weekly review wasn't lowered ($s)")
            }
        }
        return core
    }

    /** Works by hand: quiet hours on; Until 15 minutes later; the midday digest off; Morning brief moved to Digest. */
    private fun byHand(core: MekaCore) {
        val name = "notifications-by-hand"
        fun tap(desc: String) {
            val n = compose.onAllNodes(hasContentDescription(desc) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$desc\" to tap")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        tap(NotifyRules.QUIET_OFF)
        until { core.notificationSettings.value.quiet.enabled && onScreen("On · 22:00–07:00") }
        if (!core.notificationSettings.value.quiet.enabled) fail("$name: the switch left quiet hours off")
        if (!onScreen(NotifyRules.QUIET_NOTE)) fail("$name: quiet hours on didn't unfold the times")

        tap("Until 15 minutes later")
        until { core.notificationSettings.value.quiet.endMinute == 7 * 60 + 15 && onScreen("On · 22:00–07:15") }
        if (core.notificationSettings.value.quiet.endMinute != 7 * 60 + 15) fail("$name: + left Until at ${core.notificationSettings.value.quiet.endMinute}")
        if (!onScreen("On · 22:00–07:15")) fail("$name: the switch row doesn't say the new time")

        // Digests are off in this fill: turn the midday one on, then off again.
        tap("Midday · 12:30")
        until { core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY) && onScreen("Next digest") }
        if (!core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY)) fail("$name: the midday digest didn't come on")
        tap("Midday · 12:30")
        until { !core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY) && onScreen("No digests") }
        if (core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY)) fail("$name: the midday digest didn't go off")

        // The first kind listed: its Digest chip.
        val first = NoticeSource.ADJUSTABLE.first()
        val digest = compose.onAllNodes(hasText(NoticeTier.DIGEST.label) and hasClickAction())
        if (digest.fetchSemanticsNodes().isEmpty()) fail("$name: no Digest chip")
        digest[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.notificationSettings.value.tierFor(first) == NoticeTier.DIGEST }
        if (core.notificationSettings.value.tierFor(first) != NoticeTier.DIGEST) fail("$name: Digest left ${first.label} at ${core.notificationSettings.value.tierFor(first)}")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.EMPTY)) }
}
