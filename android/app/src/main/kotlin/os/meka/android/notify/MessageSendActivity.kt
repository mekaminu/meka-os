package os.meka.android.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.MekaApplication
import os.meka.android.work.LiveReplies
import os.meka.core.domain.LeavingRules
import os.meka.core.domain.LeavingTap
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticeAction
import os.meka.core.domain.NotifyRules

/**
 * Send and Edit on a "Leaving now" notification ([LeavingRules]). Shows nothing: an activity rather than a broadcast so
 * it may open WhatsApp (Android blocks notification trampolines from receivers).
 *
 * Level 3, hard constraint: this only runs from Meka's tap, and Send sends the notification's own words — the core
 * checks they are still today's draft ([LeavingTap.Send]); if the draft moved on, the notification shows the new words
 * and nothing is sent ([LeavingTap.Changed]). Send goes through a live WhatsApp notification from her (its own Reply
 * action, [LiveReplies.sendTo]); otherwise, and for Edit, WhatsApp's official click-to-chat link opens her chat with the
 * words in, and Meka taps send there. No WhatsApp library.
 */
class MessageSendActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY)
        val postId = intent.getStringExtra(EXTRA_POST_ID) ?: key
        val text = intent.getStringExtra(EXTRA_TEXT)
        val action = NotifyRules.actionFromName(intent.getStringExtra(EXTRA_ACTION))
        if (key == null || postId == null || text == null || action == null) { finish(); return }
        val app = application as MekaApplication
        scope.launch {
            try {
                val edit = action == NoticeAction.EDIT_MESSAGE
                when (val tap = runCatching { app.core.leavingTap(key, text, edit) }.getOrNull()) {
                    is LeavingTap.Send -> {
                        val sent = !edit && withContext(Dispatchers.Default) { LiveReplies.sendTo(this@MessageSendActivity, tap.to, tap.text) }
                        if (sent) app.governor.postSent(postId, LeavingRules.sentTitle(tap.to), tap.text)
                        else { openChat(app, tap.to, tap.text); app.governor.cancel(postId) }
                    }
                    is LeavingTap.Changed -> app.governor.repost(tap.notice)
                    null -> app.governor.cancel(postId)
                }
            } finally { finish() }
        }
    }

    /** Her chat with the words filled in (click-to-chat with the Family entry's number), else WhatsApp with them copied. */
    private fun openChat(app: MekaApplication, to: String, text: String) {
        val lists = app.captures.lists.value
        val entry = LeavingRules.familyEntry(to, lists.family + lists.alwaysNotify)
        val link = entry?.let { e -> lists.numbers[e].orEmpty().firstNotNullOfOrNull { LeavingRules.waLink(it, text) } }
        if (link != null) {
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(open) }.isSuccess) return
        }
        LiveReplies.copyText(this, text, "MEKA message")
        packageManager.getLaunchIntentForPackage("com.whatsapp")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { startActivity(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_KEY = "os.meka.message.key"
        private const val EXTRA_POST_ID = "os.meka.message.post"
        private const val EXTRA_TEXT = "os.meka.message.text"
        private const val EXTRA_ACTION = "os.meka.message.action"

        /** Send or Edit on [n]: its key, where it is posted, and its exact words. */
        fun intent(context: Context, n: Notice, action: NoticeAction): Intent =
            Intent(context, MessageSendActivity::class.java)
                .putExtra(EXTRA_KEY, n.key)
                .putExtra(EXTRA_POST_ID, n.postId)
                .putExtra(EXTRA_TEXT, n.text)
                .putExtra(EXTRA_ACTION, NotifyRules.actionName(action))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
