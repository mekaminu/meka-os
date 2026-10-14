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
 * call log nobody is blocked.
 */
object AssistantHangups {
    suspend fun handle(app: MekaApplication) {
        val pending = app.core.assistantHangups()
        if (pending.isEmpty()) return
        val lookups = CallerLookup.allowed(app)
        val lists = app.captures.lists.value
        val own = CallerLookup.ownNumbers(app).map(People::key).toSet()
        for (h in pending) {
            val since = h.atMs - AssistantHangupRules.WINDOW_DAYS * 86_400_000L
            val known = lists.nameForNumber(h.number) != null ||
                People.key(h.number) in own ||
                CallerLookup.isContact(app, h.number) ||
                CallerLookup.calledRecently(app, h.number, h.atMs) ||
                CallScreenLog.claimedOfficial(app, h.number, since)
            val id = runCatching { app.core.autoBlockAssistantHangup(h, known, lookups) }.getOrNull() ?: continue
            runCatching { UnknownCallNotice.postAutoBlocked(app, h.number, id, os.meka.core.domain.AutoBlockTrigger.ASSISTANT_HANGUP, h.atMs) }
        }
    }
}
