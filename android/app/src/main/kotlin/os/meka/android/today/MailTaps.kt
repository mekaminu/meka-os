package os.meka.android.today

import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import os.meka.core.domain.InstantMailRules
import os.meka.core.domain.InstantTapRules

/**
 * Taps feel instant, slice 20: an answer on a mail card in Needs you (From your mail, Time to chase, the finds,
 * Archived for you) hides its row (or the card) in this frame, then the core's write runs. A failure or a refusal brings
 * it back with the bar saying why; Undo on the bar brings it back at once too, before the core's undo lands.
 *
 * The work runs on the Needs you column's scope, not the card's: hiding a card's last row takes the card out of the
 * list, and the work must not be cancelled with it.
 */
internal class MailTaps(
    private val decided: MutableState<Set<String>>,
    private val scope: CoroutineScope,
    private val undo: (String, (suspend () -> Unit)?) -> Unit,
) {
    val hidden: Set<String> get() = decided.value

    /** The cards changed: forget the decided ids they no longer hold (their writes landed). */
    fun settle(present: Collection<String>) {
        val next = InstantTapRules.settle(decided.value, present)
        if (next != decided.value) decided.value = next
    }

    fun answer(id: String, what: String, undoWork: suspend () -> Unit, work: suspend () -> String) {
        decided.value = InstantTapRules.hide(decided.value, listOf(id))
        scope.launch {
            val said = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                back(id, InstantTapRules.failedLine(what, e.message))
                return@launch
            }
            if (InstantMailRules.refused(said)) {
                back(id, InstantTapRules.failedLine(what, said))
                return@launch
            }
            undo(said) {
                decided.value = InstantTapRules.restore(decided.value, listOf(id))
                runCatching { undoWork() }
            }
        }
    }

    private fun back(id: String, line: String) {
        decided.value = InstantTapRules.restore(decided.value, listOf(id))
        undo(line, null)
    }
}
