package os.meka.android.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MekaApplication
import os.meka.core.facade.ChatSummaryRead

/**
 * A busy chat's one-line summary (messages grouped per chat, slice 3c): the open chats with four or more messages and
 * no summary for them yet go to MEKA's AI in **one** call ([os.meka.core.facade.MekaCore.summariseChats]) — MEKA's gist
 * of each message, never the text — and the summaries sync so the Mac's cards show them too. Run by the listener after
 * triaging new messages and by Needs you when its chats change; when nothing is due it costs nothing (the core checks
 * first). One at a time, so two callers never both make the call.
 */
object ChatSummarise {
    private val lock = Mutex()

    suspend fun run(meka: MekaApplication): ChatSummaryRead? = lock.withLock {
        runCatching { meka.core.summariseChats(meka.captures.triageSettings.value) }.getOrNull()
    }
}
