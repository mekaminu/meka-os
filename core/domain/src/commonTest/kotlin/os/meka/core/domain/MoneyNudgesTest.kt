package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money manager, nudges: a heads-up when recent payments make something worth a look. Invented statements only. */
class MoneyNudgesTest {
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val oct19 = d(10, 19)
    private val names = mapOf("a1" to "Current account")
    private var n = 0
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory, text: String = merchant) =
        MoneyWatchLine("t${n++}", "a1", day, pence, merchant, c, text)

    /** Groceries every week since April: enough history for "a first payment somewhere new". */
    private val history = (0 until 28).map { line(d(4, 1) + it * 7L, -6_000, "TESCO STORES", MoneyCategory.GROCERIES) } +
        (4..9).map { m -> line(d(m, 5), -3_000, "AMAZON", MoneyCategory.SHOPPING) }

    @Test
    fun aDoubleChargeFromYesterdayIsNewAndSaysNoAmount() {
        val lines = history + line(d(10, 18), -2_340, "AMAZON", MoneyCategory.SHOPPING) + line(d(10, 18), -2_340, "AMAZON", MoneyCategory.SHOPPING)
        val items = MoneyNudges.items(lines, oct19, names, emptySet(), emptySet())
        val item = items.single()
        assertEquals("Possibly charged twice · Amazon", item.title)
        assertTrue(item.alert)
        val notice = MoneyNudges.notices(items, cal.toEpochMs(oct19, 7 * 60), cal).single()
        assertEquals(NoticeSource.MONEY_LOOK, notice.source)
        assertEquals(NoticeTarget.MONEY, notice.target)
        assertEquals(cal.toEpochMs(oct19, 9 * 60), notice.atMs)
        assertEquals(MoneyNudges.ALERT_TEXT, notice.text)
        assertFalse(notice.title.contains("£") || notice.text.contains("£"))
        // Put away ("That's fine"): never comes.
        val id = item.key.removePrefix(MoneyNudges.KEY_PREFIX)
        assertTrue(MoneyNudges.items(lines, oct19, names, emptySet(), setOf(id)).isEmpty())
    }

    @Test
    fun anOldStatementSetsNothingOff() {
        // The same double charge, but a month ago: it's on the Money screen, not a heads-up.
        val lines = history + line(d(9, 18), -2_340, "AMAZON", MoneyCategory.SHOPPING) + line(d(9, 18), -2_340, "AMAZON", MoneyCategory.SHOPPING) +
            line(d(10, 17), -6_000, "TESCO STORES", MoneyCategory.GROCERIES)
        assertTrue(MoneyNudges.items(lines, oct19, names, emptySet(), emptySet()).isEmpty())
        // Nothing in the last three days at all: nothing.
        assertTrue(MoneyNudges.items(history, oct19, names, emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun aPriceRiseIsATipOnceAMonthAndHidingItsKindStopsIt() {
        val netflix = listOf(d(6, 18), d(7, 18), d(8, 18), d(9, 18)).map { line(it, -1_099, "NETFLIX.COM", MoneyCategory.SUBSCRIPTIONS) } +
            line(d(10, 18), -1_249, "NETFLIX.COM", MoneyCategory.SUBSCRIPTIONS)
        val items = MoneyNudges.items(history + netflix, oct19, names, emptySet(), emptySet())
        val tip = items.single()
        assertEquals("Netflix.com went up", tip.title)
        assertFalse(tip.alert)
        assertEquals("money-look:rise-NETFLIX.COM:2026-10", tip.key)
        assertEquals(MoneyNudges.TIP_TEXT, MoneyNudges.notices(items, cal.toEpochMs(oct19, 12 * 60), cal).single().text)
        assertTrue(MoneyNudges.items(history + netflix, oct19, names, setOf(MoneyTipKind.RISE.key), emptySet()).isEmpty())
    }

    @Test
    fun aCategoryTippedOverByThisWeeksSpendingIsNew() {
        val before = (4..9).map { m -> line(d(m, 10), -8_000, "NEXT", MoneyCategory.CLOTHES) }
        val early = line(d(10, 3), -9_000, "NEXT", MoneyCategory.CLOTHES)
        val recent = line(d(10, 18), -12_000, "NEXT", MoneyCategory.CLOTHES)
        val items = MoneyNudges.items(history + before + early + recent, oct19, names, emptySet(), emptySet())
        assertEquals(listOf("Clothes over your 6-month average"), items.map { it.title })
        // Already over before this week: not new, so no heads-up (the screen still shows it).
        val wasOver = line(d(10, 3), -26_000, "NEXT", MoneyCategory.CLOTHES)
        assertTrue(MoneyNudges.items(history + before + wasOver + recent, oct19, names, emptySet(), emptySet()).none { it.title.startsWith("Clothes") })
    }

    @Test
    fun severalAtOnceShareOneNotificationAndSayHowMany() {
        val lines = history + line(d(10, 18), -2_340, "AMAZON", MoneyCategory.SHOPPING) + line(d(10, 18), -2_340, "AMAZON", MoneyCategory.SHOPPING) +
            line(d(10, 18), -1_200, "LATE PAYMENT FEE", MoneyCategory.FEES)
        val items = MoneyNudges.items(lines, oct19, names, emptySet(), emptySet())
        assertEquals(listOf("Possibly charged twice · Amazon", "Late payment fee"), items.map { it.title })
        val notices = MoneyNudges.notices(items, cal.toEpochMs(oct19, 10 * 60), cal)
        assertEquals(setOf(MoneyNudges.POST_AS), notices.map { it.postId }.toSet())
        assertEquals("Worth a look · and 1 more in Money, behind your fingerprint", notices.first().text)
    }

    @Test
    fun amountsComeOut() {
        assertEquals("Bigger than usual · Currys", MoneyNudges.withoutAmounts("Bigger than usual · Currys £480"))
        assertEquals("First payment to Acme Ltd", MoneyNudges.withoutAmounts("First payment to Acme Ltd · £320"))
        assertEquals("Coffee 19× in the last 30 days", MoneyNudges.withoutAmounts("Coffee 19× · £71.25 in the last 30 days"))
        assertEquals("Foreign transaction fees", MoneyNudges.withoutAmounts("Foreign transaction fees · £4.12"))
        assertEquals("Clothes was over your 6-month average in September", MoneyNudges.withoutAmounts("Clothes was £1,180 over your 6-month average in September"))
    }

    @Test
    fun theSourceIsItsOwnRowInNotifications() {
        assertTrue(NoticeSource.MONEY_LOOK in NoticeSource.ADJUSTABLE)
        assertEquals(NoticeTier.HEADS_UP, NoticeSource.MONEY_LOOK.defaultTier)
    }
}
