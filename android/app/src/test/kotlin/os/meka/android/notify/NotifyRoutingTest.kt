package os.meka.android.notify

import os.meka.android.shell.ShellDestination
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
}
