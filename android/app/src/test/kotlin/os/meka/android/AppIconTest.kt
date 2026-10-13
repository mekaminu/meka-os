package os.meka.android

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The launcher icon: Navilife's logo N3·2 (Meka picked it 2026-10-13 12:09 with the rename; it replaced the Hands M),
 * made by tools/make-app-icon.py from the logo's own drawing, never Android's default robot.
 */
class AppIconTest {
    private fun res(path: String) = File("src/main/res/$path").readText()
    private fun png(path: String) = ImageIO.read(File("src/main/res/$path"))

    private val densities = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)

    @Test
    fun theAppWearsTheNavilifeIcon() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val application = manifest.substringAfter("<application").substringBefore(">")
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in application)
        assertTrue("android:roundIcon=\"@mipmap/ic_launcher_round\"" in application)
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val adaptive = res("mipmap-anydpi/$name.xml")
            assertTrue("N3·2" in adaptive)
            assertTrue("@drawable/ic_launcher_background" in adaptive)
            assertTrue("@drawable/ic_launcher_foreground" in adaptive)
            // Themed icons on One UI / Android 13+ need the single-colour layer.
            assertTrue("@drawable/ic_launcher_monochrome" in adaptive)
        }
        // The old vector layers are gone, so Android can't pick them over the PNGs.
        assertFalse(File("src/main/res/drawable/ic_launcher_foreground.xml").exists())
        assertFalse(File("src/main/res/drawable/ic_launcher_monochrome.xml").exists())
        // The Galaxy Watch carries the very same icon.
        assertTrue(res("drawable/ic_launcher_background.xml") == File("../../wear/app/src/main/res/drawable/ic_launcher_background.xml").readText())
        for (layer in listOf("foreground", "monochrome")) for (d in densities.keys) {
            val path = "drawable-$d/ic_launcher_$layer.png"
            assertTrue(File("src/main/res/$path").readBytes().contentEquals(File("../../wear/app/src/main/res/$path").readBytes()), path)
        }
    }

    @Test
    fun theLayersFitTheSafeZoneAtEveryDensity() {
        for ((d, k) in densities) for (layer in listOf("foreground", "monochrome")) {
            val img = png("drawable-$d/ic_launcher_$layer.png")
            val side = Math.round(108 * k).toInt()
            assertEquals(side, img.width, "$d $layer")
            assertEquals(side, img.height, "$d $layer")
            // Nothing drawn outside the adaptive icon's 66 dp safe zone (radius 33 dp round the centre), half a pixel slack.
            val c = side / 2.0
            val safe = 33 * k + 0.5
            var inked = 0
            for (y in 0 until side) for (x in 0 until side) {
                val a = img.getRGB(x, y) ushr 24
                if (a == 0) continue
                inked++
                val r = Math.hypot(x + 0.5 - c, y + 0.5 - c)
                assertTrue(r <= safe, "$d $layer ($x, $y) is $r from the centre")
            }
            assertTrue(inked > side * side / 20, "$d $layer has the dial in it")
        }
    }

    @Test
    fun theDialIsTheDarkThemesBrassAndTheThemedLayerIsOneColour() {
        val fg = png("drawable-xxxhdpi/ic_launcher_foreground.png")
        // The ring (3 o'clock, 31.4 dp out) is the dark theme's accent, near enough.
        val k = 4.0
        val ring = fg.getRGB(Math.round(54 * k + 31.4 * k).toInt(), Math.round(54 * k).toInt())
        val (r, g, b) = Triple(ring shr 16 and 0xFF, ring shr 8 and 0xFF, ring and 0xFF)
        assertTrue(r > b + 40 && g > b + 20, "brass, not grey: ${Integer.toHexString(ring)}")
        val mono = png("drawable-xxxhdpi/ic_launcher_monochrome.png")
        for (y in 0 until mono.height) for (x in 0 until mono.width) {
            val px = mono.getRGB(x, y)
            if (px ushr 24 == 0) continue
            assertEquals(0xFFFFFF, px and 0xFFFFFF, "the themed layer is white only ($x, $y)")
        }
    }

    @Test
    fun notificationsWearTheDialInTheStatusBar() {
        val glyph = res("drawable/ic_stat_meka.xml")
        assertTrue("android:width=\"24dp\"" in glyph)
        // Status-bar icons are one colour (Android tints them): white, nothing else.
        assertTrue(Regex("#[0-9A-Fa-f]{6,8}").findAll(glyph).all { it.value.uppercase() == "#FFFFFFFF" })
        // Navilife's own notifications use it rather than Android's generic icons.
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        assertTrue(sources.any { "R.drawable.ic_stat_meka" in it })
        for (generic in listOf("stat_notify_more", "stat_notify_chat", "stat_sys_download_done")) {
            assertTrue(sources.none { "android.R.drawable.$generic" in it }, "$generic is gone")
        }
    }
}
