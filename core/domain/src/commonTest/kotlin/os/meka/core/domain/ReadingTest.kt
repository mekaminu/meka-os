package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading and learning, slice 1: books, podcasts and courses typed in, progress, finished and put down, with Undo. */
class ReadingTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val cal = LocalCalendar.fixedOffset(0L)
    private fun list(dev: Device) = Reading(dev.replica, { "b${n++}" }, { world.clock.nowMs })
    private val rFold = list(fold)
    private val rMac = list(mac)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun aLineBecomesABookPodcastOrCourse() {
        val book = ReadingRules.parse("Atomic Habits by James Clear, 320 pages")!!
        assertEquals("Atomic Habits", book.title)
        assertEquals("James Clear", book.by)
        assertEquals(ReadingKind.BOOK, book.kind)
        assertEquals(ReadingUnit.PAGE, book.unit)
        assertEquals(320L, book.total)
        assertFalse(book.started)

        val pod = ReadingRules.parse("podcast The Rest Is History")!!
        assertEquals("The Rest Is History", pod.title)
        assertEquals(ReadingKind.PODCAST, pod.kind)
        assertEquals(ReadingUnit.EPISODE, pod.unit)

        val course = ReadingRules.parse("course Kotlin coroutines 12 lessons")!!
        assertEquals("Kotlin coroutines", course.title)
        assertEquals(ReadingKind.COURSE, course.kind)
        assertEquals(12L, course.total)

        val reading = ReadingRules.parse("I'm reading dune by Frank Herbert")!!
        assertEquals("Dune", reading.title)
        assertTrue(reading.started)

        assertEquals(ReadingKind.COURSE, ReadingRules.parse("https://www.udemy.com/course/kotlin/ Kotlin for Android")!!.kind)
        assertEquals(ReadingKind.PODCAST, ReadingRules.parse("https://overcast.fm/+abc Hard Fork")!!.kind)
        assertEquals("Stand by Me", ReadingRules.parse("Stand by Me")!!.title)
        assertEquals("", ReadingRules.parse("Stand by Me")!!.by)
        assertEquals("Homer", ReadingRules.parse("The Odyssey, by Homer")!!.by)
        assertEquals("The Book Thief", ReadingRules.parse("The Book Thief")!!.title)
        assertNull(ReadingRules.parse("  "))
        assertNull(ReadingRules.parse("book"))
    }

    @Test
    fun progressIsReadHoweverItsTyped() {
        assertEquals(ReadingProgress(120, null, null, false), ReadingRules.progress("120"))
        assertEquals(ReadingProgress(120, 320, ReadingUnit.PAGE, false), ReadingRules.progress("p 120 of 320"))
        assertEquals(ReadingProgress(120, 320, null, false), ReadingRules.progress("120/320"))
        assertEquals(ReadingProgress(45, 100, ReadingUnit.PERCENT, false), ReadingRules.progress("45%"))
        assertEquals(ReadingProgress(4, null, ReadingUnit.EPISODE, false), ReadingRules.progress("episode 4"))
        assertEquals(ReadingProgress(3, 12, ReadingUnit.LESSON, false), ReadingRules.progress("lesson 3 of 12"))
        assertEquals(ReadingProgress(null, null, null, true), ReadingRules.progress("Finished"))
        assertNull(ReadingRules.progress("loads"))
        assertNull(ReadingRules.progress("140%"))
        assertNull(ReadingRules.progress("400 of 320"))
    }

    @Test
    fun upNextThenOnTheGoThenFinishedOnBothApps() {
        val added = rFold.add("Atomic Habits by James Clear, 320 pages")
        assertEquals("Added book Atomic Habits by James Clear · up next", added.line)
        assertNotNull(added.undo)
        assertEquals("Already on your list: Atomic Habits", rFold.add("atomic habits").line)
        world.clock.advance(1_000)
        rFold.add("podcast The Rest Is History")
        sync()
        var v = rMac.view(cal)
        assertEquals(listOf("Atomic Habits", "The Rest Is History"), v.upNext.map { it.title })
        assertEquals("Book · James Clear · 320 pages", v.upNext[0].meta)
        assertTrue(v.upNext[0].progress.startsWith("Not started"))
        assertEquals("2 up next", v.summary)
        assertNull(v.month)
        assertEquals(DailyLine("2 up next", false), ReadingRules.dailyLine(rMac.items()))

        val id = v.upNext[0].id
        val moved = rMac.progress(id, "120")
        assertEquals("Atomic Habits · Page 120 of 320 · 37%", moved.line)
        sync()
        v = rFold.view(cal)
        assertEquals(listOf("Atomic Habits"), v.onTheGo.map { it.title })
        assertEquals("Page 120 of 320 · 37%", v.onTheGo[0].progress)
        assertEquals(0.375f, v.onTheGo[0].fraction)
        assertEquals("1 on the go · 1 up next", v.summary)
        assertTrue(v.month!!.contains("1 moved on · next up: The Rest Is History"))
        assertEquals(DailyLine("Atomic Habits · 37%", false), ReadingRules.dailyLine(rFold.items()))
        assertTrue(rFold.progress(id, "huge").refused)

        assertEquals("Finished Atomic Habits", rFold.progress(id, "320").line)
        v = rFold.view(cal)
        assertEquals(listOf("Atomic Habits"), v.finished.map { it.title })
        assertTrue(v.summary.contains("finished 1 this year"))
        assertTrue(v.month!!.contains("finished 1"))
    }

    @Test
    fun putDownRemoveAndUndo() {
        rFold.add("reading Dune by Frank Herbert")
        val id = rFold.view(cal).onTheGo.single().id
        assertEquals("Started " + CivilDate.shortLabel(cal.epochDayOf(world.clock.nowMs)), rFold.view(cal).onTheGo.single().progress)
        val down = rFold.putDown(id)
        assertEquals("Put down Dune", down.line)
        assertEquals(listOf("Dune"), rFold.view(cal).putDown.map { it.title })
        assertTrue(rFold.undo(down.undo!!))
        assertEquals(listOf("Dune"), rFold.view(cal).onTheGo.map { it.title })

        val removed = rFold.remove(id)
        assertTrue(rFold.view(cal).isEmpty)
        assertTrue(rFold.remove(id).refused)
        assertTrue(rFold.undo(removed.undo!!))
        assertEquals(listOf("Dune"), rFold.view(cal).onTheGo.map { it.title })

        // Undo of an add takes it off again; only the last command can be undone.
        val added = rFold.add("course Spanish 20 lessons")
        val first = rFold.start(rFold.view(cal).upNext.single().id)
        assertFalse(rFold.undo(added.undo!!))
        assertTrue(rFold.undo(first.undo!!))
        assertEquals(listOf("Spanish"), rFold.view(cal).upNext.map { it.title })
        sync()
        assertEquals(listOf("Spanish"), rMac.view(cal).upNext.map { it.title })
        assertEquals(listOf("Dune"), rMac.view(cal).onTheGo.map { it.title })
    }

    @Test
    fun undoingAnAddTakesItOff() {
        val added = rFold.add("Atomic Habits")
        assertTrue(rFold.undo(added.undo!!))
        assertTrue(rFold.view(cal).isEmpty)
        sync()
        assertTrue(rMac.view(cal).isEmpty)
    }
}
