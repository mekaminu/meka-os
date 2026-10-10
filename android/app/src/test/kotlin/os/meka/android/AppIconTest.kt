package os.meka.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** MEKA's launcher icon (call assistant polish item 9): the watch face, never Android's default robot. */
class AppIconTest {
    private fun res(path: String) = File("src/main/res/$path").readText()

    @Test
    fun theAppWearsTheWatchFaceIcon() {
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
    }

    @Test
    fun theFaceIsTheDarkThemesBrassAndFitsTheSafeZone() {
        val tokens = File("../../design/tokens/tokens.json").readText()
        val brass = Regex("\"accent\"\\s*:\\s*\\{\\s*\"\\\$value\"\\s*:\\s*\"(#[0-9A-Fa-f]{6})\"").find(tokens)!!.groupValues[1]
        val face = res("drawable/ic_launcher_foreground.xml")
        assertTrue(brass.uppercase() in face.uppercase(), "the face is drawn in the dark theme's accent $brass")
        // Every point stays inside the adaptive icon's 66 dp safe zone (a circle of radius 33 round 54,54).
        val numbers = Regex("pathData=\"([^\"]+)\"").findAll(face).flatMap { m ->
            Regex("[ML](-?[0-9.]+),(-?[0-9.]+)").findAll(m.groupValues[1]).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
        }.toList()
        assertTrue(numbers.size > 10)
        for ((x, y) in numbers) {
            val r = Math.hypot(x - 54, y - 54)
            assertTrue(r <= 33.0, "($x, $y) is $r from the centre")
        }
    }
}
