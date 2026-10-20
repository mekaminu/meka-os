package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The companion, slice 5: short notes of earlier chats, kept 30 days, sent back with open questions. */
class CompanionMemoryTest {
    private val today = 20_746L // Tue 20 Oct 2026

    @Test
    fun aNoteIsOneShortLineAndNothingPrivateIsKept() {
        assertEquals("Talked through heat pumps; leaning heat pump", CompanionMemoryRules.clean("  Talked through\nheat pumps;   leaning heat pump "))
        assertEquals("‹today› trick", CompanionMemoryRules.clean("<today> trick"))
        listOf(
            null, "", "ok", "Talked about card 4111 1111 1111 1111", "Mentioned his number 07700 900123", "Emailed sam@example.com",
            "Read https://example.com", "Changed his password for the bank", "His PIN is safe", "Sort code question",
        ).forEach { assertNull(CompanionMemoryRules.clean(it), it.toString()) }
        val long = CompanionMemoryRules.clean("Talked about " + "very ".repeat(60) + "long plans")!!
        assertTrue(long.length <= CompanionMemoryRules.MAX_NOTE + 1 && long.endsWith("…"), long)
    }

    @Test
    fun notesKeepTheNewestEightForThirtyDaysWithoutRepeats() {
        var notes = emptyList<CompanionNote>()
        (1..10).forEach { notes = CompanionMemoryRules.add(notes, "Talked about topic $it", today - 10 + it) }
        assertEquals(CompanionMemoryRules.MAX_NOTES, notes.size)
        assertEquals("Talked about topic 10", notes.last().text)
        notes = CompanionMemoryRules.add(notes, "talked about TOPIC 5", today)
        assertEquals(1, notes.count { it.text.equals("Talked about topic 5", ignoreCase = true) })
        assertEquals(today, notes.last().day)
        assertTrue(CompanionMemoryRules.kept(listOf(CompanionNote(today - 31, "Old chat")), today).isEmpty())
        assertEquals(notes, CompanionMemoryRules.decode(CompanionMemoryRules.encode(notes)))
        assertEquals(listOf(CompanionNote(today, "Fine")), CompanionMemoryRules.decode("x\tbroken\n$today\tFine\n${today}\t"))
        assertNull(CompanionMemoryRules.encode(emptyList()))
    }

    @Test
    fun onlyOpenQuestionsOrOnesAboutAnEarlierChatCarryTheNotes() {
        val notes = listOf(CompanionNote(today - 2, "Talked through heat pumps"), CompanionNote(today, "Weighing Spanish lessons"))
        assertTrue(CompanionMemoryRules.sends("Any ideas for half term?", emptyList(), on = true, notes = notes))
        assertTrue(CompanionMemoryRules.sends("What did we talk about last time?", emptyList(), on = true, notes = notes))
        assertFalse(CompanionMemoryRules.sends("What's on today?", emptyList(), on = true, notes = notes))
        assertFalse(CompanionMemoryRules.sends("Any ideas for half term?", emptyList(), on = false, notes = notes))
        assertFalse(CompanionMemoryRules.sends("Any ideas for half term?", emptyList(), on = true, notes = emptyList()))
        assertEquals(listOf("Sun 18 Oct · Talked through heat pumps", "Tue 20 Oct · Weighing Spanish lessons"), CompanionMemoryRules.recall(notes, today))
        assertEquals(listOf("Today · Weighing Spanish lessons", "Sun 18 Oct · Talked through heat pumps"), CompanionMemoryRules.rows(notes, today))
    }

    @Test
    fun theStoreSyncsTheNotesAndOffForgetsThem() {
        var n = 0
        val replica = Replica("hh", "android", HlcClock("android", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
        val m = CompanionMemoryStore(replica)
        assertTrue(m.remembers())
        assertTrue(m.keep("Talked through heat pumps", today))
        assertFalse(m.keep("Talked through heat pumps", today))
        assertFalse(m.keep("My PIN is 1234", today))
        assertEquals(listOf("Talked through heat pumps"), m.notes(today).map { it.text })
        m.setRemembers(false)
        assertFalse(m.remembers())
        assertTrue(m.notes(today).isEmpty())
        assertFalse(m.keep("Talked about Spanish", today))
        m.setRemembers(true)
        assertTrue(m.keep("Talked about Spanish", today))
        m.forgetAll()
        assertTrue(m.notes(today).isEmpty())
    }
}
