package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import os.meka.android.today.NEEDS_YOU_GROUP_TAG
import os.meka.android.today.NEEDS_YOU_MAIL_ROW_TAG
import os.meka.android.today.NEEDS_YOU_SCHOOL_SUMMARY_TAG
import os.meka.android.today.NEEDS_YOU_SCHOOL_TAG
import os.meka.android.today.NeedsYouRoute
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.NeedsYouGroup
import os.meka.core.domain.SchoolBodyRules
import os.meka.core.domain.SchoolSummaryLine
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
 * School emails first, slice 1b (Meka 2026-11-24): an email from the children's school sits in its own **From the
 * school** box, first in Needs you and open before he has touched it, with its count; the other email stays in Mail to
 * answer, which counts only it. The emails come from a stand-in server, written as the server writes them (the school's
 * marked as the school's). Invented names only (rule 13). Saved under `needs-you/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouSchoolScreensTest {
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

    private fun email(message: String, from: String, subject: String, agoMs: Long, school: Boolean,
                      summary: List<SchoolSummaryLine> = emptyList()): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields("Personal", from, null, subject, "Hello", now - agoMs, 1, school = school, schoolSummary = summary))
        return id
    }

    private fun turns(ms: Long = 3_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun shoot(name: String, theme: ThemeChoice) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        // Before he has opened or closed a box: From the school opens by itself.
        context.getSharedPreferences("needs_you", 0).edit().clear().commit()
        // Slice 2b: its summary card, as the server writes it after reading the whole email.
        val fair = email("s1", "Oakfield Primary", "Christmas fair this Saturday", hour, school = true, summary = listOf(
            SchoolSummaryLine(SchoolBodyRules.WHEN, "Sat 24 Oct, 12:00–16:00"),
            SchoolSummaryLine(SchoolBodyRules.WHERE, "the school hall"),
            SchoolSummaryLine(SchoolBodyRules.COST, "£1 per adult"),
            SchoolSummaryLine(SchoolBodyRules.BRING, "Bring cakes for the cake stall; no parking at school"),
        ))
        val football = email("m1", "Sam Taylor", "Football on Saturday", 2 * hour, school = false)
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
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { NeedsYouRoute(core) }
            }
        }
        turns()
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
        ScreenShots.assertSensible(compose, name)

        fun heads() = compose.onAllNodes(hasTestTag(NEEDS_YOU_GROUP_TAG), useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
        fun spoken(n: androidx.compose.ui.semantics.SemanticsNode) =
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        val first = heads().firstOrNull() ?: fail("$name: no boxes on Needs you")
        if (!spoken(first).startsWith("${NeedsYouGroup.SCHOOL.title}, 1. Open")) fail("$name: the first box is \"${spoken(first)}\", not From the school, 1, open")
        val mail = heads().firstOrNull { spoken(it).startsWith(NeedsYouGroup.MAIL.title) } ?: fail("$name: no Mail to answer box")
        if (!spoken(mail).startsWith("${NeedsYouGroup.MAIL.title}, 1.")) fail("$name: Mail to answer says \"${spoken(mail)}\", not 1")
        // The school's email is in From the school's box; the other isn't.
        val box = compose.onAllNodes(hasTestTag(NEEDS_YOU_SCHOOL_TAG), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            ?: fail("$name: From the school's box holds no emails")
        fun inBox(id: String) = compose.onAllNodes(hasTestTag("$NEEDS_YOU_MAIL_ROW_TAG-$id"), useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.boundsInRoot.top >= box.boundsInRoot.top && it.boundsInRoot.bottom <= box.boundsInRoot.bottom + 1f }
        if (!inBox(fair)) fail("$name: the school's email isn't in From the school")
        if (inBox(football)) fail("$name: a person's email sits in From the school")
        // Slice 2b: the school email's card shows under its subject, inside the box, without opening it.
        val card = compose.onAllNodes(hasTestTag("$NEEDS_YOU_SCHOOL_SUMMARY_TAG-$fair"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            ?: fail("$name: the school email shows no summary card")
        if (card.boundsInRoot.bottom > box.boundsInRoot.bottom + 1f) fail("$name: the summary card spills out of From the school")
        for (t in listOf("When", "Sat 24 Oct, 12:00–16:00", "Cost", "£1 per adult")) {
            if (compose.onAllNodes(ScreenShots.plainText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("$name: the card doesn't say \"$t\"")
        }
        if (compose.onAllNodes(hasTestTag("$NEEDS_YOU_SCHOOL_SUMMARY_TAG-$football"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) fail("$name: a person's email wears a school card")
        if (compose.onAllNodes(hasContentDescription("1 from the school", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(ScreenShots.plainText("1 from the school", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            fail("$name: the header doesn't lead with the school's email")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverSchool() = shoot("cover-dark-school", ThemeChoice.DARK)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverSchoolLight() = shoot("cover-light-school", ThemeChoice.LIGHT)
}
