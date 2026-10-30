package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.FamilyPane
import os.meka.android.ask.RecoveryKeyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AboutField
import os.meka.core.domain.FamilyRow
import os.meka.core.domain.FamilyRules
import os.meka.core.domain.Household
import os.meka.core.domain.HouseholdNames
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.facade.FamilyApi
import os.meka.core.facade.MekaCore
import os.meka.core.facade.RecoveryApi
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.FamilyCodec
import os.meka.core.wire.RecoveryCodec
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 39 (build plan: "Family's and Recovery's shots"): Ask → More → Family (not connected, no
 * partner named, a partner with a link waiting, and a full set: the partner joined and seen today, a second link
 * waiting, one turned off) and Ask → Settings → Recovery key (not connected, not made yet, made on the Fold, and used
 * to connect a Mac), each on the Fold's cover and inner screens, Dark (and the full ones in Light). Saved under
 * `family/` and `recovery/` and read for nonsense, cut lines, broken words, split dates, times and numbers, a word left
 * alone and a line starting with a dot. By hand: Make a link for the partner shows "Link made"; Turn off turns a link
 * off; Make a recovery key shows the 24 words in six rows of four (each word on one line), "I've written them down"
 * asks for three of them, and typing them saves the key (only its verifier reaches the server). Both talk to a
 * stand-in server held in the test. Invented data only (rule 13); the key's words come from a seeded test random.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class FamilyRecoveryScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L
    private val day = 24 * hour

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    @After
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    /** Navilife's server, as far as these two screens ask it: sync, the family links and the recovery key. */
    private inner class Server : SyncTransport, FamilyApi, RecoveryApi {
        val service = SyncService(InMemoryServerOpStore())
        val invites = mutableListOf<FamilyCodec.Invite>()
        var status = RecoveryCodec.Status(RecoveryCodec.NONE)
        var verifier: String? = null
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
        override suspend fun familyInvites(): List<FamilyCodec.Invite> = invites.toList()
        override suspend fun createFamilyInvite(name: String): Pair<FamilyCodec.Made, String> {
            val id = "fam" + (invites.size + 1).toString().padStart(20, '0')
            invites.add(0, FamilyCodec.Invite(id, name.lowercase(), "waiting", now))
            return FamilyCodec.Made(id, name.lowercase(), "/family#" + "a".repeat(64)) to "https://navilife.example/family#" + "a".repeat(64)
        }
        override suspend fun revokeFamilyInvite(id: String): Boolean {
            val i = invites.indexOfFirst { it.id == id }
            if (i < 0) return false
            invites[i] = invites[i].copy(state = "revoked")
            return true
        }
        override suspend fun recoveryStatus() = status
        override suspend fun setRecoveryKey(verifier: String): RecoveryCodec.Status {
            this.verifier = verifier
            status = RecoveryCodec.Status(RecoveryCodec.READY, now, "Fold")
            return status
        }
    }

    private fun core(server: Server?): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(39), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun setUp(theme: ThemeChoice, server: Server?, partner: String? = null): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core(server)
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            partner?.let { check(!core.setAboutYou(AboutField.PARTNER_NAME, it).refused) }
            core.tick()
        }
        return core
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { content() }
            }
        }
    }

    /** Runs the main looper and the frames by turns (a screen's own read and a tap reach the recomposer through it). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 60) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        if (!done()) fail(what)
        turns(1_000)
    }

    /** Waits (real time too: the core finishes on its own thread before the screen's state changes) until [text] is drawn. */
    private fun untilShows(name: String, text: String) {
        fun drawn() = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        var n = 0
        while (!drawn() && n++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
        ScreenShots.assertShows(compose, name, text)
    }

    private fun settle(area: String, name: String) {
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
    }

    // ---- Family ----

    private fun family(name: String, fill: Fill, connected: Boolean = true, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val server = if (connected) Server() else null
        val partner = if (fill == Fill.EMPTY) null else "Sam"
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> server?.invites?.add(FamilyCodec.Invite("fam00000000000000000001", "sam", "waiting", now - 2 * hour))
            Fill.MANY -> server?.invites?.addAll(listOf(
                FamilyCodec.Invite("fam00000000000000000003", "riley", "waiting", now - day),
                FamilyCodec.Invite("fam00000000000000000002", "sam", "joined", now - 11 * day, now - 11 * day + hour, now - hour),
                FamilyCodec.Invite("fam00000000000000000001", "mary jane", "revoked", now - 20 * day, now - 20 * day + hour, now - 15 * day),
            ))
        }
        val core = setUp(theme, server, partner)
        runBlocking { core.refreshFamily() }
        show { FamilyPane(core, onClose = {}) }
        settle("family", name)
        val v = core.familyView.value ?: fail("$name: Family never read the server")
        ScreenShots.assertShows(compose, name, "Family")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertEachOneLine(compose, name, v.rows.map { it.title } + listOfNotNull(v.inviteLabel.takeIf { v.canInvite }))
        val lines = listOf(v.summary, v.shared, FamilyRules.NOTE) + v.rows.map { it.line } + listOfNotNull(v.problem)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        if (name.startsWith("cover")) turnOffUnderWords(name, v.rows)
        when {
            !connected -> if (v.problem != FamilyRules.NOT_CONNECTED) fail("$name: not connected, but the problem says \"${v.problem}\"")
            fill == Fill.EMPTY -> {
                if (v.summary != FamilyRules.NO_PARTNER || v.canInvite) fail("$name: no partner named, but $v")
            }
            fill == Fill.ONE -> {
                if (v.rows.size != 1 || v.canInvite) fail("$name: one link waiting, but $v")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept("Waiting · link made today"))
            }
            fill == Fill.MANY -> {
                if (v.rows.size != 3) fail("$name: ${v.rows.size} rows for three links")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept("Joined Sat 10 Oct · seen today"))
                ScreenShots.assertShows(compose, name, "Turned off")
                ScreenShots.assertShows(compose, name, "Mary Jane")
            }
        }
        return core
    }

    /**
     * Quality gate slice 40: on the cover screen a link's Turn off sits under its words, never squeezing them: each row's
     * line reads on one line and the button starts below it.
     */
    private fun turnOffUnderWords(name: String, rows: List<FamilyRow>) {
        ScreenShots.assertEachOneLine(compose, name, rows.map { LoneWordGlue.kept(it.line) })
        rows.filter { it.canTurnOff }.forEach { r ->
            val line = compose.onAllNodes(hasText(LoneWordGlue.kept(r.line)), useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull() ?: return@forEach // below the fold
            val off = compose.onAllNodes(hasText("Turn off"), useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.top >= line.boundsInRoot.top - 1f } ?: fail("$name: no Turn off for ${r.title}")
            if (off.boundsInRoot.top < line.boundsInRoot.bottom - 1f) fail("$name: ${r.title}'s Turn off sits beside its words")
            // Quality gate slice 41: Turn off follows its line closely (about 20 dp of air sat between them), the card ends
            // just under it, and its touch is 48 dp tall.
            val gapDp = (off.boundsInRoot.top - line.boundsInRoot.bottom) / compose.density.density
            if (gapDp > TURN_OFF_GAP_DP) fail("$name: ${gapDp.toInt()} dp between ${r.title}'s line and its Turn off (at most ${TURN_OFF_GAP_DP.toInt()})")
            val touch = compose.onAllNodes(hasText("Turn off") and hasClickAction()).fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.top <= off.boundsInRoot.top && it.boundsInRoot.bottom >= off.boundsInRoot.bottom }
                ?: fail("$name: ${r.title}'s Turn off can't be tapped")
            val touchDp = touch.boundsInRoot.height / compose.density.density
            if (touchDp < 47.5f) fail("$name: ${r.title}'s Turn off is ${touchDp.toInt()} dp tall to tap (at least 48)")
            val card = compose.onAllNodes(hasTestTag("family-row:${r.title}"), useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull()?.boundsInRoot ?: fail("$name: no card for ${r.title}")
            val underDp = (card.bottom - touch.boundsInRoot.bottom) / compose.density.density
            if (underDp > 8f) fail("$name: ${r.title}'s card has ${underDp.toInt()} dp of empty space under Turn off (at most 8)")
        }
    }

    /** The most air between a link's line and its Turn off's words: the touch room above the pill plus its padding. */
    private val TURN_OFF_GAP_DP = 18f

    /** Works by hand: with Sam named and no link yet, Make a link shows "Link made for Sam"; Turn off turns it off. */
    private fun familyByHand() {
        val server = Server()
        val core = setUp(ThemeChoice.DARK, server, "Sam")
        show { FamilyPane(core, onClose = {}) }
        until("Family by hand: the screen never read the server") { core.familyView.value != null }
        val make = compose.onAllNodes(hasText("Make a link for Sam") and hasClickAction())
        if (make.fetchSemanticsNodes().isEmpty()) fail("Family: no Make a link for Sam with Sam named")
        make[0].performSemanticsAction(SemanticsActions.OnClick)
        until("Family: Make a link made no link (${server.invites})") { server.invites.isNotEmpty() }
        untilShows("family-by-hand", "Link made for Sam")
        ScreenShots.assertShows(compose, "family-by-hand", LoneWordGlue.kept("Waiting · link made today"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("family", "inner-dark-made"))
        val off = compose.onAllNodes(hasText("Turn off") and hasClickAction())
        if (off.fetchSemanticsNodes().isEmpty()) fail("Family: no Turn off on the new link")
        off[0].performSemanticsAction(SemanticsActions.OnClick)
        until("Family: Turn off left the link on (${server.invites})") { server.invites.first().state == "revoked" }
        untilShows("family-by-hand", "Turned off")
    }

    // ---- Recovery key ----

    private fun recovery(name: String, fill: Fill, connected: Boolean = true, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val server = if (connected) Server() else null
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> server?.status = RecoveryCodec.Status(RecoveryCodec.READY, now - 11 * day, "Fold")
            Fill.MANY -> server?.status = RecoveryCodec.Status(RecoveryCodec.USED, now - 40 * day, "Fold", now - 2 * day, "Mac")
        }
        val core = setUp(theme, server)
        runBlocking { core.refreshRecoveryKey() }
        show { RecoveryKeyPane(core, onClose = {}) }
        settle("recovery", name)
        val v = core.recoveryKeyView.value ?: fail("$name: Recovery key never read the server")
        ScreenShots.assertShows(compose, name, "Recovery key")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.line))
        ScreenShots.assertEachOneLine(compose, name, listOf(v.action))
        val lines = listOfNotNull(v.line, RecoveryKeyRules.HOW, v.warning, v.problem)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        when {
            !connected -> if (v.problem != RecoveryKeyRules.NOT_CONNECTED) fail("$name: not connected, but the problem says \"${v.problem}\"")
            fill == Fill.EMPTY -> if (!v.line.startsWith("Not made yet")) fail("$name: no key, but \"${v.line}\"")
            fill == Fill.ONE -> {
                if (v.line != "Made Sat 10 Oct on the Fold · gets you back in if you lose both devices") fail("$name: a key made on the Fold reads \"${v.line}\"")
                if (v.warning == null) fail("$name: a key exists, but no warning that a new one replaces it")
            }
            fill == Fill.MANY -> if (v.line != "Used Mon 19 Oct to connect the Mac · make a new one and put the old paper away") fail("$name: a used key reads \"${v.line}\"")
        }
        return core
    }

    /** Every drawn text that matches [pattern] whole. */
    private fun texts(pattern: Regex): List<String> =
        compose.onAllNodes(SemanticsMatcher("text matches $pattern") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.let { t -> pattern.matches(t) } == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    /**
     * Works by hand: Make a recovery key shows the 24 words (six rows of four, each word whole on one line);
     * "I've written them down" asks for three; typing them and Save sends the verifier and says it's saved.
     */
    private fun recoveryByHand(area: String) {
        val server = Server()
        val core = setUp(ThemeChoice.DARK, server)
        show { RecoveryKeyPane(core, onClose = {}) }
        until("$area: the screen never read the server") { core.recoveryKeyView.value != null }
        compose.onAllNodes(hasText("Make a recovery key") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        ScreenShots.assertShows(compose, area, "Your 24 words")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("recovery", "$area-words"))
        ScreenShots.assertSensible(compose, area)
        ScreenShots.assertNoBrokenWords(compose, area)
        // Each numbered word ("1 abandon") reads on one line in its quarter of the row.
        val shown = texts(Regex("^\\d{1,2} [a-z]+$"))
        if (shown.size != 24) fail("$area: ${shown.size} numbered words on screen, not 24")
        ScreenShots.assertEachOneLine(compose, area, shown)
        compose.onAllNodes(hasText(RecoveryKeyRules.WRITE_DOWN) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        ScreenShots.assertShows(compose, area, RecoveryKeyRules.CONFIRM)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("recovery", "$area-confirm"))
        val asked = shown.associate { it.substringBefore(' ').toInt() to it.substringAfter(' ') }
        val labels = texts(Regex("^Word \\d{1,2}$"))
        if (labels.size != RecoveryKeyRules.CONFIRM_WORDS) fail("$area: asks for ${labels.size} words, not ${RecoveryKeyRules.CONFIRM_WORDS}")
        labels.forEach { label ->
            val n = label.removePrefix("Word ").toInt()
            compose.onAllNodes(hasContentDescription(label) and hasSetTextAction())[0].performTextInput(asked.getValue(n))
            repeat(5) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.mainClock.advanceTimeBy(100)
            }
        }
        compose.onAllNodes(hasText("Save") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until("$area: Save didn't send the key (${core.recoveryKeyView.value})") { server.verifier != null }
        untilShows(area, LoneWordGlue.kept(RecoveryKeyRules.SAVED))
        if (asked.values.any { server.verifier!!.contains(it) }) fail("$area: a word reached the server")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun familyCoverOff() { family("cover-dark-not-connected", Fill.ONE, connected = false) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun familyCoverEmpty() { family("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun familyCoverOne() { family("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun familyCoverMany() { family("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun familyCoverManyLight() { family("cover-light-many", Fill.MANY, theme = ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun familyInnerMany() { family("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun familyByHandTest() { familyByHand() }

    @Test @Config(qualifiers = ScreenShots.COVER) fun recoveryCoverOff() { recovery("cover-dark-not-connected", Fill.EMPTY, connected = false) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun recoveryCoverEmpty() { recovery("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun recoveryCoverOne() { recovery("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun recoveryCoverMany() { recovery("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun recoveryCoverManyLight() { recovery("cover-light-many", Fill.MANY, theme = ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun recoveryInnerOne() { recovery("inner-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun recoveryByHandCover() { recoveryByHand("cover-dark-by-hand") }
    @Test @Config(qualifiers = ScreenShots.INNER) fun recoveryByHandInner() { recoveryByHand("inner-dark-by-hand") }
}
