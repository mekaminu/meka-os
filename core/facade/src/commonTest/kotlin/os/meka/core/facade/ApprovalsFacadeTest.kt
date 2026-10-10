package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.ApprovalKind
import os.meka.core.domain.ApprovalRefusal
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.EntityTypes
import os.meka.core.policy.ActionRequest
import os.meka.core.policy.ActionType
import os.meka.core.policy.AutonomyLevel
import os.meka.core.policy.PolicyConfig
import os.meka.core.policy.PolicyDecision
import os.meka.core.policy.PolicyDomain
import os.meka.core.policy.PolicyEngine
import os.meka.core.policy.Provenance
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The approvals inbox through the facade (slice 1): a request the server writes, the fingerprint rule, both devices. */
class ApprovalsFacadeTest {
    private var now = 1_791_755_400_000L // Sun 11 Oct 2026, 22:50 in London
    private val ops = InMemoryServerOpStore()
    private var seq = 0

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    /** What the server writes when Kestrel asks (the trading approvals item builds the endpoint). */
    private fun serverAsks(ref: String, title: String, expiresAtMs: Long): String {
        val id = ApprovalRules.entityId("Kestrel", ref)
        val fields = ApprovalRules.requestFields(ApprovalKind.TRADE, "Kestrel", title, listOf("About £2,410"), now, expiresAtMs)!!
        for ((f, v) in fields) {
            seq++
            ops.append(Op("s$seq", "hh", EntityTypes.APPROVAL, id, f, v, Hlc(now, seq, "server"), emptyList(), "server"))
        }
        return id
    }

    @Test
    fun approvingNeedsTheFingerprintAndTheAnswerReachesTheMacAndActivity() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val id = serverAsks("sig-1", "Buy 0.05 BTC at market", now + 10 * 60_000L)
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf(id), fold.approvals.value.map { it.id })
        assertEquals("Asked 22:50 · expires in 10 min", fold.approvals.value.single().why)
        assertEquals(1, mac.approvals.value.size)

        // Without the fingerprint nothing is written.
        val refused = fold.approve(id, confirmedWithBiometric = false)
        assertFalse(refused.done)
        assertEquals(ApprovalRefusal.NEEDS_BIOMETRIC.line, refused.line)
        assertEquals(1, fold.approvals.value.size)

        val yes = fold.approve(id, confirmedWithBiometric = true)
        assertTrue(yes.done)
        assertEquals("Approved · Buy 0.05 BTC at market", yes.line)
        assertTrue(fold.approvals.value.isEmpty())
        val row = fold.activityView.value.days.flatMap { it.rows }.single { it.kind == ActivityKind.APPROVAL }
        assertEquals("Approved: Buy 0.05 BTC at market", row.summary)

        fold.syncNow(); mac.syncNow()
        assertTrue(mac.approvals.value.isEmpty())
        val late = mac.declineApproval(id)
        assertFalse(late.done)
        assertEquals(ApprovalRefusal.ANSWERED.line, late.line)
        assertEquals(1, mac.activityView.value.days.flatMap { it.rows }.count { it.kind == ActivityKind.APPROVAL })
    }

    @Test
    fun decliningTakesATapAndAnExpiredRequestCantBeApproved() = runTest {
        val fold = core("android")
        val a = serverAsks("sig-2", "Sell 0.05 BTC", now + 30 * 60_000L)
        val b = serverAsks("sig-3", "Buy 1 ETH", now + 5 * 60_000L)
        fold.syncNow()
        assertEquals(listOf(b, a), fold.approvals.value.map { it.id })
        assertEquals("Declined · Sell 0.05 BTC", fold.declineApproval(a).line)
        now += 6 * 60_000L
        assertEquals(ApprovalRefusal.EXPIRED.line, fold.approve(b, confirmedWithBiometric = true).line)
    }

    /** The inbox's fingerprint rule is the policy engine's: every kind's action needs biometric approval at any setting. */
    @Test
    fun everyKindIsOneThePolicyEngineHoldsAtApprovalWithTheFingerprint() {
        val domains = mapOf(ApprovalKind.TRADE to PolicyDomain.TRADING, ApprovalKind.PAYMENT to PolicyDomain.FINANCE, ApprovalKind.SHARE to PolicyDomain.DOCUMENTS)
        for (kind in ApprovalKind.entries) {
            val type = ActionType.valueOf(kind.action)
            val domain = domains.getValue(kind)
            val engine = PolicyEngine(PolicyConfig(levels = mapOf((domain to type) to AutonomyLevel.AUTO_UNDER_RULE), autoRules = mapOf("r" to type)))
            val d = engine.decide(ActionRequest(type, domain, Provenance.USER, reversible = true, ruleId = "r"))
            val approval = assertIs<PolicyDecision.RequireApproval>(d, kind.name)
            assertTrue(approval.biometric, kind.name)
            assertTrue(kind.biometric, kind.name)
        }
    }
}
