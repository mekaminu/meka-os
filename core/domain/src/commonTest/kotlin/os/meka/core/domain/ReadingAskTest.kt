package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading and learning, slice 2: Ask and Talk know the reading list, and change it at his word. */
class ReadingAskTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val cal = LocalCalendar.fixedOffset(0L)
    private val reading = Reading(fold.replica, { "b${n++}" }, { world.clock.nowMs })

    private fun resolve(q: String): ReadingAskResult? =
        ReadingAskRules.request(q)?.let { ReadingAskRules.resolve(it, reading.items(), world.clock.nowMs, cal) }

    private fun did(q: String): AskProposal.ReadingUpdate = assertIs<ReadingAskResult.Do>(resolve(q)).proposal

    private fun said(q: String): String = assertIs<ReadingAskResult.Answer>(resolve(q)).text

    @Test
    fun theQuestionsAreReadHoweverTheyAreSaid() {
        listOf("What am I reading?", "hey Navilife, what am I reading at the moment", "what's on my reading list",
            "my reading list", "what should I read next?", "What books am I reading").forEach {
            assertEquals(ReadingAsk.What(null), ReadingAskRules.request(it), it)
        }
        assertEquals(ReadingAsk.What("Dune"), ReadingAskRules.request("How far am I through Dune?"))
        assertEquals(ReadingAsk.Progress(null, "page 200"), ReadingAskRules.request("I'm on page 200"))
        assertEquals(ReadingAsk.Progress("Atomic Habits", "page 200"), ReadingAskRules.request("I'm on page 200 of Atomic Habits"))
        assertEquals(ReadingAsk.Progress(null, "page 200 of 320"), ReadingAskRules.request("I've got to page 200 of 320."))
        assertEquals(ReadingAsk.Progress("The Rest Is History", "episode 5"), ReadingAskRules.request("on episode 5 of The Rest Is History"))
        assertEquals(ReadingAsk.Progress("Dune", "45%"), ReadingAskRules.request("I'm 45% through Dune"))
        assertEquals(ReadingAsk.Progress("Dune", "50%"), ReadingAskRules.request("I'm halfway through Dune"))
        assertEquals(ReadingAsk.Progress(null, "50%", vague = true), ReadingAskRules.request("I'm halfway through it"))
        assertEquals(ReadingAsk.Finish("Atomic Habits"), ReadingAskRules.request("I've finished Atomic Habits!"))
        assertEquals(ReadingAsk.Finish(null), ReadingAskRules.request("I've just finished the book"))
        assertEquals(ReadingAsk.Finish(null, vague = true), ReadingAskRules.request("I finished it"))
        assertEquals(ReadingAsk.Add("Dune by Frank Herbert"), ReadingAskRules.request("Add Dune by Frank Herbert to my reading list"))
        assertEquals(ReadingAsk.Add("podcast Hard Fork"), ReadingAskRules.request("add the podcast Hard Fork"))
        assertEquals(ReadingAsk.Add("Dune"), ReadingAskRules.request("I want to read Dune"))
        assertEquals(ReadingAsk.Start("Dune", reading = true), ReadingAskRules.request("I've started reading Dune"))
        assertEquals(ReadingAsk.Start("Dune", reading = false), ReadingAskRules.request("I've started Dune"))
        // Quality gate slice 28: "book" as a noun stays Reading's...
        assertEquals(ReadingAsk.Add("book The Hobbit"), ReadingAskRules.request("add book The Hobbit"))
        assertEquals(ReadingAsk.Add("book Dune"), ReadingAskRules.request("add the book Dune"))
        assertEquals(ReadingAsk.Add("book a day in the life"), ReadingAskRules.request("add book a day in the life to my reading list"))
        // ...and as the verb it's a task, for the plain adds.
        listOf(
            "add book the boiler service on friday", "add book a table for Saturday", "add book my MOT",
            "add book in the car service tomorrow at 9", "add book the dentist next Tuesday",
        ).forEach {
            assertNull(ReadingAskRules.request(it), it)
        }
        // Not about reading: the other rules' and the AI's.
        listOf("add milk to the shopping", "what's the weather", "remind me to read the contract", "I'm reading it", "page me").forEach {
            assertNull(ReadingAskRules.request(it), it)
        }
    }

    @Test
    fun theListIsAnsweredOnTheDevice() {
        assertEquals(ReadingAskRules.EMPTY, said("what am I reading?"))
        reading.add("Atomic Habits by James Clear, 320 pages")
        reading.add("Dune")
        reading.add("podcast The Rest Is History")
        val habits = reading.items().first { it.title == "Atomic Habits" }.id
        reading.start(habits)
        reading.progress(habits, "120")
        assertEquals("On the go: Atomic Habits, page 120 of 320, 37%. Up next: Dune and The Rest Is History.", said("What am I reading?"))
        assertEquals("Atomic Habits by James Clear: page 120 of 320, 37%.", said("how far am I through atomic habits"))
        // Nothing on his list by that name: the AI's.
        assertNull(resolve("how far am I through the week"))
    }

    @Test
    fun progressGoesToTheOneOnTheGoOrTheOneNamed() {
        assertEquals(ReadingAskRules.EMPTY, said("I'm on page 200"))
        reading.add("Atomic Habits by James Clear, 320 pages")
        reading.add("podcast The Rest Is History")
        assertEquals(ReadingAskRules.NOTHING_ON, said("I'm on page 200"))
        val habits = reading.items().first { it.title == "Atomic Habits" }.id
        reading.start(habits)
        val p = did("I'm on page 200")
        assertEquals(ReadingChange.PROGRESS, p.change)
        assertEquals(habits, p.itemId)
        assertEquals("Atomic Habits · Page 200 of 320 · 62%", p.line)
        assertEquals("noted page 200 of 320, 62% in Atomic Habits", ReadingAskRules.phrase(p, past = true))
        // Named, though up next: it starts with the episode.
        val pod = did("I'm on episode 5 of the rest is history")
        assertEquals("The Rest Is History · Episode 5", pod.line)
        // Past the end, and the last page finishes it.
        assertEquals("That's past the end of Atomic Habits (320 pages).", said("I'm on page 400"))
        assertEquals(ReadingChange.FINISH, did("I've got to page 320").change)
        // Two on the go: a page is a book's, a percent asks which.
        reading.start(pod.itemId ?: reading.items().first { it.kind == ReadingKind.PODCAST }.id)
        assertEquals(habits, did("I'm on page 210").itemId)
        assertTrue(said("I'm 45% through").startsWith("Which one?"))
        // "It" with two on the go is too vague: the AI's.
        assertNull(resolve("I finished it"))
    }

    @Test
    fun finishingStartingAndAddingAtHisWord() {
        reading.add("Dune by Frank Herbert")
        assertTrue(said("add Dune to my reading list").startsWith("Dune is already on your reading list, not started"))
        val start = did("I've started Dune")
        assertEquals(ReadingChange.START, start.change)
        assertEquals("On the go: Dune", start.line)
        assertTrue(reading.start(start.itemId!!).undo != null)
        assertTrue(said("I'm reading Dune").startsWith("Dune is already on the go"))
        val fin = did("I finished it")
        assertEquals(ReadingChange.FINISH, fin.change)
        assertEquals("Finished Dune", fin.line)
        assertEquals("Mark Dune finished", AskRules.cardOf(fin, 0).line)
        val add = did("I've started reading Project Hail Mary by Andy Weir")
        assertEquals(ReadingChange.ADD, add.change)
        assertEquals("Added book Project Hail Mary by Andy Weir · on the go", add.line)
        assertEquals("Added book Project Hail Mary by Andy Weir · on the go", reading.add(add.text).line)
        // Started something that isn't on the list, without "reading": not his reading.
        assertNull(resolve("I've started the washing"))
        assertNull(resolve("I've finished work"))
    }
}
