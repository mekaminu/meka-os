package os.meka.android.notify

import os.meka.android.shell.ShellDestination
import os.meka.core.domain.NoticeAction
import os.meka.core.domain.NoticePrecision
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NotifyRoutingTest {
    @Test
    fun eachTierHasItsOwnChannelAndAppOnlyNeverGetsOne() {
        val interrupting = listOf(NoticeTier.CRITICAL, NoticeTier.ACTION, NoticeTier.HEADS_UP).map(NotifyRouting::channelId)
        assertEquals(3, interrupting.toSet().size)
        assertEquals("meka_digest", NotifyRouting.channelId(NoticeTier.DIGEST))
        assertTrue(NotifyRouting.channelId(NoticeTier.DIGEST) !in interrupting)
    }

    @Test
    fun tappingLandsWhereTheNoticeBelongs() {
        assertEquals(ShellDestination.LISTS, NotifyRouting.destination(NoticeTarget.LISTS))
        assertEquals(ShellDestination.NEEDS_YOU, NotifyRouting.destination(NoticeTarget.NEEDS_YOU))
        assertEquals(ShellDestination.GOALS, NotifyRouting.destination(NoticeTarget.GOALS))
        assertEquals(ShellDestination.TODAY, NotifyRouting.destination(NoticeTarget.TODAY))
        assertEquals(ShellDestination.REVIEW, NotifyRouting.destination(NoticeTarget.REVIEW))
        // The leave heads-up opens Ask with the Annual leave pane up; the rest open their place.
        assertEquals(ShellDestination.ASK, NotifyRouting.destination(NoticeTarget.LEAVE))
        assertEquals(os.meka.android.MainActivity.OPEN_LEAVE, NotifyRouting.openExtra(NoticeTarget.LEAVE))
        // Sunday's week ahead opens Ask with the Week ahead pane up.
        assertEquals(ShellDestination.ASK, NotifyRouting.destination(NoticeTarget.WEEK_AHEAD))
        assertEquals(os.meka.android.MainActivity.OPEN_WEEK_AHEAD, NotifyRouting.openExtra(NoticeTarget.WEEK_AHEAD))
        assertEquals(os.meka.android.MainActivity.OPEN_DESTINATION_PREFIX + "LISTS", NotifyRouting.openExtra(NoticeTarget.LISTS))
        // A document running out opens the Vault (V2, slice 1).
        assertEquals(ShellDestination.VAULT, NotifyRouting.destination(NoticeTarget.VAULT))
        assertEquals(os.meka.android.MainActivity.OPEN_DESTINATION_PREFIX + "VAULT", NotifyRouting.openExtra(NoticeTarget.VAULT))
        // "Time to check Alex's sizes" opens Ask with the Kids pane up (kids' admin, slice 6).
        assertEquals(ShellDestination.ASK, NotifyRouting.destination(NoticeTarget.KIDS))
        assertEquals(os.meka.android.MainActivity.OPEN_KIDS, NotifyRouting.openExtra(NoticeTarget.KIDS))
    }

    @Test
    fun idsAreStablePositiveAndNeverTheDigests() {
        val id = NotifyRouting.notificationId("renewal:r1:cancel:20001")
        assertEquals(id, NotifyRouting.notificationId("renewal:r1:cancel:20001"))
        assertTrue(id >= 0)
        assertNotEquals(NotifyRouting.DIGEST_ID, id)
    }

    @Test
    fun softRemindersMayBeBatchedLongerThanClockOnes() {
        assertEquals(10 * 60_000L, NotifyRouting.windowMs(NoticePrecision.SOFT))
        assertTrue(NotifyRouting.windowMs(NoticePrecision.CLOCK) < NotifyRouting.windowMs(NoticePrecision.SOFT))
    }

    @Test
    fun eachButtonOnANoticeHasItsOwnIntent() {
        val key = "session:gym:20352:ask"
        val codes = setOf(
            NotifyRouting.actionRequestCode(key, NoticeAction.WENT),
            NotifyRouting.actionRequestCode(key, NoticeAction.DIDNT_GO),
            NotifyRouting.undoRequestCode(key),
            NotifyRouting.actionRequestCode("session:gym:20353:ask", NoticeAction.WENT),
        )
        assertEquals(4, codes.size)
        assertEquals(NotifyRouting.actionRequestCode(key, NoticeAction.WENT), NotifyRouting.actionRequestCode(key, NoticeAction.WENT))
    }

    @Test
    fun leavingNowShowsSendAndEditOnTheFold() {
        val n = os.meka.core.domain.Notice(
            key = "leaving:20738:good", source = os.meka.core.domain.NoticeSource.LEAVING, tier = os.meka.core.domain.NoticeTier.HEADS_UP,
            title = "Tell Jeanette you're leaving?", text = "Leaving now, home about 18:40", atMs = 0L,
            target = os.meka.core.domain.NoticeTarget.TODAY,
            actions = listOf(NoticeAction.SEND_MESSAGE, NoticeAction.EDIT_MESSAGE, NoticeAction.COPY_MESSAGE), postAs = "leaving:20738",
        )
        assertEquals(listOf(NoticeAction.SEND_MESSAGE, NoticeAction.EDIT_MESSAGE), os.meka.core.domain.NotifyRules.actionsOn(n, send = true))
        assertTrue(os.meka.core.domain.NotifyRules.isLeaving(n.key))
        assertTrue(NotifyRouting.actionRequestCode(n.key, NoticeAction.SEND_MESSAGE) != NotifyRouting.actionRequestCode(n.key, NoticeAction.EDIT_MESSAGE))
    }

    @Test
    fun weekAheadListenShowsOnBothApps() {
        val n = os.meka.core.domain.Notice(
            key = "weekahead:20738", source = os.meka.core.domain.NoticeSource.WEEK_AHEAD, tier = os.meka.core.domain.NoticeTier.HEADS_UP,
            title = "Your week ahead", text = "5 office days", atMs = 0L,
            target = os.meka.core.domain.NoticeTarget.WEEK_AHEAD, actions = listOf(NoticeAction.LISTEN),
        )
        assertEquals(listOf(NoticeAction.LISTEN), os.meka.core.domain.NotifyRules.actionsOn(n, send = true))
        assertEquals(listOf(NoticeAction.LISTEN), os.meka.core.domain.NotifyRules.actionsOn(n, send = false))
        assertEquals("Listen", os.meka.core.domain.NotifyRules.actionLabel(NoticeAction.LISTEN))
    }
}
