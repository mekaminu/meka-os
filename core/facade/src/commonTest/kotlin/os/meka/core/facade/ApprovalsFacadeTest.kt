package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.ApprovalFields
import os.meka.core.domain.ApprovalKeyFields
import os.meka.core.domain.ApprovalKind
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
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

    /** What the app does around the fingerprint prompt: prepare, sign the text with the approval key, approve. */
    private suspend fun signAndApprove(c: MekaCore, id: String, signature: String = "MEUCIQ-signed", key: String = "MFkw-fold-key") =
        c.prepareApproval(id).let { p ->
            if (p.refusal != null) ApprovalAnswer(false, p.refusal!!)
            else c.approve(id, p.decisionKey, p.decidedAtMs, key, signature, "Fold")
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

        // Without the approval key's signature (the fingerprint unlocks it) nothing is written.
        val refused = signAndApprove(fold, id, signature = "")
        assertFalse(refused.done)
        assertEquals(ApprovalRefusal.NEEDS_BIOMETRIC.line, refused.line)
        assertEquals(1, fold.approvals.value.size)

        val prep = fold.prepareApproval(id)
        assertEquals(null, prep.refusal)
        val message = prep.message!!
        assertTrue(message.startsWith("MEKA-APPROVAL1\n$id\napproved\ntrade\nKestrel\n${prep.decisionKey}\n${prep.decidedAtMs}\n"))
        assertTrue(message.endsWith("\nandroid\n" + ApprovalRules.shownHash("Buy 0.05 BTC at market", listOf("About £2,410"))))
        // A prep left for more than five minutes (the prompt stayed open) has to be signed again.
        now += 6 * 60_000L
        assertEquals(ApprovalRefusal.STALE.line, fold.approve(id, prep.decisionKey, prep.decidedAtMs, "MFkw-fold-key", "sig", "Fold").line)
        now -= 6 * 60_000L
        val yes = fold.approve(id, prep.decisionKey, prep.decidedAtMs, "MFkw-fold-key", "MEUCIQ-signed", "Fold")
        assertTrue(yes.done)
        assertEquals("Approved · Buy 0.05 BTC at market", yes.line)
        assertTrue(fold.approvals.value.isEmpty())
        val row = fold.activityView.value.days.flatMap { it.rows }.single { it.kind == ActivityKind.APPROVAL }
        assertEquals("Approved: Buy 0.05 BTC at market", row.summary)

        fold.syncNow(); mac.syncNow()
        assertTrue(mac.approvals.value.isEmpty())
        // What the server reads: the signed yes and the Fold's own approval key, written by the Fold.
        val answered = ops.after("hh", 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == EntityTypes.APPROVAL && it.entityId == id }
        assertEquals(prep.decisionKey, answered.single { it.field == ApprovalFields.DECISION_KEY }.value.textOrNull)
        assertEquals("MEUCIQ-signed", answered.single { it.field == ApprovalFields.ANSWER_SIGNATURE }.value.textOrNull)
        assertEquals("android", answered.single { it.field == ApprovalFields.ANSWER_SIGNATURE }.deviceId)
        val key = ops.after("hh", 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == EntityTypes.APPROVAL_KEY }
        assertEquals(setOf("android"), key.map { it.entityId }.toSet())
        assertEquals("MFkw-fold-key", key.single { it.field == ApprovalKeyFields.PUBLIC_KEY }.value.textOrNull)
        assertFalse(fold.registerApprovalKey("MFkw-fold-key", "Fold")) // already known: nothing written
        assertTrue(mac.registerApprovalKey("MFkw-mac-key", "Mac"))
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
        val prep = fold.prepareApproval(b)
        assertEquals(ApprovalRefusal.EXPIRED.line, prep.refusal)
        assertEquals(null, prep.message) // nothing to sign: the fingerprint is never asked
        assertEquals(ApprovalRefusal.GONE.line, fold.prepareApproval("nope").refusal)
    }

    /** Slice 2: a server-written request is a Needs a decision notice on each device; answered on one, both take it down. */
    @Test
    fun aRequestNotifiesBothDevicesAndAnAnswerTakesTheNotificationDown() = runTest {
        now = 1_791_745_200_000L // Sun 11 Oct 2026, 20:00 in London (before quiet hours)
        val fold = core("android")
        val mac = core("mac")
        val id = serverAsks("sig-4", "Buy 0.05 BTC at market", now + 60 * 60_000L)
        fold.syncNow(); mac.syncNow()
        val foldPost = fold.governNotifications(null, DeviceAlerts.ALL)
        val macPost = mac.governNotifications(null, DeviceAlerts.ALL)
        for (r in listOf(foldPost, macPost)) {
            val n = r.post.single { it.source == NoticeSource.APPROVAL }
            assertEquals(NoticeTier.ACTION, n.tier)
            assertEquals("Trade · Kestrel", n.title)
            assertEquals("Buy 0.05 BTC at market · answer by 21:00", n.text)
            assertEquals(NoticeTarget.NEEDS_YOU, n.target)
            assertEquals("approval:$id", n.postId)
        }
        assertTrue(signAndApprove(fold, id).done)
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("approval:$id"), fold.governNotifications(foldPost.stateEncoded, DeviceAlerts.ALL).withdraw)
        assertEquals(listOf("approval:$id"), mac.governNotifications(macPost.stateEncoded, DeviceAlerts.ALL).withdraw)
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
