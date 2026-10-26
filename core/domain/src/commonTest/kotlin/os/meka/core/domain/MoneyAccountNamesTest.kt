package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money bugs (A2): an account's name never shows its whole number. Invented numbers. */
class MoneyAccountNamesTest {
    @Test
    fun anIbanBecomesTheBanksNameAndLastFour() {
        assertEquals("Revolut ••9180", MoneyAccountNames.shown("Revolut GB33REVO00996912349180"))
        assertEquals("Revolut ••9180", MoneyAccountNames.shown("Revolut GB33 REVO 0099 6912 3491 80"))
        assertEquals("Revolut ••9180", MoneyBank.name("GB33REVO00996912349180", "Revolut"))
    }

    @Test
    fun accountNumbersAndSortCodesAreMasked() {
        assertEquals("Oakbank Current ••5678", MoneyAccountNames.shown("Oakbank Current 12-34-56 12345678"))
        assertEquals("Oakbank Current ••5678", MoneyAccountNames.shown("Oakbank Current 12345678"))
        assertEquals("Amex ••1005", MoneyAccountNames.shown("Amex ****1005"))
        assertEquals("Card ••4321", MoneyAccountNames.shown("Card xxxx-xxxx-xxxx-4321"))
        assertEquals("Card ••4321", MoneyAccountNames.shown("Card 4000 1234 5678 4321"))
    }

    @Test
    fun ordinaryNamesStayAsTheyAre() {
        for (n in listOf("Current account", "HSBC Credit card", "Revolut ••9180", "Saver 2026", "ISA", "Card ending 4321", "Box 360")) {
            assertEquals(n, MoneyAccountNames.shown(n))
            assertFalse(MoneyAccountNames.revealsNumber(n), n)
        }
        assertTrue(MoneyAccountNames.revealsNumber("Revolut GB33REVO00996912349180"))
    }

    @Test
    fun aStoredNameIsMaskedWhenRead() {
        var n = 0L
        val r = os.meka.core.sync.Replica("hh", "android", os.meka.core.sync.HlcClock("android", { n++ }),
            os.meka.core.sync.InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
        r.commitLocal(EntityTypes.MONEY_ACCOUNT, "l1", mapOf(
            MoneyAccountFields.NAME to os.meka.core.sync.FieldValue.Text("Revolut GB33REVO00996912349180"),
            MoneyAccountFields.SOURCE to os.meka.core.sync.FieldValue.Text(MoneyBank.SOURCE),
        ))
        val money = Money(r) { 0L }
        assertEquals(listOf("Revolut ••9180"), money.accounts().map { it.name })
    }
}
