package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyReceiptTest {
    private val hourMs = 3_600_000L
    private val day = 20_000L
    private val now = day * CivilDate.DAY_MS + 21 * hourMs
    private var n = 0

    private fun item(kind: ActivityKind, atMs: Long = now - hourMs, undoneAtMs: Long? = null) = ActivityItem(
        id = "a${n++}", atMs = atMs, kind = kind, summary = "s", detail = null, why = "w", source = null, level = null,
        changes = emptyList(), undoneAtMs = undoneAtMs, undoNote = null,
    )

    private fun receipt(vararg items: ActivityItem) = ReceiptRules.receipt(items.toList(), now, LocalCalendar.UTC)

    @Test
    fun nothingHandledTodayIsNoLine() {
        assertNull(receipt())
        // Yesterday's entries, Meka's own calendar edits, family and watch links aren't MEKA handling something today.
        assertNull(receipt(
            item(ActivityKind.REMINDED, atMs = now - 22 * hourMs),
            item(ActivityKind.CALENDAR), item(ActivityKind.FAMILY), item(ActivityKind.DEVICE),
        ))
    }

    @Test
    fun countsWhatMekaDidWithTheBiggestPartsFirst() {
        val r = receipt(
            item(ActivityKind.REMINDED), item(ActivityKind.REMINDED), item(ActivityKind.REMINDED),
            item(ActivityKind.SCREENED), item(ActivityKind.SCREENED),
            item(ActivityKind.DIGEST), item(ActivityKind.DIGEST),
            item(ActivityKind.PUBLISHED),
            item(ActivityKind.CALENDAR),
        )!!
        assertEquals("Navilife handled 7 things today", r.line)
        assertEquals(7, r.count)
        // Ties keep the fixed order (calls stopped before digests); the phone build isn't counted at all.
        assertEquals("3 reminders · 2 calls stopped · 2 digests", r.detail)
    }

    @Test
    fun anUndoneChangeDoesNotCountAndOneThingIsSingular() {
        val r = receipt(item(ActivityKind.CHANGED), item(ActivityKind.CHANGED, undoneAtMs = now - 60_000))!!
        assertEquals("Navilife handled 1 thing today", r.line)
        assertEquals("1 change", r.detail)
        assertEquals("1 call handled", ReceiptRules.part(ActivityKind.CALL, 1))
        assertEquals("2 calls handled", ReceiptRules.part(ActivityKind.CALL, 2))
    }

    @Test
    fun theDayStartsAtLocalMidnight() {
        // 00:30 today counts; 23:30 yesterday doesn't.
        val r = receipt(item(ActivityKind.DIGEST, atMs = day * CivilDate.DAY_MS + hourMs / 2), item(ActivityKind.DIGEST, atMs = day * CivilDate.DAY_MS - hourMs / 2))!!
        assertEquals("1 digest", r.detail)
    }

    @Test
    fun phoneBuildsNeverCountAndAloneGiveNoLine() {
        // Fold review 2026-10-12 17:56, item 1: "MEKA handled 54 things today · 51 phone builds".
        assertNull(receipt(*Array(51) { item(ActivityKind.PUBLISHED) }))
        val r = receipt(*Array(51) { item(ActivityKind.PUBLISHED) }, item(ActivityKind.REMINDED), item(ActivityKind.REMINDED), item(ActivityKind.SCREENED))!!
        assertEquals("Navilife handled 3 things today", r.line)
        assertEquals("2 reminders · 1 call stopped", r.detail)
    }
}
