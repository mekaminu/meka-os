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
    fun linesAndSizes() {
        assertEquals("Build 412 · 24.3 MB", AppUpdateRules.line(b412))
        assertEquals("MEKA 0.1.412 · build 412 · 24.3 MB", AppUpdateRules.summary(b412))
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
}
