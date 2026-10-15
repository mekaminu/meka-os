package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The emergency card, slice 1: who to call, where things are and key accounts by name; never a secret or a number. */
class EmergencyCardTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun card(dev: Device) = EmergencyCard(dev.replica, { "em${n++}" }, { world.clock.nowMs })
    private val cFold = card(fold)
    private val cMac = card(mac)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun aLineIsReadAsANameAndItsDetails() {
        assertEquals(EmergencyLine("GP", "Dr Patel, 01632 123456"), EmergencyRules.read("GP: Dr Patel, 01632 123456"))
        assertEquals(EmergencyLine("Passports", "study desk, top drawer"), EmergencyRules.read(" passports - study desk, top drawer "))
        assertEquals(EmergencyLine("Mum", ""), EmergencyRules.read("mum"))
        assertNull(EmergencyRules.read("   "))
        assertNull(EmergencyRules.read(": nothing before it"))
        // A long line with no separator: the first words are the name.
        val long = EmergencyRules.read("The will and the house deeds are in the fireproof box under the stairs")!!
        assertTrue(long.title.length <= EmergencyRules.MAX_TITLE)
        assertEquals("The will and the house deeds are in the fireproof box under the stairs", "${long.title} ${long.detail}")
    }

    @Test
    fun secretsAndNumbersNeverGoOnTheCard() {
        val call = EmergencySection.CALL
        val docs = EmergencySection.DOCUMENTS
        val accounts = EmergencySection.ACCOUNTS
        // Phone numbers are fine under "Who to call", nowhere else.
        assertNull(EmergencyRules.refusal(call, "GP: Dr Patel, 01632 123456"))
        assertNull(EmergencyRules.refusal(call, "Tunde: +44 7700 900123"))
        assertNull(EmergencyRules.refusal(call, "Emergency: 999"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(docs, "GP: Dr Patel, 01632 123456"))
        // Card numbers, sort codes, account, policy and passport numbers: refused everywhere.
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(accounts, "Barclays card: 4929 1234 5678 9012"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(call, "Barclays card: 4929 1234 5678 9012"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(accounts, "Barclays: sort code 20-00-00"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(accounts, "Current account: 12345678"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(call, "Bank: account 12345678"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(docs, "Passport: 123456789, study desk"))
        assertEquals(EmergencyRules.REFUSED_NUMBER, EmergencyRules.refusal(accounts, "National Insurance: QQ123456C"))
        // Passwords and PINs: only where they're kept, never what they are.
        assertEquals(EmergencyRules.REFUSED_SECRET, EmergencyRules.refusal(accounts, "Bank PIN: 4821"))
        assertEquals(EmergencyRules.REFUSED_SECRET, EmergencyRules.refusal(accounts, "Wifi password: BlueFox77"))
        assertEquals(EmergencyRules.REFUSED_SECRET, EmergencyRules.refusal(accounts, "Email: the password is hunter2"))
        assertNull(EmergencyRules.refusal(docs, "Passwords: in the family password manager"))
        // Ordinary words and small numbers are fine.
        assertNull(EmergencyRules.refusal(accounts, "Home insurance: Aviva, policy in the blue folder"))
        assertNull(EmergencyRules.refusal(docs, "Bins: Tuesday, 2 green bins"))
        assertEquals("01632123456", EmergencyRules.phone("Dr Patel, 01632 123456"))
        assertEquals("+447700900123", EmergencyRules.phone("+44 7700 900123 (mobile)"))
        assertNull(EmergencyRules.phone("Dr Patel"))
    }

    @Test
    fun mekaAddsEditsAndRemovesLinesOnEitherApp() {
        assertEquals(EmergencyView.EMPTY.summary, cFold.view().summary)
        assertEquals(listOf("Who to call", "Where things are", "Key accounts"), cFold.view().groups.map { it.label })
        assertEquals(EmergencyAdded("Added GP to Who to call", false), cFold.add("call", "GP: Dr Patel, 01632 123456"))
        world.clock.nowMs += 1_000
        assertEquals(EmergencyAdded("Added Passports to Where things are", false), cFold.add("documents", "Passports: study desk, top drawer"))
        world.clock.nowMs += 1_000
        assertEquals(EmergencyAdded("Added Home insurance to Key accounts", false), cFold.add("accounts", "Home insurance: Aviva"))
        // Refused lines write nothing.
        assertEquals(EmergencyAdded(EmergencyRules.REFUSED_SECRET, true), cFold.add("accounts", "Bank PIN: 4821"))
        assertEquals(EmergencyAdded(EmergencyRules.REFUSED_NUMBER, true), cFold.add("accounts", "Barclays: 12345678"))
        assertEquals(EmergencyAdded(EmergencyRules.NOT_READ, true), cFold.add("call", "  "))
        assertEquals(EmergencyAdded(EmergencyRules.NOT_READ, true), cFold.add("elsewhere", "GP: Dr Patel"))
        assertEquals(3, cFold.view().count)

        sync()
        val v = cMac.view()
        assertEquals("3 lines · your partner sees it read-only on their family page", v.summary)
        val gp = v.groups[0].rows.single()
        assertEquals("GP", gp.title)
        assertEquals("Dr Patel, 01632 123456", gp.detail)
        assertEquals("01632123456", gp.phone)
        assertEquals("GP. Dr Patel, 01632 123456", gp.spoken)
        assertNull(v.groups[1].rows.single().phone) // only "Who to call" rings

        // The same name again in the same part updates its details; with none typed it's left alone.
        assertEquals(EmergencyAdded("Updated GP", false), cMac.add("call", "gp: Dr Shah, 01632 654321"))
        assertEquals(EmergencyAdded("GP is already on the card", false), cMac.add("call", "GP"))
        sync()
        assertEquals("Dr Shah, 01632 654321", cFold.view().groups[0].rows.single().detail)

        // Removed on one app, gone on both, for good.
        assertTrue(cFold.remove(gp.id))
        assertFalse(cFold.remove(gp.id))
        sync()
        assertTrue(cMac.view().groups[0].rows.isEmpty())
        assertEquals(2, cMac.view().count)
    }

    @Test
    fun aLineThatHoldsASecretIsNeverShownEvenIfWrittenDirectly() {
        // An older device or a hand-made op can't get a secret onto her page: the read drops it.
        fold.replica.commitLocal(EntityTypes.EMERGENCY_ENTRY, "x1", mapOf(
            EmergencyFields.SECTION to os.meka.core.sync.FieldValue.Text("accounts"),
            EmergencyFields.TITLE to os.meka.core.sync.FieldValue.Text("Bank PIN"),
            EmergencyFields.DETAIL to os.meka.core.sync.FieldValue.Text("4821"),
        ))
        assertEquals(0, cFold.view().count)
        val her = EmergencyRules.view(emptyList(), viewer = "jordan")
        assertEquals("Meka hasn't filled the emergency card in yet.", her.summary)
    }
}
