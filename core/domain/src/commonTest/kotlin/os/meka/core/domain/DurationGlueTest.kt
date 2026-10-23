package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DurationGlueTest {
    private val nb = LifeTileRules.GLUE

    @Test
    fun aDurationIsKeptWithTheWordItMeasures() {
        assertEquals(
            "Suggested from Work at 09:00 · 1${nb}h${nb}20${nb}commute · 1${nb}h to get ready",
            DurationGlue.kept("Suggested from Work at 09:00 · 1 h 20 commute · 1 h to get ready"),
        )
        assertEquals("19:00–21:00 · 2${nb}h${nb}free", DurationGlue.kept("19:00–21:00 · 2 h free"))
        assertEquals("45${nb}min${nb}walk", DurationGlue.kept("45 min walk"))
    }

    @Test
    fun aJoiningWordAfterItStaysFree() {
        assertEquals("1${nb}h${nb}20 from Work", DurationGlue.kept("1 h 20 from Work"))
        assertEquals("Leave 1${nb}h before", DurationGlue.kept("Leave 1 h before"))
    }

    @Test
    fun clockTimesAndOtherNumbersAreLeftAlone() {
        assertEquals("Work at 09:00", DurationGlue.kept("Work at 09:00"))
        assertEquals("3 events · 1 task", DurationGlue.kept("3 events · 1 task"))
        // A following hour count is its own duration, never read as the first one's minutes.
        assertEquals("4${nb}h 8${nb}h", DurationGlue.kept("4 h 8 h"))
    }

    @Test
    fun rangesCoverThePhraseInPlainOrKeptLines() {
        val line = "Suggested from Work at 09:00 · 1 h 20 commute · 1 h to get ready"
        val r = DurationGlue.ranges(line).map { line.substring(it) }
        assertEquals(listOf("1 h 20 commute", "1 h"), r)
        assertEquals(DurationGlue.ranges(line), DurationGlue.ranges(DurationGlue.kept(line)))
        assertEquals(listOf("1 h 20"), DurationGlue.ranges("1 h 20 from Work").map { "1 h 20 from Work".substring(it) })
    }
}
