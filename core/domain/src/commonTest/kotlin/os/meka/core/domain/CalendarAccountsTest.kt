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
        assertEquals("GOV.UK · first sync in progress", CalendarAccountRules.statusLine("bank_holidays", "GOV.UK", "ok", null))
        // A feed keeps its own name as the title.
        assertEquals("AI, tech and Barça news", CalendarAccountRules.title("news_more", "AI, tech and Barça news"))
        assertEquals("Weather", CalendarAccountRules.title("weather", " "))
    }

    @Test
    fun aSignedInAccountIsTitledLikeItsCalendarWithTheAddressUnderIt() {
        assertEquals("Personal", CalendarAccountRules.title("google", "meka@gmail.com"))
        assertEquals("Hotmail", CalendarAccountRules.title("microsoft", "meka@hotmail.co.uk"))
        assertEquals("Outlook", CalendarAccountRules.title("microsoft", "meka@outlook.com"))
        assertEquals("Google · synced 08:29", CalendarAccountRules.statusLine("google", "meka@gmail.com", "ok", "08:29"))
        assertEquals("Outlook · first sync in progress", CalendarAccountRules.statusLine("microsoft", "meka@hotmail.co.uk", "ok", null))
        assertEquals("Google · access expired", CalendarAccountRules.statusLine("google", "meka@gmail.com", "needs_reconnect", "08:29"))
        assertEquals("Google · couldn't sync last time · retrying", CalendarAccountRules.statusLine("google", "meka@gmail.com", "error", null))
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

    @Test
    fun aSignedInAccountSaysWhetherItsEventsComeThrough() {
        // Meka's 10:48 screenshots, 2026-10-09: Hotmail connected, nothing of it showing.
        val now = 1_791_244_800_000L
        val day = 86_400_000L
        fun ev(id: String, provider: String, account: String?, from: Long) =
            CalendarEvent(id, id, from, from + 3_600_000L, false, null, provider, account, "Calendar")
        val events = listOf(
            ev("a", "google", "meka@gmail.com", now + day),
            ev("b", "google", "Meka@Gmail.com", now + 2 * day),
            ev("c", "google", "meka@gmail.com", now + 40 * day), // past the 30 days
            ev("d", "google", "meka@gmail.com", now - 2 * day), // over
            ev("e", "microsoft", "other@hotmail.co.uk", now + day),
        )
        assertEquals("2 events in the next 30 days", CalendarAccountRules.eventsLine("google", "meka@gmail.com", "ok", true, events, now))
        assertEquals("No events in the next 30 days", CalendarAccountRules.eventsLine("microsoft", "meka@hotmail.co.uk", "ok", true, events, now))
        assertEquals("1 event in the next 30 days", CalendarAccountRules.eventsLine("microsoft", "other@hotmail.co.uk", "ok", true, events, now))
        // Feeds, a lapsed sign-in or the first sync still running: the status line says enough.
        kotlin.test.assertNull(CalendarAccountRules.eventsLine("news", "BBC News", "ok", true, events, now))
        kotlin.test.assertNull(CalendarAccountRules.eventsLine("google", "meka@gmail.com", "needs_reconnect", true, events, now))
        kotlin.test.assertNull(CalendarAccountRules.eventsLine("google", "meka@gmail.com", "ok", false, events, now))
    }

    @Test
    fun syncedAtReadsTheCoresZoneNotTheDevices() {
        // Quality gate slice 33: Calendars wrote the time in the device's own zone. Wed 21 Oct 2026, 17:15 BST (UTC+1).
        val bst = LocalCalendar.fixedOffset(3_600_000L)
        val now = 1_792_599_300_000L
        val min = 60_000L
        assertEquals("17:11", CalendarAccountRules.syncedAt(now - 4 * min, now, bst))
        assertEquals("16:11", CalendarAccountRules.syncedAt(now - 4 * min, now, LocalCalendar.UTC))
        assertEquals("just now", CalendarAccountRules.syncedAt(now - 30_000L, now, bst))
        // An earlier day names it, so "synced 08:29" never passes for this morning.
        val yesterday = CalendarAccountRules.syncedAt(now - 24 * 60 * min, now, bst)!!
        assertTrue(yesterday.endsWith("17:15") && yesterday != "17:15", yesterday)
        kotlin.test.assertNull(CalendarAccountRules.syncedAt(null, now, bst))
        // The drawn line keeps "synced 17:11" together.
        val line = LoneWordGlue.kept(CalendarAccountRules.statusLine("google", "alex@example.com", "ok", "17:11"))
        assertFalse(line.endsWith(" 17:11"), line)
    }

    @Test
    fun aKindOfFeedIsTitledByWhatItBringsWithItsSourceUnder() {
        // Quality gate slice 34: "GOV.UK" over "Bank holidays" read the wrong way round.
        assertEquals("Bank holidays", CalendarAccountRules.title("bank_holidays", "GOV.UK · England and Wales"))
        assertEquals("GOV.UK · England and Wales · synced 08:29", CalendarAccountRules.statusLine("bank_holidays", "GOV.UK · England and Wales", "ok", "08:29"))
        assertEquals("Weather", CalendarAccountRules.title("weather", "Open-Meteo · Springfield"))
        assertEquals("Open-Meteo · Springfield · synced 08:29", CalendarAccountRules.statusLine("weather", "Open-Meteo · Springfield", "ok", "08:29"))
        assertEquals("Train lines", CalendarAccountRules.title("lines", "TfL · the lines on your train route"))
        assertEquals("Car (DVLA)", CalendarAccountRules.title(CarCheckRules.PROVIDER, CarCheckRules.LABEL))
        assertEquals("${CarCheckRules.LABEL} · off · waiting for the DVLA keys", CalendarAccountRules.statusLine(CarCheckRules.PROVIDER, CarCheckRules.LABEL, CarCheckRules.STATUS_OFF, null))
        // No source given: never the title said twice.
        assertEquals("Synced 08:29", CalendarAccountRules.statusLine("weather", " ", "ok", "08:29"))
        // Named feeds keep their own names.
        assertEquals("FC Barcelona", CalendarAccountRules.title("fixtures", "FC Barcelona"))
        assertEquals("Fixtures · synced 08:29", CalendarAccountRules.statusLine("fixtures", "FC Barcelona", "ok", "08:29"))
    }

    @Test
    fun anExpiredAccountsLineLeavesReconnectToItsButton() {
        val line = CalendarAccountRules.statusLine("microsoft", "alex.work@example.com", "needs_reconnect", "08:29")
        assertFalse("Reconnect" in line, line)
        assertEquals("Outlook · access expired", line)
    }

    @Test
    fun anAccountTitledByItsProviderDoesNotSayItTwice() {
        // Quality gate slice 36: the expired Outlook card read "Outlook" over "Outlook · alex.work@example.com ·" with
        // "access expired" alone on the next line.
        val expired = CalendarAccountRules.statusLine("microsoft", "alex.work@example.com", "needs_reconnect", "08:29", title = "Outlook")
        assertEquals("Access expired", expired)
        val kept = LoneWordGlue.kept(expired)
        assertFalse(kept.trimEnd().endsWith("·"), kept)
        assertEquals("Synced 08:29",
            CalendarAccountRules.statusLine("microsoft", "alex.work@example.com", "ok", "08:29", title = "Outlook"))
        // A title of its own keeps the provider on the line.
        assertEquals("Outlook · synced 08:29",
            CalendarAccountRules.statusLine("microsoft", "meka@hotmail.co.uk", "ok", "08:29", title = "Hotmail"))
        assertEquals("Google · access expired",
            CalendarAccountRules.statusLine("google", "sam@example.com", "needs_reconnect", null, title = "Personal"))
        // Feeds are unchanged.
        assertEquals("Headlines · synced 08:29", CalendarAccountRules.statusLine("news", "BBC News", "ok", "08:29", title = "BBC News"))
    }

    @Test
    fun aNarrowOnTodayRowDropsTheProviderBeforeAnAddress() {
        // Quality gate slice 35: on the cover screen "Outlook ·" sat alone above its address.
        assertEquals("alex.work@example.com", CalendarRules.detailShort("Outlook · alex.work@example.com"))
        assertEquals("sam@example.com", CalendarRules.detailShort("Google · sam@example.com"))
        // The rest of the line stays.
        assertEquals(
            "sam@example.com · ${HolidayCalendars.DETAIL}",
            CalendarRules.detailShort("Google · sam@example.com · ${HolidayCalendars.DETAIL}"),
        )
        // Nothing to drop: no address after the provider, no provider, or nothing at all.
        assertEquals(null, CalendarRules.detailShort("Google"))
        assertEquals(null, CalendarRules.detailShort("Google · ${HolidayCalendars.DETAIL}"))
        assertEquals(null, CalendarRules.detailShort("FC Barcelona"))
        assertEquals(null, CalendarRules.detailShort(null))
        assertEquals("alex.work@example.com", CalendarChoice("k", "Outlook", "Outlook · alex.work@example.com", true).detailShort)
    }

    @Test
    fun noAddressIsEverShownAndTwoAccountsOfOneNameAreToldApart() {
        // Walk-through A2, 2026-10-30: Settings → Calendars showed "Google · alex@example.com" and "Access expired for
        // alex.work@…" as if the address were the account's name.
        for (status in listOf("ok", "error", "needs_reconnect")) for (title in listOf(null, "Personal", "Outlook")) {
            val line = CalendarAccountRules.statusLine("microsoft", "alex.work@example.com", status, "08:29", title)
            assertFalse('@' in line || "alex" in line, line)
        }
        val titles = CalendarAccountRules.distinctTitles(listOf(
            "google" to "sam@example.com", "google" to "Alex@example.com", "microsoft" to "alex@hotmail.co.uk",
            "microsoft" to "alex@outlook.com", "news" to "BBC News", "google" to "alex@example.com",
        ), mapOf("google|alex@example.com|alex@example.com" to "Home"))
        assertEquals("Home", titles[CalendarAccountRules.key("google", "Alex@example.com")])
        assertEquals("Personal", titles[CalendarAccountRules.key("google", "sam@example.com")])
        assertEquals("Hotmail", titles[CalendarAccountRules.key("microsoft", "alex@hotmail.co.uk")])
        assertEquals("Outlook", titles[CalendarAccountRules.key("microsoft", "alex@outlook.com")])
        assertEquals("BBC News", titles[CalendarAccountRules.key("news", "BBC News")])
        assertEquals(5, titles.size)
        // Two Gmail accounts with no names of their own: the second by address is "Personal 2".
        val two = CalendarAccountRules.distinctTitles(listOf("google" to "sam@example.com", "google" to "alex@example.com"))
        assertEquals("Personal", two[CalendarAccountRules.key("google", "alex@example.com")])
        assertEquals("Personal 2", two[CalendarAccountRules.key("google", "sam@example.com")])
    }
}
