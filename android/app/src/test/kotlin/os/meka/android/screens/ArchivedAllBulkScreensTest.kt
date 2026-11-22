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
import androidx.compose.ui.test.performClick
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
import os.meka.android.today.ARCHIVED_ALL_CONFIRM_TAG
import os.meka.android.today.ArchivedAllPane
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailTidiedAction
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
 * Tidied mail, slice 4 on the Fold (Meka 2026-11-18 23:34, part (3)): All tidied mail on today's promos offers
 * **Select** (Select all · "3 selected" · Trash 3 · Put back 3) and **Trash all 3 promos**, whose confirm says how many
 * and that they go to Trash for 30 days; Move 3 to Trash asks all three with one Undo on the bar. Invented senders
 * (rule 13). Saved under `needs-you/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ArchivedAllBulkScreensTest {
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
        ops.append(Op("srvab${k++}", "hh", EntityTypes.MAIL_TIDY, id, f, v, clock.now(), emptyList(), "server"))
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
        val emails = listOf(promo("p1", "Oakfield Shoes", 50), promo("p2", "Sam's Deli", 40), promo("p3", "Alex Books", 30))
        serverWrite("mt1", MailAutopilotRules.batchFields(now - 20 * 60_000L, "Personal", mapOf(MailTidy.ARCHIVE to 3)) + MailTidiedRules.fields(emails))
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
        // The list is read off the main thread: wait for it rather than fail on a slow CI frame. The read runs in real
        // time, not the test's clock, so wait up to 15 s of wall time (ci runs 45028591163 and 45033418457 flaked on
        // the clock-only wait: "Trash all 3 promos is missing").
        val until = System.currentTimeMillis() + 15_000L
        while (!ScreenShots.texts(compose).any { it.contains("Trash all 3 promos") } && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            turns(200)
        }
        return core
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun selectSeveralAndTrashAllBehindItsConfirm() {
        open()
        if (!onScreen("Trash all 3 promos")) fail("Trash all 3 promos is missing")
        compose.onNodeWithText("Select").performClick()
        turns(800)
        if (!onScreen("Tap emails to select them")) fail("the bar should ask to pick emails")
        compose.onNodeWithText("Select all").performClick()
        turns(800)
        listOf("3 selected", "Trash 3", "Put back 3", "Clear").forEach { if (!onScreen(it)) fail("$it is missing from the bar") }
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "tidied-select-cover-dark"))
        compose.onNodeWithText("Done").performClick()
        turns(800)
        compose.onNodeWithText("Trash all 3 promos").performClick()
        turns(800)
        compose.onNodeWithTag(ARCHIVED_ALL_CONFIRM_TAG, useUnmergedTree = true).assertExists()
        listOf("Move 3 promos to Trash?", "can be put back for 30 days", "Move 3 to Trash", "Cancel")
            .forEach { if (!onScreen(it)) fail("the confirm should say $it") }
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "tidied-trash-all-cover-dark"))
        compose.onNodeWithText("Move 3 to Trash").performClick()
        turns()
        if (said != "Moving 3 promos to Trash at Navilife's next check") fail("the bar said $said")
        if (onScreen("Trash all 3 promos")) fail("Trash all should go once they're asked")
        if (!onScreen(MailTidiedAction.TRASH.pending)) fail("each row should say it's on its way to Trash")
    }
}
