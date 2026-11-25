package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Notifications' "This phone" and each kind of notice are a row opening a sheet. */
class NotifyChoiceSheetTest {
    @Test
    fun theDeviceRowSaysWhatIsChosenAndTheSheetLightsIt() {
        val row = NotifyRules.deviceRow(DeviceAlerts.DIGESTS, "This phone")
        assertEquals("This phone", row.title)
        assertEquals("Digests only", row.value)
        assertTrue(row.spoken.startsWith("This phone, Digests only"))
        val sheet = NotifyRules.deviceSheet(DeviceAlerts.DIGESTS, "This phone")
        assertEquals("This phone", sheet.title)
        assertEquals(listOf("Everything", "Digests only", "Off"), sheet.choices.map { it.label })
        assertEquals(listOf("DIGESTS"), sheet.choices.filter { it.lit }.map { it.name })
        assertTrue(sheet.choices.all { it.line.isNotBlank() })
        assertTrue(sheet.choices.single { it.lit }.spoken.endsWith(", chosen"))
        assertEquals(DeviceAlerts.OFF, NotifyRules.deviceNamed(sheet.choices.last().name))
    }

    @Test
    fun aKindOfNoticeOffersItsDefaultAndLowerWithTheUsedOneLit() {
        val settings = NotificationSettings.DEFAULT.copy(tiers = mapOf(NoticeSource.BRIEF to NoticeTier.DIGEST))
        val row = NotifyRules.tierRow(settings, NoticeSource.BRIEF)
        assertEquals("Morning brief", row.title)
        assertEquals("Digest", row.value)
        assertEquals("Heads-up", NotifyRules.tierRow(settings, NoticeSource.WEEKLY_REVIEW).value)
        val sheet = NotifyRules.tierSheet(settings, NoticeSource.BRIEF)
        assertEquals("Morning brief", sheet.title)
        assertEquals(NotifyRules.tierChoices(NoticeSource.BRIEF).map { it.label }, sheet.choices.map { it.label })
        assertEquals(listOf("Heads-up", "Digest", "App only"), sheet.choices.map { it.label })
        assertEquals(listOf(NoticeTier.DIGEST), sheet.choices.filter { it.lit }.mapNotNull { NotifyRules.tierNamed(it.name) })
        assertTrue(sheet.note.isNotBlank())
    }

    @Test
    fun everyAdjustableKindHasARowAndASheetWithOneLit() {
        NoticeSource.ADJUSTABLE.forEach { s ->
            val sheet = NotifyRules.tierSheet(NotificationSettings.DEFAULT, s)
            assertEquals(1, sheet.choices.count { it.lit }, s.label)
            assertEquals(sheet.choices.single { it.lit }.label, NotifyRules.tierRow(NotificationSettings.DEFAULT, s).value)
        }
    }
}
