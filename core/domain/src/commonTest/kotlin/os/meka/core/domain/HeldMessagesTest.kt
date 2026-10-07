package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The after-work summary on the Mac (Needs Meka #10): held messages synced through Meka's own server. */
class HeldMessagesTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")
    private fun held(d: Device) = HeldMessages(d.replica) { world.clock.nowMs }
    private val fold = held(a)
    private val mac = held(m)
    private val t0 = 1_791_360_000_000L // a weekday morning in October 2026

    init { world.clock.nowMs = t0 + 9 * hour }

    private fun msg(who: String, text: String?, at: Long, app: CaptureApp = CaptureApp.WHATSAPP, group: String? = null) =
        CapturedItem(Capture.itemId(app, CaptureKind.MESSAGE, who, group, at, text), app, CaptureKind.MESSAGE, who, text, group, at)

    private fun call(who: String, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, at, null), CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, null, at)

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    @Test
    fun whatTheFoldHeldShowsOnTheMacGroupedTheSameWay() {
        val lists = PeopleLists(family = setOf("Mum"))
        val items = listOf(
            msg("Tom", "pub later?", t0 + 10 * hour),
            msg("Mum", "call me when you're free", t0 + 11 * hour),
            call("Mum", t0 + 11 * hour + 60_000),
            msg("Ada", "URGENT: the boiler is leaking", t0 + 12 * hour, group = "Flat 4"),
        )
        world.clock.nowMs = t0 + 18 * hour
        assertEquals(4, fold.hold(items, lists))
        syncBoth()
        val onMac = mac.summary()
        // Urgent first, then family (the Mac has no lists: family travels with each item), then the rest.
        assertEquals(listOf("Ada", "Mum", "Tom"), onMac.people.map { it.personName })
        assertTrue(onMac.people[1].isFamily)
        assertEquals("1 message · 1 missed call", onMac.people[1].line)
        assertEquals("Flat 4", onMac.people[0].items.single().conversation)
        assertEquals("3 people · 3 messages · 1 missed call", onMac.headline)
        // The same summary the Fold builds from its own copy.
        assertEquals(AfterWorkSummaries.build(items, lists).headline, onMac.headline)
        assertEquals(fold.summary().people.map { it.personName }, onMac.people.map { it.personName })
    }

    @Test
    fun repostsAndBothCopiesAreOneItem() {
        val first = listOf(msg("Tom", "hi", t0 + 10 * hour))
        assertEquals(1, fold.hold(first))
        assertEquals(0, fold.hold(first + first)) // WhatsApp re-posts the unread message
        assertEquals(1, fold.hold(first + msg("Tom", "you there?", t0 + 10 * hour + 60_000)))
        syncBoth()
        assertEquals(2, mac.items().size)
        assertEquals(HeldMessages.entityId(first[0].id), mac.items().first().id)
    }

    @Test
    fun doneOnTheMacClearsBothAndBlanksTheTextAndNothingComesBack() {
        val items = listOf(msg("Tom", "hi", t0 + 10 * hour), call("Ada", t0 + 11 * hour))
        fold.hold(items)
        syncBoth()
        world.clock.nowMs = t0 + 18 * hour
        assertEquals(2, mac.clear())
        syncBoth()
        listOf(fold, mac).forEach { assertTrue(it.summary().isEmpty) }
        val stored = a.replica.entities(EntityTypes.HELD_MESSAGE)
        assertEquals(2, stored.size)
        stored.forEach { assertNull(it[HeldMessageFields.TEXT].textOrNull) }
        // The Fold's sealed copy still has them (it keeps them to spot re-posts): holding them again doesn't revive them.
        assertEquals(0, fold.hold(items))
        assertEquals(0, mac.clear())
        assertTrue(fold.summary().isEmpty)
    }

    @Test
    fun doneOnBothDevicesOfflineIsOneClearAndALaterMessageStays() {
        fold.hold(listOf(msg("Tom", "hi", t0 + 10 * hour)))
        syncBoth()
        world.clock.nowMs = t0 + 18 * hour
        mac.clear()
        fold.clear()
        // While the Mac's Done was on its way, the Fold held one more (work ran late).
        world.clock.nowMs += 60_000
        fold.hold(listOf(msg("Ada", "still on for tonight?", world.clock.nowMs)))
        syncBoth()
        listOf(fold, mac).forEach { assertEquals(listOf("Ada"), it.summary().people.map { p -> p.personName }) }
    }

    @Test
    fun itemsOlderThanAWeekAreNotShown() {
        fold.hold(listOf(msg("Tom", "old", t0), msg("Ada", "new", t0 + 6 * 24 * hour)))
        world.clock.nowMs = t0 + 7 * 24 * hour + hour
        assertEquals(listOf("Ada"), fold.summary().people.map { it.personName })
    }
}
