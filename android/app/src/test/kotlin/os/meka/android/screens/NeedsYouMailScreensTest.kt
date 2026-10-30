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
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import os.meka.android.today.NEEDS_YOU_LIST_TAG
import os.meka.android.today.NeedsYouRoute
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailRenewalRules
import os.meka.core.domain.MailRowAction
import os.meka.core.domain.MailRowActionRules
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
 * The quality gate, works by hand for Needs you's mail cards: "From your mail" (two emails, one to the business
 * account) and "Renewals from your mail" (two renewals the server read) on the Fold's cover screen, Dark and Light.
 * Every pill and button on the cards answers a 48 dp touch (the pills still drawn slim, inside the touch); Done takes
 * its email off the card; ⋯ More unfolds the rest and Snooze takes the other email off till tomorrow; Add puts a
 * renewal on Renewals and Not needed clears the other. The emails and renewals come from a stand-in server held in the
 * test, written as the server writes them. Invented senders and companies only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouMailScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L

    private companion object { const val SETTLE_MS = 3_000L }

    /** Navilife's server, as far as Needs you asks it: sync only. */
    private class Server(val service: SyncService) : SyncTransport {
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
    }

    private val serverOps = InMemoryServerOpStore()
    private val clock = HlcClock("server", { now })
    private var k = 0

    private fun serverWrite(type: String, id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srv${k++}", "hh", type, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, from: String, subject: String, preview: String, agoMs: Long, account: String): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(EntityTypes.MAIL_ITEM, id, MailNeedsRules.serverFields(account, from, null, subject, preview, now - agoMs, 1))
        return id
    }

    private val received = CivilDate.toEpochDay(2026, 10, 20)

    private fun renewal(message: String, name: String, subject: String, preview: String): String {
        val r = MailRenewalRules.read(name, null, subject, preview, received) ?: fail("no renewal read from \"$subject\"")
        val id = MailRenewalRules.findId("acc1", message, r)
        serverWrite(EntityTypes.MAIL_FIND, id, MailRenewalRules.serverFields(r, "Personal", subject, received, name))
        return id
    }

    private lateinit var football: String
    private lateinit var invoice: String
    private lateinit var streamly: String
    private lateinit var safeDrive: String

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        football = email("m1", "Sam Taylor", "Football on Saturday", "Can you drive the boys to the match?", hour, "Personal")
        invoice = email("m2", "Alex Reed", "Invoice question", "Could you check the March invoice for me?", 2 * hour, "Business")
        streamly = renewal("m3", "Streamly", "Your membership renews soon", "It renews on 3 Nov for £10.99 a month")
        safeDrive = renewal("m4", "Safe Drive", "Your car insurance is due for renewal", "Renewal date: 28/11/2026")
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = Server(SyncService(serverOps)),
            secureRandom = Random(41), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (!core.syncNow()) fail("$name: the stand-in server didn't sync")
            core.tick()
        }
        if (core.mailNeeds.value?.rows?.size != 2) fail("$name: From your mail has ${core.mailNeeds.value?.rows?.size} emails, not 2")
        if (core.mailFinds.value?.rows?.size != 2) fail("$name: Renewals from your mail has ${core.mailFinds.value?.rows?.size} rows, not 2")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { NeedsYouRoute(core) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
        ScreenShots.assertSensible(compose, name)
        reach(name, hasContentDescription(spokenDone(core, football)), "Done on \"Football on Saturday\"")
        return core
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

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /**
     * Scrolls Needs you's own list ([NEEDS_YOU_LIST_TAG]) until a node matching [m] is composed, then returns the
     * matches (failing with [what] if none). Never performScrollToNode (with the clock paused it asks a lazy list's
     * animated scroll for ever): each step asks for 60 % of the list's height and runs the clock; at most 20 steps
     * down, then 40 up, so it always ends.
     */
    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        if (!shows(m)) {
            val list = compose.onNodeWithTag(NEEDS_YOU_LIST_TAG)
            val step = list.fetchSemanticsNode().size.height * 0.6f
            for (dy in List(20) { step } + List(40) { -step }) {
                if (shows(m)) break
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                compose.mainClock.advanceTimeBy(1_000)
            }
            turns(500)
        }
        val n = compose.onAllNodes(m)
        if (n.fetchSemanticsNodes().isEmpty()) {
            fail("$name: no $what; on screen:\n" + ScreenShots.texts(compose).joinToString("\n") { "- $it" })
        }
        return n
    }

    /** Taps the first node [matcher] finds that answers a tap, scrolling the list to it first. */
    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        reach(name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Every button, checkbox and tab drawn answers a 48 dp touch. */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun row(core: MekaCore, id: String) = core.mailNeeds.value?.rows?.firstOrNull { it.id == id }
    private fun spokenDone(core: MekaCore, id: String) =
        MailRowActionRules.spoken(MailRowAction.DONE, row(core, id) ?: fail("no email $id on From your mail"))
    private fun onCard(core: MekaCore, id: String) = row(core, id) != null
    private fun find(core: MekaCore, id: String) = core.mailFinds.value?.rows?.firstOrNull { it.id == id }

    /**
     * Works by hand, Needs you's mail cards: every pill a 48 dp touch; Done on the first email takes it off the card;
     * ⋯ More on the other unfolds Snooze (a 48 dp touch too), and Snooze takes it off till tomorrow; Add puts Streamly
     * on Renewals and Not needed clears Safe Drive.
     */
    private fun mailByHand(core: MekaCore) {
        val name = "needs-you-mail-by-hand"
        assertTouchTargets(name)

        tap(name, MailNeedsRules.DONE, hasContentDescription(spokenDone(core, football)))
        until { !onCard(core, football) }
        if (onCard(core, football)) fail("$name: Done left \"Football on Saturday\" on From your mail")

        val other = row(core, invoice) ?: fail("$name: \"Invoice question\" left From your mail with Done on the other email")
        tap(name, MailRowActionRules.MORE, hasContentDescription(MailRowActionRules.moreSpoken(false, other)))
        val snooze = hasContentDescription(MailRowActionRules.spoken(MailRowAction.SNOOZE, other))
        until { shows(snooze and hasClickAction()) }
        assertTouchTargets(name) // Waiting for, Snooze, Always tidy… under More
        tap(name, MailNeedsRules.SNOOZE, snooze)
        until { !onCard(core, invoice) }
        if (onCard(core, invoice)) fail("$name: Snooze left \"Invoice question\" on From your mail")

        val add = find(core, streamly) ?: fail("$name: no Streamly on Renewals from your mail")
        tap(name, add.addLabel, hasContentDescription(add.addSpoken))
        until { find(core, streamly) == null }
        if (find(core, streamly) != null) fail("$name: ${add.addLabel} left Streamly on Renewals from your mail")
        val onRenewals = core.listsView.value.renewals.let { it.attention + it.upcoming + it.later }
        if (onRenewals.none { it.title == "Streamly" }) fail("$name: ${add.addLabel} didn't put Streamly on Renewals")

        val drop = find(core, safeDrive) ?: fail("$name: no Safe Drive on Renewals from your mail")
        assertTouchTargets(name)
        tap(name, drop.notNeededLabel, hasContentDescription(drop.notNeededSpoken))
        until { find(core, safeDrive) == null }
        if (find(core, safeDrive) != null) fail("$name: ${drop.notNeededLabel} left Safe Drive on Renewals from your mail")
        assertTouchTargets(name)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMail() { shoot("cover-dark-mail") }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMailLight() { shoot("cover-light-mail", ThemeChoice.LIGHT) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMailByHand() { mailByHand(shoot("cover-dark-mail-by-hand")) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMail() { shoot("inner-dark-mail") }
}
