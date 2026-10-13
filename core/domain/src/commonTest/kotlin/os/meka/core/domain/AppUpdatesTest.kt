package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdatesTest {
    private val b412 = AppUpdateRules.Build(412, "0.1.412", 24_300_000)

    @Test
    fun onlyANewerBuildIsOfferedAndLaterPutsOffJustThatBuild() {
        assertEquals(b412, AppUpdateRules.offer(b412, installedCode = 400, laterCode = null))
        assertNull(AppUpdateRules.offer(b412, installedCode = 412, laterCode = null))
        assertNull(AppUpdateRules.offer(b412, installedCode = 500, laterCode = null))
        assertNull(AppUpdateRules.offer(null, installedCode = 1, laterCode = null))
        assertNull(AppUpdateRules.offer(b412, installedCode = 400, laterCode = 412))
        // A newer build comes back even after "Later" on the previous one.
        val b413 = b412.copy(versionCode = 413)
        assertEquals(b413, AppUpdateRules.offer(b413, installedCode = 400, laterCode = 412))
    }

    @Test
    fun theUpdateNotificationComesOncePerBuildAndOnlyWhenMekaIsNotOnScreen() {
        val b413 = b412.copy(versionCode = 413)
        assertEquals(true, AppUpdateRules.shouldNotify(b412, toldCode = null, onScreen = false))
        assertEquals(false, AppUpdateRules.shouldNotify(b412, toldCode = null, onScreen = true))
        assertEquals(false, AppUpdateRules.shouldNotify(null, toldCode = null, onScreen = false))
        // Once told (by the notification or the card on screen), not again for that build; a newer one is news.
        assertEquals(412L, AppUpdateRules.told(b412, null))
        assertEquals(false, AppUpdateRules.shouldNotify(b412, toldCode = 412, onScreen = false))
        assertEquals(true, AppUpdateRules.shouldNotify(b413, toldCode = 412, onScreen = false))
        assertEquals(413L, AppUpdateRules.told(b413, 412))
        // An older build showing up (a rollback on the server) neither notifies nor rewinds what was told.
        assertEquals(false, AppUpdateRules.shouldNotify(b412, toldCode = 413, onScreen = false))
        assertEquals(413L, AppUpdateRules.told(b412, 413))
        assertEquals(413L, AppUpdateRules.told(null, 413))
        assertEquals("Build 412 · 24.3 MB · open Navilife to install", AppUpdateRules.notificationLine(b412))
    }

    @Test
    fun linesAndSizes() {
        assertEquals("Build 412 · 24.3 MB", AppUpdateRules.line(b412))
        assertEquals("Navilife 0.1.412 · build 412 · 24.3 MB", AppUpdateRules.summary(b412))
        assertEquals("1 KB", AppUpdateRules.sizeLabel(10))
        assertEquals("850 KB", AppUpdateRules.sizeLabel(850_000))
        assertEquals("1.0 MB", AppUpdateRules.sizeLabel(999_999))
        assertEquals("9.9 MB", AppUpdateRules.sizeLabel(9_940_000))
        assertEquals("120 MB", AppUpdateRules.sizeLabel(120_400_000))
    }

    @Test
    fun progressNeverReadsDoneEarly() {
        assertEquals("Downloading · 0%", AppUpdateRules.progressLine(0, 25))
        assertEquals("Downloading · 40%", AppUpdateRules.progressLine(10, 25))
        assertEquals(99, AppUpdateRules.percent(199, 200))
        assertEquals(100, AppUpdateRules.percent(25, 25))
        assertEquals(0, AppUpdateRules.percent(0, 0))
    }

    // Smaller phone updates: each build says its size in Activity, once.
    @Test
    fun theInstalledBuildSaysItsSizeOnce() {
        assertEquals("Updated to build 471 · 18.2 MB", AppUpdateRules.installedSummary(471, 18_200_000, previousCode = 470))
        assertEquals("Installed build 471 · 18.2 MB", AppUpdateRules.installedSummary(471, 18_200_000, previousCode = null))
        assertEquals("From build 470 (55.1 MB) · 36.9 MB smaller", AppUpdateRules.installedWhy(18_200_000, 470, 55_100_000))
        assertEquals("From build 470 (18.2 MB) · same size", AppUpdateRules.installedWhy(18_210_000, 470, 18_200_000))
        assertEquals("From build 470 (18.2 MB) · 1.0 MB bigger", AppUpdateRules.installedWhy(19_200_000, 470, 18_200_000))
        assertEquals("From build 470", AppUpdateRules.installedWhy(18_200_000, 470, null))
        assertEquals("Navilife takes 18.2 MB on this phone", AppUpdateRules.installedWhy(18_200_000, null, null))
        assertEquals(true, AppUpdateRules.shouldRecordInstall(471, null))
        assertEquals(true, AppUpdateRules.shouldRecordInstall(471, 470))
        assertEquals(false, AppUpdateRules.shouldRecordInstall(471, 471))
        assertEquals(AppUpdateRules.installedActivityId(471), AppUpdateRules.installedActivityId(471))
        assertEquals("10.3 MB · was 55.1 MB", AppUpdateRules.healthSize(10_300_000, 55_100_000))
        assertEquals("10.3 MB", AppUpdateRules.healthSize(10_300_000, null))
        assertEquals("10.3 MB", AppUpdateRules.healthSize(10_300_000, 10_310_000))
        assertEquals(null, AppUpdateRules.healthSize(null, 55_100_000))
    }

    @Test
    fun theNewIconIsNotedOnceAndOnlyAfterAnUpdate() {
        // An update from a build with the old icon: say so.
        kotlin.test.assertTrue(AppUpdateRules.shouldNoteIcon(notedIcon = null, hadEarlierBuild = true))
        // Already said, or a fresh install (the icon was never different): stay quiet.
        kotlin.test.assertFalse(AppUpdateRules.shouldNoteIcon(notedIcon = AppUpdateRules.ICON, hadEarlierBuild = true))
        kotlin.test.assertFalse(AppUpdateRules.shouldNoteIcon(notedIcon = null, hadEarlierBuild = false))
        // A later icon change would be noted again under its own entry.
        kotlin.test.assertTrue(AppUpdateRules.shouldNoteIcon(notedIcon = "hands-m", hadEarlierBuild = true))
        kotlin.test.assertTrue(AppUpdateRules.iconActivityId() != AppUpdateRules.iconActivityId("hands-m"))
        kotlin.test.assertEquals("Now called Navilife", AppUpdateRules.ICON_SUMMARY)
        kotlin.test.assertTrue("restart" in AppUpdateRules.ICON_WHY)
    }
}
