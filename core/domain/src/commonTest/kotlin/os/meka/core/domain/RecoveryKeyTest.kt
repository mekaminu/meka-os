package os.meka.core.domain

import os.meka.core.sync.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recovery key's words (build plan V1 "Recovery key"): BIP-39 phrases, reading typed words back, the status row. */
class RecoveryKeyTest {
    private fun bytes(b: Int) = ByteArray(RecoveryKeyRules.ENTROPY_BYTES) { b.toByte() }

    @Test
    fun theWordListIsBip39sEnglishList() {
        val w = RecoveryWordList.WORDS
        assertEquals(2048, w.size)
        assertEquals("abandon", w.first())
        assertEquals("zoo", w.last())
        assertEquals(2048, w.toSet().size)
        assertEquals(2048, w.map { it.take(4) }.toSet().size)
        assertTrue(w.all { it.length in 3..8 && it.all { c -> c in 'a'..'z' } })
    }

    @Test
    fun phrasesMatchTheBip39TestVectors() {
        assertEquals(List(23) { "abandon" } + "art", RecoveryKeyRules.phrase(bytes(0)))
        assertEquals(List(23) { "zoo" } + "vote", RecoveryKeyRules.phrase(bytes(0xff)))
        val legal = "legal winner thank year wave sausage worth useful"
        assertEquals("$legal $legal $legal".split(' ').dropLast(1) + "title", RecoveryKeyRules.phrase(bytes(0x7f)))
    }

    @Test
    fun aTypedPhraseReadsBackToTheSameBitsHoweverItIsTyped() {
        val entropy = ByteArray(32) { (it * 37 + 11).toByte() }
        val words = RecoveryKeyRules.phrase(entropy)
        val ok = RecoveryKeyRules.read(words.joinToString(" ")) as RecoveryKeyRules.Reading.Ok
        assertTrue(ok.entropy.contentEquals(entropy))
        // Numbered, upper case, commas and new lines, and four letters a word.
        val messy = words.mapIndexed { i, w -> "${i + 1}. ${if (i % 2 == 0) w.uppercase() else w.take(4)}" }.chunked(4)
            .joinToString("\n") { it.joinToString(", ") }
        val again = RecoveryKeyRules.read(messy) as RecoveryKeyRules.Reading.Ok
        assertTrue(again.entropy.contentEquals(entropy))
        assertNull(RecoveryKeyRules.readingLine(again))
    }

    @Test
    fun mistakesAreCaughtOnTheDevice() {
        val words = RecoveryKeyRules.phrase(ByteArray(32) { (it * 5).toByte() })
        assertEquals(RecoveryKeyRules.Reading.Count(23), RecoveryKeyRules.read(words.drop(1).joinToString(" ")))
        assertEquals("23 of 24 words", RecoveryKeyRules.readingLine(RecoveryKeyRules.read(words.drop(1).joinToString(" "))))
        assertEquals("Type the 24 words", RecoveryKeyRules.readingLine(RecoveryKeyRules.read("")))
        val unknown = words.toMutableList().apply { this[2] = "zzzz"; this[9] = "ab" }
        assertEquals(RecoveryKeyRules.Reading.Unknown(listOf(3, 10)), RecoveryKeyRules.read(unknown.joinToString(" ")))
        assertEquals("Word 3, 10 aren't one of the key's words", RecoveryKeyRules.readingLine(RecoveryKeyRules.read(unknown.joinToString(" "))))
        // A real word in the wrong place fails the checksum.
        val swapped = words.toMutableList().apply { val t = this[0]; this[0] = this[1]; this[1] = t }
        if (swapped != words) assertEquals(RecoveryKeyRules.Reading.Mistyped, RecoveryKeyRules.read(swapped.joinToString(" ")))
        val wrong = words.toMutableList().apply { this[5] = if (this[5] == "abandon") "ability" else "abandon" }
        assertEquals(RecoveryKeyRules.Reading.Mistyped, RecoveryKeyRules.read(wrong.joinToString(" ")))
        // Three letters only stand for a word that is exactly them.
        assertEquals("act", RecoveryKeyRules.wordOf("act"))
        assertNull(RecoveryKeyRules.wordOf("aba"))
        assertEquals("abandon", RecoveryKeyRules.wordOf("aban"))
        // The Mac's Connect sheet reads the same lines from the text alone.
        assertEquals("23 of 24 words", RecoveryKeyRules.typedLine(words.drop(1).joinToString(" ")))
        assertEquals("Type the 24 words", RecoveryKeyRules.typedLine(" "))
        assertFalse(RecoveryKeyRules.readable(wrong.joinToString(" ")))
        assertTrue(RecoveryKeyRules.readable(words.joinToString("\n")))
        assertNull(RecoveryKeyRules.typedLine(words.joinToString(" ")))
    }

    @Test
    fun onlyTheVerifierLeavesWhenMakingAndTheServerChecksTheSecretAgainstIt() {
        val e = bytes(3)
        val secret = RecoveryKeyRules.secret(e)
        assertEquals(64, secret.length)
        assertEquals(Sha256.hexOf(secret), RecoveryKeyRules.verifier(e))
        assertFalse(RecoveryKeyRules.verifier(e) == RecoveryKeyRules.verifier(bytes(4)))
        // Neither is the bits themselves.
        val hex = e.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertFalse(secret == hex || RecoveryKeyRules.verifier(e) == hex)
    }

    @Test
    fun confirmingAsksForThreeDifferentWords() {
        val words = RecoveryKeyRules.phrase(bytes(9))
        val pos = RecoveryKeyRules.confirmPositions { 0 }
        assertEquals(listOf(0, 1, 2), pos)
        val spread = RecoveryKeyRules.confirmPositions { n -> n - 1 }
        assertEquals(listOf(21, 22, 23), spread)
        assertTrue(RecoveryKeyRules.confirms(words, spread, spread.map { words[it] }))
        assertTrue(RecoveryKeyRules.confirms(words, spread, spread.map { " " + words[it].take(4).uppercase() }))
        assertFalse(RecoveryKeyRules.confirms(words, spread, listOf(words[21], words[22], "abandon").let { if (words[23] == "abandon") listOf(words[21], words[22], "zoo") else it }))
        assertFalse(RecoveryKeyRules.confirms(words, spread, spread.take(2).map { words[it] }))
        assertEquals(6, RecoveryKeyRules.rows(words).size)
        assertEquals("1 ${words[0]}", RecoveryKeyRules.rows(words)[0][0])
        assertEquals("24 ${words[23]}", RecoveryKeyRules.rows(words)[5][3])
    }

    @Test
    fun theSettingsRowSaysWhetherThereIsAKey() {
        val cal = LocalCalendar.UTC
        val now = 1_791_800_000_000L
        val none = RecoveryKeyRules.view(RecoveryKeyRules.Status(RecoveryKeyRules.Status.NONE), now, cal)
        assertTrue(none.lit)
        assertEquals("Make a recovery key", none.action)
        val ready = RecoveryKeyRules.view(RecoveryKeyRules.Status(RecoveryKeyRules.Status.READY, createdAtMs = now - 1_000, createdBy = "Fold"), now, cal)
        assertEquals("Made today on the Fold · gets you back in if you lose both devices", ready.line)
        assertFalse(ready.lit)
        assertEquals("Make a new one", ready.action)
        val used = RecoveryKeyRules.view(RecoveryKeyRules.Status(RecoveryKeyRules.Status.USED, now - 86_400_000, "Fold", now - 86_400_000, "Mac"), now, cal)
        assertTrue(used.line.startsWith("Used yesterday to connect the Mac"))
        assertTrue(used.lit)
        assertFalse(RecoveryKeyRules.view(null, now, cal).lit)
        assertEquals("The old key stops working once the new one is saved", RecoveryKeyRules.replaceWarning(RecoveryKeyRules.Status(RecoveryKeyRules.Status.READY)))
        assertNull(RecoveryKeyRules.replaceWarning(RecoveryKeyRules.Status(RecoveryKeyRules.Status.USED)))
        assertTrue(RecoveryKeyRules.refusal("key").startsWith("That recovery key doesn't open MEKA"))
        assertEquals("MEKA's server refused it", RecoveryKeyRules.refusal(null))
    }

    @Test
    fun aDraftShowsTheWordsAndConfirmsThreeOfThem() {
        val entropy = ByteArray(32) { (it * 7).toByte() }
        val d = RecoveryKeyRules.draft(entropy) { 0 }
        assertEquals(RecoveryKeyRules.phrase(entropy), d.words)
        assertEquals(listOf(0, 1, 2), d.confirmPositions)
        assertEquals(listOf("Word 1", "Word 2", "Word 3"), d.confirmLabels)
        assertEquals("1 ${d.words[0]}", d.rows[0][0])
        assertEquals(RecoveryKeyRules.verifier(entropy), d.verifier)
        assertTrue(RecoveryKeyRules.confirms(d, d.words.take(3)))
        assertFalse(RecoveryKeyRules.confirms(d, d.words.drop(1).take(3)))
        assertFalse(RecoveryKeyRules.confirms(d, emptyList()))
        val cal = LocalCalendar.UTC
        val made = RecoveryKeyRules.pane(RecoveryKeyRules.Status(RecoveryKeyRules.Status.READY, createdAtMs = 0), 0, cal, null)
        assertEquals("The old key stops working once the new one is saved", made.warning)
        assertEquals(RecoveryKeyRules.OFFLINE, RecoveryKeyRules.pane(null, 0, cal, RecoveryKeyRules.OFFLINE).problem)
    }
}
