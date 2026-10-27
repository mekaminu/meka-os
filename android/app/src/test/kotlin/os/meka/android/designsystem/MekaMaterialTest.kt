package os.meka.android.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.screens.ShotApplication

/**
 * Material's menus, date picker and dialogs wear MEKA's colours (Meka 2026-10-26 23:18, (5): a white dropdown with
 * near-invisible light text). The scheme maps every surface a menu or picker draws on to MEKA's raised surface and its
 * text to MEKA's text, in Dark and Light; inside MekaTheme a menu reads them and plain text keeps its old style.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class)
class MekaMaterialTest {
    @get:Rule
    val compose = createComposeRule()

    private fun ColorScheme.menuSurfaces() = listOf(surface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)

    @Test
    fun everyMenuSurfaceIsMekasRaisedSurfaceWithMekasText() {
        for ((colors, dark) in listOf(MekaDarkColors to true, MekaLightColors to false)) {
            val s = mekaColorScheme(colors, dark)
            s.menuSurfaces().forEach { assertEquals(colors.surfaceRaised, it) }
            assertEquals(colors.textPrimary, s.onSurface)
            assertEquals(colors.textSecondary, s.onSurfaceVariant)
            assertEquals(colors.accent, s.primary)
            assertEquals(colors.hairline, s.outline)
        }
        // Dark's menu is dark and its text light: never the white menu with light text again.
        val dark = mekaColorScheme(MekaDarkColors, dark = true)
        assertTrue(dark.surfaceContainer.lum() < 0.3f, "dark menu surface")
        assertTrue(dark.onSurface.lum() > 0.7f, "dark menu text")
        val light = mekaColorScheme(MekaLightColors, dark = false)
        assertTrue(light.surfaceContainer.lum() > 0.7f, "light menu surface")
        assertTrue(light.onSurface.lum() < 0.3f, "light menu text")
    }

    @Test
    fun insideMekaThemeMaterialReadsMekasColoursAndPlainTextKeepsItsStyle() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        var scheme: ColorScheme? = null
        var meka: MekaColors? = null
        var style: TextStyle? = null
        var outer: TextStyle? = null
        compose.setContent {
            outer = LocalTextStyle.current
            MekaTheme {
                scheme = MaterialTheme.colorScheme
                meka = Meka.colors
                style = LocalTextStyle.current
                Box {
                    DropdownMenu(
                        expanded = true, onDismissRequest = {},
                        containerColor = Meka.colors.surfaceRaised, border = mekaMenuBorder(Meka.colors),
                    ) {
                        DropdownMenuItem(text = { Text("Make it a task") }, onClick = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Make it a task").assertIsDisplayed()
        assertNotNull(scheme)
        assertEquals(meka!!.surfaceRaised, scheme!!.surfaceContainer)
        assertEquals(meka!!.textPrimary, scheme!!.onSurface)
        assertEquals(outer, style)
    }

    private fun Color.lum() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
