package os.meka.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MEKA's launcher icon: the Hands M (Meka chose option B on 2026-10-12 20:36, replacing the Bezel M), never
 * Android's default robot and not the full watch face, which read as a clock app.
 */
class AppIconTest {
    private fun res(path: String) = File("src/main/res/$path").readText()

    private fun points(xml: String) = Regex("pathData=\"([^\"]+)\"").findAll(xml).flatMap { m ->
        Regex("[ML](-?[0-9.]+),(-?[0-9.]+)").findAll(m.groupValues[1]).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
    }.toList()

    @Test
    fun theAppWearsTheHandsMIcon() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val application = manifest.substringAfter("<application").substringBefore(">")
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in application)
        assertTrue("android:roundIcon=\"@mipmap/ic_launcher_round\"" in application)
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val adaptive = res("mipmap-anydpi/$name.xml")
            assertTrue("@drawable/ic_launcher_background" in adaptive)
            assertTrue("@drawable/ic_launcher_foreground" in adaptive)
            // Themed icons on One UI / Android 13+ need the single-colour layer.
            assertTrue("@drawable/ic_launcher_monochrome" in adaptive)
        }
        // The Galaxy Watch carries the very same icon.
        for (layer in listOf("foreground", "monochrome", "background")) {
            assertTrue(res("drawable/ic_launcher_$layer.xml") == File("../../wear/app/src/main/res/drawable/ic_launcher_$layer.xml").readText())
        }
    }

    @Test
    fun theIconIsTheDarkThemesBrassAndFitsTheSafeZone() {
        val tokens = File("../../design/tokens/tokens.json").readText()
        val brass = Regex("\"accent\"\\s*:\\s*\\{\\s*\"\\\$value\"\\s*:\\s*\"(#[0-9A-Fa-f]{6})\"").find(tokens)!!.groupValues[1]
        for (layer in listOf("foreground", "monochrome")) {
            val icon = res("drawable/ic_launcher_$layer.xml")
            if (layer == "foreground") assertTrue(brass.uppercase() in icon.uppercase(), "the icon is drawn in the dark theme's accent $brass")
            assertTrue("MEKA's Hands M" in icon)
            // No rim any more: the two hands, the pin, and the pivot's hole cut through all three.
            assertFalse("a26,26" in icon, "the Bezel M's rim is gone")
            assertTrue(icon.split("android:fillType=\"evenOdd\"").size == 4, "both hands and the pin have the hole cut through")
            assertTrue("a1.05,1.05" in icon, "the pivot's hole")
            // The stems are heavy enough to hold at 48 px (2.6 of the 72 visible units ≈ 1.7 px).
            val stem = Regex("strokeWidth=\"([0-9.]+)\"").find(icon)!!.groupValues[1].toDouble()
            assertTrue(stem >= 2.4, "stems $stem")
            val numbers = points(icon)
            assertTrue(numbers.size > 10)
            // Every point stays inside the adaptive icon's 66 dp safe zone (a circle of radius 33 round 54,54).
            for ((x, y) in numbers) {
                val r = Math.hypot(x - 54, y - 54)
                assertTrue(r <= 33.0, "($x, $y) is $r from the centre")
            }
        }
    }

    @Test
    fun notificationsWearTheMInTheStatusBar() {
        val glyph = res("drawable/ic_stat_meka.xml")
        assertTrue("android:width=\"24dp\"" in glyph)
        // Status-bar icons are one colour (Android tints them): white, nothing else.
        assertTrue(Regex("#[0-9A-Fa-f]{6,8}").findAll(glyph).all { it.value.uppercase() == "#FFFFFFFF" })
        // MEKA's own notifications use it rather than Android's generic icons.
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        assertTrue(sources.any { "R.drawable.ic_stat_meka" in it })
        for (generic in listOf("stat_notify_more", "stat_notify_chat", "stat_sys_download_done")) {
            assertTrue(sources.none { "android.R.drawable.$generic" in it }, "$generic is gone")
        }
    }
}
