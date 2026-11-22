package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
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
import os.meka.android.today.ARCHIVED_ALL_PAGER_TAG
import os.meka.android.today.ArchivedAllPane
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailTidiedEmail
import os.meka.core.domain.MailTidiedRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.TidiedKind
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
 * Tidied mail, slice 5 on the Fold (Meka 2026-11-18 23:34, part (4)): All tidied mail shows five emails a page with
 * Needs you's pager ("1–5 of 8", ‹ ›); Select all on this page picks this page's five, and a pick on another page is kept
 * and counted. Invented senders (rule 13). Saved under `needs-you/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ArchivedAllPagesScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 London
    private val ops = InMemoryServerOpStore()
    private val sync = SyncService(ops)
    private val transport = object : SyncTransport {
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
    }
    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        ops.append(Op("srvpg${k++}", "hh", EntityTypes.MAIL_TIDY, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun promo(id: String, from: String, minsAgo: Int) = MailTidiedEmail(
        MailTidiedRules.key("acc", id), MailTidy.ARCHIVE, MailSort.PROMO, now - minsAgo * 60_000L, from,
        "${from.lowercase().filter { it.isLetterOrDigit() }}.example", "Weekend sale $id", null,
    )

    private var said: String? = null

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun open(): MekaCore {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), ThemeChoice.DARK)
        val emails = (1..8).map { promo("p$it", "Sender $it", 100 - it * 5) }
        serverWrite("mt1", MailAutopilotRules.batchFields(now - 2 * 60_000L, "Personal", mapOf(MailTidy.ARCHIVE to 8)) + MailTidiedRules.fields(emails))
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
            secureRandom = Random(47), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        runBlocking { core.syncNow() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    ArchivedAllPane(core, { line, _ -> said = line }, startKind = TidiedKind.PROMOS.id, onClose = {})
                }
            }
        }
        turns()
        // The list is read off the main thread: wait for it (up to 6 s more) rather than fail on a slow CI frame.
        repeat(30) { if (!ScreenShots.texts(compose).any { it.contains("Trash all 8 promos") }) turns(200) }
        return core
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    private fun toPager() {
        compose.onNodeWithTag("archived-all").performScrollToNode(hasTestTag(ARCHIVED_ALL_PAGER_TAG))
        turns(600)
    }

    private fun toTop() {
        compose.onNodeWithTag("archived-all").performScrollToIndex(0)
        turns(600)
    }

    /** Every sender whose row is on the page (its "Sender 3 · sender3.example" line, not the chips), top to bottom. */
    private fun senders(): Set<String> {
        toTop()
        val seen = mutableSetOf<String>()
        fun read() = ScreenShots.texts(compose).forEach { t -> Regex("(Sender \\d) · sender").findAll(t).forEach { seen += it.groupValues[1] } }
        read()
        compose.onNodeWithTag("archived-all").performScrollToIndex(7)
        turns(300)
        read()
        toPager()
        read()
        return seen
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun fiveAPageWithThePagerAndSelectAllOnThisPage() {
        open()
        val first = senders()
        if (first != (4..8).map { "Sender $it" }.toSet()) fail("page one should hold the newest five, held $first")
        toPager()
        if (!onScreen("1–5 of 8")) fail("the pager should say 1–5 of 8")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "tidied-pages-cover-dark"))
        compose.onNodeWithContentDescription("Next page").performClick()
        turns(1_000)
        toPager()
        if (!onScreen("6–8 of 8")) fail("› should open 6–8 of 8")
        val second = senders()
        if (second != (1..3).map { "Sender $it" }.toSet()) fail("page two should hold the oldest three, held $second")
        toPager()
        compose.onNodeWithContentDescription("Previous page").performClick()
        turns(1_000)
        toTop()
        compose.onNodeWithText("Select").performClick()
        turns(800)
        compose.onNodeWithText("Select all on this page").performClick()
        turns(800)
        listOf("5 selected", "Trash 5", "Clear this page").forEach { if (!onScreen(it)) fail("$it is missing from the bar") }
        toPager()
        compose.onNodeWithContentDescription("Next page").performClick()
        turns(1_000)
        toTop()
        compose.onNodeWithText("Select all on this page").performClick()
        turns(800)
        listOf("8 selected", "Trash 8").forEach { if (!onScreen(it)) fail("$it is missing after page two") }
        compose.onNodeWithText("Clear this page").performClick()
        turns(800)
        if (!onScreen("5 selected")) fail("Clear this page should keep page one's five")
    }
}
