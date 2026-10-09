package os.meka.android.today

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where typed text is saved from (a task's title, notes and steps; a habit's workout-app link). Not a screen's own
 * scope: those saves run as a screen closes (TextAutosave), and a screen's scope is cancelled as it goes, which would
 * drop the save it just launched (closing Search with a renamed task open, leaving Goals mid-edit).
 */
internal object TypingSaves {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun launch(save: suspend () -> Unit) {
        scope.launch { runCatching { save() } }
    }
}
