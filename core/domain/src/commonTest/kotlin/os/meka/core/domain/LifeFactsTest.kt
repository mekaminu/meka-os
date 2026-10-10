package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
