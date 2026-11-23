package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import os.meka.android.today.NEEDS_YOU_LIST_TAG
import os.meka.android.today.NEEDS_YOU_MAIL_ROW_TAG
import os.meka.android.today.NEEDS_YOU_RIGHT_TAG
import os.meka.android.today.NEEDS_YOU_SCAMS_TAG
import os.meka.android.today.NeedsYouGroupStore
import os.meka.android.today.NeedsYouRoute
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailNotScamRules
import os.meka.core.domain.MailScamListRules
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.domain.MailScamTrashRules
import os.meka.core.domain.MailSenderRuleRules
import os.meka.core.domain.NeedsYouGroup
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
 * The quality gate for "a possible scam isn't mail to answer" (Meka 2026-11-18 23:34, Needs you screenshot): one real
 * email and one possible scam from a stand-in server; on the folded Fold the scam isn't under Mail to answer but under
 * Possible scams to check in the Mail tidied today box; tapped, it opens on Junk it & block and Not a scam, never Task;
 * Not a scam sends it to Mail to answer. Dark and Light. Invented senders only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouScamScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L

    private class Server(val service: SyncService) : SyncTransport {
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
    }

    private val serverOps = InMemoryServerOpStore()
    private val clock = HlcClock("server", { now })
    private var k = 0

    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srv${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, from: String, subject: String, agoMs: Long, warning: String = ""): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields("Personal", from, "x@mail-x.example", subject, null, now - agoMs, 1, warning = warning))
        return id
    }

    private fun turns(ms: Long = 3_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        if (!shows(m)) {
            val right = compose.onAllNodesWithTag(NEEDS_YOU_RIGHT_TAG).fetchSemanticsNodes().isNotEmpty()
            val list = compose.onNodeWithTag(if (right) NEEDS_YOU_RIGHT_TAG else NEEDS_YOU_LIST_TAG)
            val step = list.fetchSemanticsNode().size.height * 0.6f
            for (dy in List(20) { step } + List(40) { -step }) {
                if (shows(m)) break
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                compose.mainClock.advanceTimeBy(1_000)
            }
            turns(500)
        }
        val n = compose.onAllNodes(m)
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no $what; on screen:\n" + ScreenShots.texts(compose).joinToString("\n") { "- $it" })
        return n
    }

    private fun onScreen(text: String) = shows(ScreenShots.plainText(text, substring = true))

    private fun shoot(name: String, theme: ThemeChoice, byHand: Boolean, trash: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val real = email("m1", "Sam Taylor", "Football on Saturday", hour)
        val scam = email("m2", "Invest Co", "Our bank account details are changing", 2 * hour,
            warning = "⚠ Possible scam · sent from mail-x.example, not invest.example")
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = Server(SyncService(serverOps)),
            secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (!core.syncNow()) fail("$name: the stand-in server didn't sync")
            core.tick()
        }
        if (core.mailNeeds.value?.rows?.map { it.id } != listOf(real)) fail("$name: Mail to answer holds ${core.mailNeeds.value?.rows?.map { it.from }}")
        if (core.mailScams.value?.rows?.map { it.id } != listOf(scam)) fail("$name: the possible scam isn't under Possible scams to check")
        NeedsYouGroupStore.save(context, NeedsYouGroup.entries.toSet())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { NeedsYouRoute(core) }
            }
        }
        turns()
        compose.waitForIdle()
        reach(name, hasTestTag(NEEDS_YOU_SCAMS_TAG), "Possible scams to check")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
        ScreenShots.assertSensible(compose, name)
        // The headline is the section label, drawn in capitals.
        if (!onScreen(MailScamListRules.headline(1).uppercase())) fail("$name: no \"${MailScamListRules.headline(1)}\" line")
        // Walk-through 2026-11-22 A9: the empty state beside the scam and the mail to answer says what still waits,
        // never "Approvals, replies and decisions land here" as if nothing were pending.
        reach(name, ScreenShots.plainText(NeedsYouStackRules.STILL_WAITING, substring = true), "the empty state's still-waiting line")
        if (onScreen(NeedsYouStackRules.EMPTY_CAPTION.substringBefore(" land"))) fail("$name: the empty state still says ${NeedsYouStackRules.EMPTY_CAPTION}")
        if (!byHand) return
        reach(name, hasTestTag("$NEEDS_YOU_MAIL_ROW_TAG-$scam") and hasClickAction(), "the possible scam's row")[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        turns()
        if (!onScreen(MailSenderRuleRules.BIN)) fail("$name: the possible scam doesn't open on ${MailSenderRuleRules.BIN}")
        // Scam email never a task, slice 3: a plain Move to Trash (no block) beside it.
        if (!onScreen(MailScamTrashRules.TRASH)) fail("$name: the possible scam doesn't offer ${MailScamTrashRules.TRASH}")
        if (trash) {
            reach(name, ScreenShots.plainText(MailScamTrashRules.TRASH) and hasClickAction(), "Move to Trash")[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            until { core.mailScams.value == null }
            if (core.mailScams.value != null) fail("$name: Move to Trash left it under Possible scams to check")
            if (core.mailNeeds.value?.rows?.map { it.id } != listOf(real)) fail("$name: Move to Trash sent it to Mail to answer")
            return
        }
        reach(name, ScreenShots.plainText(MailNotScamRules.NOT_SCAM) and hasClickAction(), "Not a scam")[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { core.mailScams.value == null }
        if (core.mailScams.value != null) fail("$name: Not a scam left it under Possible scams to check")
        if (core.mailNeeds.value?.rows?.map { it.id }?.toSet() != setOf(real, scam)) fail("$name: Not a scam didn't send it to Mail to answer")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverScams() { shoot("cover-dark-scams", ThemeChoice.DARK, byHand = false) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverScamsLight() { shoot("cover-light-scams", ThemeChoice.LIGHT, byHand = false) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverScamsByHand() { shoot("cover-dark-scams-by-hand", ThemeChoice.DARK, byHand = true) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverScamMovedToTrash() { shoot("cover-dark-scams-trash", ThemeChoice.DARK, byHand = true, trash = true) }
}
