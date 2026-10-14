package os.meka.android.work

import os.meka.android.MekaApplication
import os.meka.core.domain.AssistantHangupRules
import os.meka.core.domain.People

/**
 * Spam calls still ring, slice 3: after each sync, the callers the server says reached the call assistant and hung up
 * without a message are looked at here, with what only the phone knows — its contacts, anyone Meka called in the last
 * 90 days, the family and always-notify lists, its own numbers, and whether the caller's network name claimed an
 * official body (all read here, never sent). A stranger goes on the block list as an automatic action with Undo in
 * Activity and the quiet "Blocked … · it won't ring again" line with Undo. Without permission to read contacts and the
 * call log nobody is blocked. Call notifications, slice 5: the call assistant's automated messages from strangers too.
 */
object AssistantHangups {
    suspend fun handle(app: MekaApplication) {
        val pending = app.core.assistantHangups()
        // Call notifications, slice 5: an automated message (a keypad press, one word, a second or two) from a
        // stranger follows the same rules as a hang-up.
        val automated = runCatching { app.core.automatedCalls() }.getOrDefault(emptyList())
        if (pending.isEmpty() && automated.isEmpty()) return
        val lookups = CallerLookup.allowed(app)
        val lists = app.captures.lists.value
        val own = CallerLookup.ownNumbers(app).map(People::key).toSet()
        fun known(h: os.meka.core.domain.AssistantHangup): Boolean {
            val since = h.atMs - AssistantHangupRules.WINDOW_DAYS * 86_400_000L
            return lists.nameForNumber(h.number) != null ||
                People.key(h.number) in own ||
                CallerLookup.isContact(app, h.number) ||
                CallerLookup.calledRecently(app, h.number, h.atMs) ||
                CallScreenLog.claimedOfficial(app, h.number, since)
        }
        for (h in pending) {
            val id = runCatching { app.core.autoBlockAssistantHangup(h, known(h), lookups) }.getOrNull() ?: continue
            runCatching { UnknownCallNotice.postAutoBlocked(app, h.number, id, os.meka.core.domain.AutoBlockTrigger.ASSISTANT_HANGUP, h.atMs) }
        }
        for (h in automated) {
            val id = runCatching { app.core.autoBlockAutomatedCall(h, known(h), lookups) }.getOrNull() ?: continue
            runCatching { UnknownCallNotice.postAutoBlocked(app, h.number, id, os.meka.core.domain.AutoBlockTrigger.AUTOMATED, h.atMs) }
        }
    }
}
