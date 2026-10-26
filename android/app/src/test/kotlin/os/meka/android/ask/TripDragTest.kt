package os.meka.android.ask

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists editing, slice 4b: the drag's state on the Fold, the group under the finger as the heads lie on screen. */
class TripDragTest {
    private fun drag(vararg heads: Pair<String, Float>) = TripDrag().also { d ->
        heads.forEach { (n, top) -> d.heads[n] = Rect(0f, top, 800f, top + 48f) }
    }

    @Test
    fun theGroupUnderTheFingerIsTheOneWhoseStretchItIsIn() {
        val d = drag("Documents" to 300f, "Clothes" to 600f, "Other" to 900f)
        val order = listOf("Documents", "Clothes", "Other")
        d.start("i1", null, "Documents", 400f)
        assertTrue(d.active)
        assertEquals("Documents", d.groupUnder(order))
        d.offsetY = 250f
        assertEquals("Clothes", d.groupUnder(order))
        d.offsetY = 600f
        assertEquals("Other", d.groupUnder(order))
        d.reset()
        assertFalse(d.active)
        assertNull(d.target)
    }

    @Test
    fun aboveEveryHeadOnScreenIsTheGroupWhoseHeadScrolledAway() {
        // Documents' head has scrolled off the top; the finger is over its last rows, above Clothes' head.
        val d = drag("Clothes" to 300f, "Other" to 700f)
        d.start("i1", null, "Clothes", 350f)
        d.offsetY = -200f
        assertEquals("Documents", d.groupUnder(listOf("Documents", "Clothes", "Other")))
        assertEquals("Clothes", d.groupUnder(listOf("Clothes", "Other")))
        assertNull(TripDrag().groupUnder(listOf("Documents")))
    }
}
