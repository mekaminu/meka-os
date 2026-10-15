package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What MEKA knows, slice 4: a shared form's fields answered from the facts page, on the device only. */
class FormFillTest {
    private var n = 0
    private fun fact(label: String, value: String, aiOff: Boolean = false) =
        LifeFact("f${n++}", label, value, FactRules.groupOf(label), aiOff, n.toLong())

    private val facts = listOf(
        fact("Car reg", "AB12 CDE"),
        fact("GP", "Dr Shah"),
        fact("GP surgery", "Ivel Medical Centre"),
        fact("Home address", "1 Station Road, Oakfield AB1 2CD"),
        fact("Shoe size", "10"),
        fact("Shirt size", "M"),
        fact("Energy supplier", "Octopus", aiOff = true),
    )

    @Test
    fun aFormsLinesBecomeItsFields() {
        val text = """
            Dear parent,
            1. Vehicle registration: ________
            • Name of doctor's surgery …………
            Shoe size? [ ]
            Vehicle registration: ____
            Please read the whole of this letter carefully before you sign it and return it to the school office by Friday morning.
            ____
        """.trimIndent()
        assertEquals(listOf("Dear parent", "Vehicle registration", "Name of doctor's surgery", "Shoe size"), FormFillRules.fields(text))
    }

    @Test
    fun aFieldGetsTheFactWhoseWordsItHolds() {
        assertEquals("Car reg", FormFillRules.match("Vehicle registration number", facts)?.label)
        // The fact with more of the field's words wins: "GP surgery" over "GP".
        assertEquals("GP surgery", FormFillRules.match("Name of doctor's surgery", facts)?.label)
        assertEquals("GP", FormFillRules.match("Your GP", facts)?.label)
        // A field's words all in one fact's name: "Address" is the home address.
        assertEquals("Home address", FormFillRules.match("Address", facts)?.label)
        // Two facts would answer "Size": no answer rather than a guess.
        assertNull(FormFillRules.match("Size", facts))
        // Words like "name" or "please" never tie a field to a fact.
        assertNull(FormFillRules.match("Full name", facts))
        assertNull(FormFillRules.match("Dear parent", facts))
        assertNull(FormFillRules.match("Signature", facts))
    }

    @Test
    fun answersCome_inTheFormsOrder_eachFactOnce_andNeverLeaveTheDevice() {
        val text = "Vehicle registration: ____\nName of doctor's surgery: ____\nEnergy supplier: ____\nCar reg (again): ____\nSignature: ____"
        val v = FormFillRules.answers(text, "Parking permit form", facts)
        assertEquals(listOf("Vehicle registration", "Name of doctor's surgery", "Energy supplier"), v.answers.map { it.field })
        assertEquals(listOf("AB12 CDE", "Ivel Medical Centre", "Octopus"), v.answers.map { it.copyText })
        // A fact kept from the AI still answers: this never goes to the AI or the server.
        assertEquals("Energy supplier", v.answers[2].label)
        assertEquals("3 answers from What Navilife knows · Copy, then paste into the form", v.line)
        assertEquals("Vehicle registration: AB12 CDE\nName of doctor's surgery: Ivel Medical Centre\nEnergy supplier: Octopus", v.copyAllText)
        assertEquals("Copy Car reg for Vehicle registration", v.answers[0].copySpoken)
        assertEquals("Vehicle registration: AB12 CDE, from Car reg", v.answers[0].spoken)
        assertEquals("Copied Car reg · AB12 CDE", FormFillRules.copiedLine(v.answers[0]))
        assertEquals("Copied 3 answers · paste them where they go", FormFillRules.copiedAllLine(v))
    }

    @Test
    fun nothingToAnswerIsEmpty() {
        assertTrue(FormFillRules.answers("Vehicle registration: ____", null, emptyList()).answers.isEmpty())
        assertTrue(FormFillRules.answers("Hi Meka, are you free on Thursday for a quick call?", null, facts).answers.isEmpty())
        val long = fact("Notes", "x".repeat(60))
        val one = FormFillRules.answers("Notes:", null, listOf(long))
        assertEquals("1 answer from What Navilife knows · Copy, then paste into the form", one.line)
        assertEquals("Copied Notes · " + "x".repeat(40) + "…", FormFillRules.copiedLine(one.answers.single()))
        // At most eight answers.
        val many = (1..10).map { fact("Thing$it", "v$it") }
        assertEquals(FormFillRules.MAX_ANSWERS, FormFillRules.answers(many.joinToString("\n") { it.label + ":" }, null, many).answers.size)
    }
}
