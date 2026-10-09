package os.meka.android.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MekaApplication
import os.meka.core.domain.GroupDigestRules
import os.meka.core.facade.GistRead
import java.time.LocalTime

/**
 * The group digest's gist (V1, messages slice 4b): once a digest time (12:30, 18:30) has come, the busy groups' chatter
 * this phone keeps goes to MEKA's AI in **one** call ([os.meka.core.facade.MekaCore.gistGroupDigest]) and every group in
 * the digest gets a synced card (count, who wrote, the gist; never the messages), so the Mac shows the digest too.
 * Run by the listener after each message and by Needs you when the digest comes due; once a slot is done, it costs
 * nothing (the core checks the slot's cards first). One at a time, so two callers never both make the call.
 */
object DigestGist {
    private val lock = Mutex()

    suspend fun run(meka: MekaApplication): GistRead? {
        val t = LocalTime.now()
        if (GroupDigestRules.slotLabel(t.hour * 60 + t.minute) == null) return null
        return lock.withLock {
            val store = meka.captures
            runCatching {
                meka.core.gistGroupDigest(store.digest.value, store.triageSettings.value, store.digestSeen.value)
            }.getOrNull()
        }
    }
}
