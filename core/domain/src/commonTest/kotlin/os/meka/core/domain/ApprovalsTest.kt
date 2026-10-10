package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The approvals inbox (slice 1): requests, the card, the fingerprint rule, answers on two devices, expiry. */
class ApprovalsTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun approvals(d: Device) = Approvals(d.replica, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(sun, 21, 40) }

    private fun trade(d: Device = fold, expires: Long? = at(sun, 21, 54)): String = assertNotNull(
        approvals(d).request(ApprovalKind.TRADE, "Kestrel", "sig-1", "Buy 0.05 BTC at market", listOf("About £2,410", "Stop loss 5%"), expires),
    )

    @Test
    fun aRequestShowsAsACardWithWhoAskedAndWhenItRunsOut() {
        val id = trade()
        val card = approvals(fold).cards().single()
        assertEquals(id, card.id)
        assertEquals("TRADE · KESTREL", card.label)
        assertEquals("Buy 0.05 BTC at market", card.title)
        assertEquals(listOf("About £2,410", "Stop loss 5%"), card.lines)
        assertEquals("Asked 21:40 · expires in 14 min", card.why)
        assertTrue(card.urgent)
        assertTrue(card.biometric)
        assertEquals("Kestrel asks: Trade. Buy 0.05 BTC at market. About £2,410. Stop loss 5%. Asked 21:40 · expires in 14 min", card.spoken)

        // The same request again (same source and ref) is one card.
        assertEquals(id, trade())
        assertEquals(1, approvals(fold).cards().size)
    }

    @Test
    fun expiryReadsAsATimeAndARequestWithNoneHasADay() {
        approvals(fold).request(ApprovalKind.PAYMENT, "Kestrel", "p1", "Pay £40 to Octopus", emptyList())
        val card = approvals(fold).cards().single()
        assertEquals("Asked 21:40 · expires tomorrow 21:40", card.why)
        assertEquals(false, card.urgent)
        world.clock.nowMs = at(sun + 1, 9)
        assertEquals("Asked yesterday 21:40 · expires at 21:40", approvals(fold).cards().single().why)
    }

    @Test
    fun approvingNeedsTheFingerprintAndDecliningTakesATap() {
        val id = trade()
        val a = approvals(fold)
        assertEquals(ApprovalDecision.Refused(ApprovalRefusal.NEEDS_BIOMETRIC), a.decide(id, true, ApprovalConfirmation.TAP, "k1", "android"))
        assertEquals(1, a.cards().size)

        val yes = assertIs<ApprovalDecision.Write>(a.decide(id, true, ApprovalConfirmation.BIOMETRIC, "k1", "android"))
        assertEquals("Approved · Buy 0.05 BTC at market", yes.line)
        assertTrue(a.cards().isEmpty())
        val stored = assertNotNull(a.find(id))
        assertEquals(ApprovalState.APPROVED, stored.state(world.clock.nowMs))
        assertEquals("biometric", stored.confirmedBy)
        assertEquals("k1", stored.decisionKey)

        // Answered once: a second answer is refused.
        assertEquals(ApprovalDecision.Refused(ApprovalRefusal.ANSWERED), a.decide(id, false, ApprovalConfirmation.TAP, "k2", "android"))

        val other = approvals(fold).request(ApprovalKind.SHARE, "Vault", "d1", "Share passport scan with Jeanette", emptyList())!!
        val no = assertIs<ApprovalDecision.Write>(a.decide(other, false, ApprovalConfirmation.TAP, "k3", "android"))
        assertEquals("Declined · Share passport scan with Jeanette", no.line)
        assertEquals(ApprovalDecision.Refused(ApprovalRefusal.GONE), a.decide("nope", false, ApprovalConfirmation.TAP, "k4", "android"))
    }

    @Test
    fun anExpiredRequestLeavesNeedsYouAndCantBeApproved() {
        val id = trade()
        world.clock.nowMs = at(sun, 21, 54)
        assertTrue(approvals(fold).cards().isEmpty())
        assertEquals(ApprovalDecision.Refused(ApprovalRefusal.EXPIRED), approvals(fold).decide(id, true, ApprovalConfirmation.BIOMETRIC, "k", "android"))
    }

    @Test
    fun anAnswerOnOneDeviceClearsTheOtherAndAClashCountsAsDeclined() {
        val id = trade()
        fold.sync(); mac.sync()
        assertEquals(1, approvals(mac).cards().size)
        approvals(mac).decide(id, false, ApprovalConfirmation.TAP, "km", "mac")
        mac.sync(); fold.sync()
        assertTrue(approvals(fold).cards().isEmpty())
        assertEquals(ApprovalState.DECLINED, approvals(fold).find(id)!!.state(world.clock.nowMs))

        // Both answer at once, differently: once synced it is a no on both.
        val id2 = approvals(fold).request(ApprovalKind.TRADE, "Kestrel", "sig-2", "Sell 0.05 BTC", emptyList(), at(sun, 23))!!
        fold.sync(); mac.sync()
        approvals(fold).decide(id2, true, ApprovalConfirmation.BIOMETRIC, "kf", "android")
        approvals(mac).decide(id2, false, ApprovalConfirmation.TAP, "km2", "mac")
        fold.sync(); mac.sync(); fold.sync()
        assertEquals(ApprovalState.DECLINED, approvals(fold).find(id2)!!.state(world.clock.nowMs))
        assertEquals(ApprovalState.DECLINED, approvals(mac).find(id2)!!.state(world.clock.nowMs))
    }

    @Test
    fun requestsAreCleanedAndBadOnesRefused() {
        val asked = at(sun, 21, 40)
        assertNull(ApprovalRules.requestFields(ApprovalKind.TRADE, "Kestrel", "  ", emptyList(), asked))
        assertNull(ApprovalRules.requestFields(ApprovalKind.TRADE, " ", "Buy", emptyList(), asked))
        assertNull(ApprovalRules.requestFields(ApprovalKind.TRADE, "Kestrel", "Buy", emptyList(), asked, asked - hour))
        val f = assertNotNull(ApprovalRules.requestFields(
            ApprovalKind.TRADE, "Kestrel", "Buy\n0.05   BTC", listOf("a", " ", "b\nc", "d", "e", "f"), asked, asked + 30 * 24 * hour,
        ))
        val a = assertNotNull(ApprovalRules.parse("x", f))
        assertEquals("Buy 0.05 BTC", a.title)
        assertEquals(listOf("a", "b c", "d", "e"), a.lines)
        assertEquals(asked + ApprovalRules.MAX_TTL_MS, a.expiresAtMs)
        val long = ApprovalRules.parse("y", ApprovalRules.requestFields(ApprovalKind.TRADE, "Kestrel", "word ".repeat(60), emptyList(), asked)!!)!!.title
        assertTrue(long.length <= ApprovalRules.MAX_TITLE && long.endsWith("word…"), long)
        // An unknown kind is never shown.
        assertNull(ApprovalRules.parse("z", f + (ApprovalFields.KIND to os.meka.core.sync.FieldValue.Text("transfer"))))
    }

    @Test
    fun theActivityLinesSayWhatWasAnsweredAndHow() {
        trade()
        val a = approvals(fold).all().single()
        assertEquals(
            Triple("Approved: Buy 0.05 BTC at market", "About £2,410 · Stop loss 5%", "Kestrel asked · you confirmed it was you"),
            ApprovalRules.activityLines(a, true, ApprovalConfirmation.BIOMETRIC),
        )
        assertEquals("Kestrel asked · nothing was done", ApprovalRules.activityLines(a, false, ApprovalConfirmation.TAP).third)
        assertEquals(ApprovalRules.activityId(a.id), ApprovalRules.activityId(a.id))
    }

    @Test
    fun theSoonestToExpireComesFirst() {
        approvals(fold).request(ApprovalKind.PAYMENT, "Kestrel", "late", "Later one", emptyList(), at(sun, 23))
        approvals(fold).request(ApprovalKind.TRADE, "Kestrel", "soon", "Sooner one", emptyList(), at(sun, 22))
        assertEquals(listOf("Sooner one", "Later one"), approvals(fold).cards().map { it.title })
    }
}
