package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarAccountsTest {
    @Test
    fun theSecondNewsFeedReadsHeadlinesNotItsId() {
        // Meka's screenshot 2026-10-09 09:01: "news_more · synced 08:29".
        assertEquals("Headlines", CalendarAccountRules.providerLabel("news_more"))
        assertEquals("Headlines", CalendarAccountRules.providerLabel("news"))
        assertEquals("Headlines · synced 08:29", CalendarAccountRules.statusLine("news_more", "AI, tech and Barça news", "ok", "08:29"))
        assertEquals("Headlines · synced 08:29", CalendarAccountRules.statusLine("news", "BBC News", "ok", "08:29"))
        assertEquals("Bank holidays · first sync in progress", CalendarAccountRules.statusLine("bank_holidays", "GOV.UK", "ok", null))
        // A feed keeps its own name as the title.
        assertEquals("AI, tech and Barça news", CalendarAccountRules.title("news_more", "AI, tech and Barça news"))
        assertEquals("Weather", CalendarAccountRules.title("weather", " "))
    }

    @Test
    fun aSignedInAccountIsTitledLikeItsCalendarWithTheAddressUnderIt() {
        assertEquals("Personal", CalendarAccountRules.title("google", "meka@gmail.com"))
        assertEquals("Hotmail", CalendarAccountRules.title("microsoft", "meka@hotmail.co.uk"))
        assertEquals("Outlook", CalendarAccountRules.title("microsoft", "meka@outlook.com"))
        assertEquals("Google · meka@gmail.com · synced 08:29", CalendarAccountRules.statusLine("google", "meka@gmail.com", "ok", "08:29"))
        assertEquals("Outlook · meka@hotmail.co.uk · first sync in progress", CalendarAccountRules.statusLine("microsoft", "meka@hotmail.co.uk", "ok", null))
        assertEquals("Google · meka@gmail.com · access expired · Reconnect", CalendarAccountRules.statusLine("google", "meka@gmail.com", "needs_reconnect", "08:29"))
        assertEquals("Google · meka@gmail.com · couldn't sync last time · retrying", CalendarAccountRules.statusLine("google", "meka@gmail.com", "error", null))
    }

    @Test
    fun mekasOwnNameForTheMainCalendarTitlesTheAccount() {
        val names = mapOf(
            "google|meka@gmail.com|Meka@Gmail.com" to "Home",
            "google|meka@gmail.com|Timestripe" to "Goals",
            "microsoft|meka@hotmail.co.uk|Calendar" to "  Old   mail ",
            "google|other@gmail.com|other@gmail.com" to "Other",
        )
        assertEquals("Home", CalendarAccountRules.title("google", "Meka@gmail.com", names))
        assertEquals("Old mail", CalendarAccountRules.title("microsoft", "meka@hotmail.co.uk", names))
        // Another calendar's name never titles the account; nor another account's.
        assertEquals("Personal", CalendarAccountRules.title("google", "meka@gmail.com", names - "google|meka@gmail.com|Meka@Gmail.com"))
        // The key's events read the same name ([CalendarRules.key] for the account's main calendar).
        val e = CalendarEvent("e", "Dentist", 0, 1, false, null, "google", "meka@gmail.com", "Meka@Gmail.com")
        assertEquals("Home", names[CalendarRules.key(e)])
    }

    @Test
    fun theMainCalendarIsKnownByProvider() {
        assertTrue(CalendarAccountRules.isMainCalendar("google", "meka@gmail.com", " MEKA@gmail.com"))
        assertFalse(CalendarAccountRules.isMainCalendar("google", "meka@gmail.com", "kids@group.calendar.google.com"))
        assertTrue(CalendarAccountRules.isMainCalendar("microsoft", "meka@outlook.com", "Calendar"))
        assertFalse(CalendarAccountRules.isMainCalendar("microsoft", "meka@outlook.com", "Work"))
        assertFalse(CalendarAccountRules.isMainCalendar("fixtures", null, "FC Barcelona"))
        assertFalse(CalendarAccountRules.isMailAccount("news", "BBC News"))
        assertTrue(CalendarAccountRules.isMailAccount("microsoft", "meka@hotmail.co.uk"))
    }
}
