package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Clean-ups (2026-10-19): a new phone or Mac asking to join, as Needs you and the asking device show it. */
class DeviceJoinTest {
    private val now = 1_800_000_000_000L
    private val ask = DeviceJoin("join" + "a".repeat(32), "MacBook Air", DeviceJoinRules.MAC, "482913", now, now + 10 * 60_000L, DeviceJoinRules.WAITING)

    private fun roundTrip(j: DeviceJoin): DeviceJoin? = DeviceJoinRules.fields(j).let { f -> DeviceJoinRules.read(j.id) { f[it] } }

    @Test
    fun readsWhatTheServerWritesAndLeavesOutWhatDoesntRead() {
        assertEquals(ask, roundTrip(ask))
        assertEquals("Fold", roundTrip(ask.copy(state = DeviceJoinRules.ALLOWED, answeredBy = "Fold"))!!.answeredBy)
        val f = DeviceJoinRules.fields(ask)
        assertNull(DeviceJoinRules.read(ask.id) { if (it == DeviceJoinFields.CODE) FieldValue.Text("12") else f[it] })
        assertNull(DeviceJoinRules.read(ask.id) { if (it == DeviceJoinFields.KIND) FieldValue.Text("watch") else f[it] })
        assertNull(DeviceJoinRules.read(ask.id) { if (it == DeviceJoinFields.NAME) null else f[it] })
    }

    @Test
    fun aWaitingAskIsALitCardWithAllowAndNotThisOne() {
        val card = DeviceJoinRules.cards(listOf(ask), now + 60_000).single()
        assertEquals(DecisionKind.DEVICE_JOIN, card.kind)
        assertEquals("A new Mac wants to join", card.title)
        assertEquals("MacBook Air · code 482 913 · check the new Mac shows the same · 9 min left", card.why)
        assertTrue(card.urgent)
        assertEquals(DecisionEffect.ALLOW_DEVICE, card.effect(DecisionMove.YES))
        assertEquals(DecisionEffect.REFUSE_DEVICE, card.effect(DecisionMove.LATER))
        assertEquals(DecisionEffect.SET_ASIDE, card.effect(DecisionMove.OPEN))
        assertEquals("A new phone wants to join", DeviceJoinRules.cards(listOf(ask.copy(kind = DeviceJoinRules.PHONE)), now).single().title)
        assertEquals(DeviceJoinRules.ALLOWED_BAR, NeedsYouStackRules.message(card, DecisionMove.YES))
        assertEquals(DeviceJoinRules.REFUSED_BAR, NeedsYouStackRules.message(card, DecisionMove.LATER))
    }

    @Test
    fun answeredOrRunOutAsksLeaveAndAtMostThreeShow() {
        assertTrue(DeviceJoinRules.cards(listOf(ask.copy(state = DeviceJoinRules.ALLOWED)), now).isEmpty())
        assertTrue(DeviceJoinRules.cards(listOf(ask.copy(state = DeviceJoinRules.REFUSED)), now).isEmpty())
        assertTrue(DeviceJoinRules.cards(listOf(ask), ask.expiresAtMs).isEmpty())
        val many = (0 until 5).map { i -> ask.copy(id = "join" + "$i".repeat(32), askedAtMs = now + i) }
        assertEquals(listOf("4", "3", "2"), DeviceJoinRules.cards(many, now + 10).map { it.id.drop(4).take(1) })
    }

    @Test
    fun itGoesOnTopOfNeedsYou() {
        val cal = LocalCalendar.fixedOffset(0)
        val stack = NeedsYouStackRules.build(
            Today(emptyList(), null, emptyList(), emptyList()), "2 to chase", now, cal, devices = DeviceJoinRules.cards(listOf(ask), now),
        )
        assertEquals(listOf(DecisionKind.DEVICE_JOIN, DecisionKind.LISTS), stack.cards.map { it.kind })
    }

    @Test
    fun theAskingDeviceSaysWhereToAllowIt() {
        assertEquals("Code 482 913 · it shows in Needs you there · 10 min left", DeviceJoinRules.waitingLine("482913", ask.expiresAtMs, now))
        assertEquals("Code 482 913 · it shows in Needs you there · 1 min left", DeviceJoinRules.waitingLine("482913", ask.expiresAtMs, ask.expiresAtMs + 5))
        assertEquals("Allow this Mac from your phone or other Mac", DeviceJoinRules.waitingTitle(DeviceJoinRules.MAC))
        assertEquals(DeviceJoinRules.ALLOWED_BAR, DeviceJoinRules.answeredLine(DeviceJoinRules.ALLOWED))
        assertEquals(DeviceJoinRules.GONE_BAR, DeviceJoinRules.answeredLine("gone"))
        assertEquals(DeviceJoinRules.OFFLINE_BAR, DeviceJoinRules.answeredLine(null))
    }
}
