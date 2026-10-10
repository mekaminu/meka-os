package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What MEKA knows (facts page, slice 1): typed facts, their groups, what's refused and what goes with a question. */
class LifeFactsTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun facts(dev: Device) = LifeFacts(dev.replica, { "f${n++}" }, { world.clock.nowMs })
    private val fFold = facts(fold)
    private val fMac = facts(mac)
    private val cal = LocalCalendar.fixedOffset(3_600_000L)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun aLineIsReadAsANameAndAFact() {
        assertEquals(FactLine("Car reg", "AB12 CDE"), FactRules.read("Car reg: AB12 CDE"))
        assertEquals(FactLine("Shoe size", "10"), FactRules.read("my shoe size is 10"))
        assertEquals(FactLine("Rex's school", "Biggleswade Academy"), FactRules.read("Rex's school is Biggleswade Academy"))
        assertEquals(FactLine("Energy supplier", "Octopus"), FactRules.read("energy supplier - Octopus"))
        assertNull(FactRules.read("AB12 CDE"))
        assertNull(FactRules.read(": nothing before it"))
        assertNull(FactRules.read("Car reg:"))
        assertNull(FactRules.read("   "))
    }

    @Test
    fun aFactsNameChoosesItsGroup() {
        assertEquals(FactGroup.CAR, FactRules.groupOf("Car reg"))
        assertEquals(FactGroup.CAR, FactRules.groupOf("Car insurer"))
        assertEquals(FactGroup.SIZES, FactRules.groupOf("Rex shoe size"))
        assertEquals(FactGroup.HEALTH, FactRules.groupOf("GP"))
        assertEquals(FactGroup.HOME, FactRules.groupOf("Energy supplier"))
        assertEquals(FactGroup.FAMILY, FactRules.groupOf("Rex's school"))
        assertEquals(FactGroup.OTHER, FactRules.groupOf("Favourite takeaway"))
    }

    @Test
    fun secretsCardNumbersAndIdNumbersNeverGoOnThePage() {
        assertEquals(FactRules.REFUSED_SECRET, FactRules.refusal("Wifi password: BlueFox77"))
        assertNull(FactRules.refusal("Passwords: in the family password manager"))
        assertEquals(FactRules.REFUSED_BANK, FactRules.refusal("Bank card: 4111 1111 1111 1111"))
        assertEquals(FactRules.REFUSED_BANK, FactRules.refusal("Sort code: 20-00-00"))
        assertEquals(FactRules.REFUSED_BANK, FactRules.refusal("Bank account number: 12345678"))
        assertEquals(FactRules.REFUSED_ID, FactRules.refusal("Passport: 123456789"))
        assertEquals(FactRules.REFUSED_ID, FactRules.refusal("NI: AB 12 34 56 C"))
        assertNull(FactRules.refusal("Passports: study desk, top drawer"))
        // Fine: a phone number, a policy reference, a reg plate, a size.
        assertNull(FactRules.refusal("GP: Dr Patel, 01767 123456"))
        assertNull(FactRules.refusal("Car insurer: Admiral, policy 87654321"))
        assertNull(FactRules.refusal("Car reg: AB12 CDE"))
        assertNull(FactRules.refusal("Shoe size: 10"))
    }

    @Test
    fun factsTypedOnTheFoldReachTheMacAndTheSameNameChangesIt() {
        assertEquals(FactAdded("Added Car reg to Car", false), fFold.add("Car reg: AB12 CDE"))
        world.clock.advance(1_000)
        assertEquals(FactAdded("Added Shoe size to Sizes", false), fFold.add("Shoe size: 10"))
        assertEquals(FactAdded(FactRules.REFUSED_SECRET, true), fFold.add("Bank PIN: 4821"))
        assertEquals(FactAdded(FactRules.NOT_READ, true), fFold.add("just words"))
        sync()
        val v = fMac.view()
        assertEquals("2 facts · MEKA answers from them", v.summary)
        assertEquals(listOf("Sizes", "Car"), v.groups.map { it.label })
        assertEquals(FactAdded("Updated Car reg", false), fMac.add("car reg: CD34 EFG"))
        assertEquals(FactAdded("Shoe size is already on the page", false), fMac.add("Shoe size: 10"))
        val car = fMac.all().first { it.label == "Car reg" }
        assertTrue(fMac.setAi(car.id, use = false))
        sync()
        val fv = fFold.view()
        val row = fv.groups.first { it.group == "car" }.rows.single()
        assertEquals("CD34 EFG", row.value)
        assertEquals(FactRules.KEPT_LINE, row.aiLine)
        assertEquals(FactRules.USE_BUTTON, row.aiButton)
        assertEquals("2 facts · MEKA answers from 1 · 1 kept from AI", fv.summary)
        assertTrue(fFold.remove(row.id))
        sync()
        assertEquals(1, fMac.view().count)
        assertEquals(FactsView.EMPTY, FactRules.view(emptyList()))
    }

    @Test
    fun onlyFactsThatBearOnTheQuestionGoWithIt() {
        fun f(label: String, value: String, aiOff: Boolean = false) =
            LifeFact(label, label, value, FactRules.groupOf(label), aiOff, 0L)
        val all = listOf(f("Car reg", "AB12 CDE"), f("Rex shoe size", "13"), f("Energy supplier", "Octopus"), f("Bin day", "Tuesday", aiOff = true))
        assertEquals(listOf("Facts · 3 kept · 1 bears on the question", "Fact · Car · Car reg: AB12 CDE"),
            FactAskRules.askLines(all, "what's the car's reg?"))
        // A group's word ties the question to every fact in it ("shoes" → Sizes).
        assertEquals(listOf("Rex shoe size"), FactAskRules.relevant(all, "What size shoes does Rex take?").map { it.label })
        assertEquals(listOf("Energy supplier"), FactAskRules.relevant(all, "who's our gas supplier").map { it.label })
        // Kept from AI never goes, even when asked directly.
        assertTrue(FactAskRules.relevant(all, "when is bin day?").none { it.label == "Bin day" })
        assertEquals(listOf("Facts · 3 kept · none bear on this question"), FactAskRules.askLines(all, "will it rain?"))
        assertEquals(4, FactAskRules.askLines(all, "What do you know about me?").size)
        assertEquals(emptyList(), FactAskRules.askLines(emptyList(), "what's the car's reg?"))
    }

    @Test
    fun rememberThatIsACardForANewOrChangedFactOnlyAndTheTapChecksAgain() {
        fFold.add("Shoe size: 10")
        world.clock.nowMs += 1_000
        fFold.add("GP: Dr Patel")
        val all = fFold.all()
        val gp = all.first { it.label == "GP" }
        fFold.setAi(gp.id, use = false)

        // A new fact: Keep. The card reads as the page would.
        val ctx = AskContext.EMPTY
        fun card(title: String?) = AskRules.card(AskRawAction("add_fact", title = title), ctx, emptyMap(), world.clock.nowMs, cal, facts = fFold.all())
        val car = card("my car reg is AB12 CDE")!!
        assertEquals(AskProposal.AddFact("Car reg", "AB12 CDE", null), car.proposal)
        assertEquals("Keep on What MEKA knows · Car reg: AB12 CDE", car.line)
        assertEquals("Keep", car.button)
        assertEquals("Kept Car reg: AB12 CDE on What MEKA knows", AskRules.doneLine(car.proposal, 0))
        assertEquals("remember Car reg as AB12 CDE", TalkRules.phrase(car.proposal, 0, past = false))
        // The same name again changes it (even one kept from the AI); the card never shows the old value.
        val newGp = card("gp: Dr Shah")!!
        assertEquals(AskProposal.AddFact("GP", "Dr Shah", gp.id), newGp.proposal)
        assertEquals("Update on What MEKA knows · GP: Dr Shah", newGp.line)
        assertEquals("Update", newGp.button)
        assertEquals("Updated GP on What MEKA knows", AskRules.doneLine(newGp.proposal, 0))
        assertEquals("changed GP to Dr Shah", TalkRules.phrase(newGp.proposal, 0, past = true))
        // Already there, unreadable, or a secret or number the page turns away: no card.
        assertNull(card("Shoe size: 10"))
        assertNull(card("shoe size 10"))
        assertNull(card("Wifi password: hunter22"))
        assertNull(card("Bank card: 4111 1111 1111 1111"))
        assertNull(card("Passport number: 123456789"))
        assertNull(card(null))
        // Never runs on its own under earned trust.
        assertNull(TrustRules.kindOf(car.proposal))

        // The tap: added, then taken off; changed, then put back only while it still reads the new value.
        val (carId, before) = fFold.keep("Car reg", "AB12 CDE", null)
        assertNull(before)
        assertEquals(FactGroup.CAR, fFold.all().first { it.id == carId }.group)
        assertFailsWith<ValidationException> { fFold.keep("Car reg", "AB12 CDE", null) }
        assertFailsWith<ValidationException> { fFold.keep("Wifi password", "hunter22", null) }
        val (gpId, old) = fFold.keep("GP", "Dr Shah", gp.id)
        assertEquals(gp.id to "Dr Patel", gpId to old)
        assertTrue(fFold.all().first { it.id == gp.id }.aiOff) // its AI choice stays
        sync()
        assertEquals("Dr Shah", fMac.all().first { it.id == gp.id }.value)
        assertTrue(fMac.putBack(gp.id, "Dr Patel", "Dr Shah"))
        assertFalse(fMac.putBack(gp.id, "Dr Patel", "Dr Shah"))
        assertTrue(fMac.remove(carId))
        sync()
        assertEquals(listOf("Shoe size: 10", "GP: Dr Patel"), fFold.all().map { "${it.label}: ${it.value}" })
    }
}
