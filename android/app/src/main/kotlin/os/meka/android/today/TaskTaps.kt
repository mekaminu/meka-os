package os.meka.android.today

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import os.meka.core.domain.InstantTapRules
import os.meka.core.domain.InstantTaskRules
import os.meka.core.domain.ShutdownView
import os.meka.core.domain.TaskMove

/**
 * Taps feel instant for task moves in the shutdown and the brief ([InstantTaskRules]): one set of moves on their way, so
 * a tick in the brief leaves the shutdown's list in the same frame too. Each move is forgotten when its call returns (or
 * is cancelled); a failed one puts the task back and [onFail] gets the bar's line.
 */
object TaskTaps {
    private val _taps = MutableStateFlow(emptyMap<String, TaskMove>())
    val taps: StateFlow<Map<String, TaskMove>> = _taps.asStateFlow()

    fun move(scope: CoroutineScope, id: String, move: TaskMove, onFail: ((String) -> Unit)? = null, call: suspend () -> Unit) {
        _taps.update { InstantTaskRules.move(it, id, move) }
        land(scope, listOf(id), move, onFail, call)
    }

    /** "Move the rest to tomorrow": every row left in [v] leaves at once. */
    fun carryAll(scope: CoroutineScope, v: ShutdownView, onFail: ((String) -> Unit)? = null, call: suspend () -> Unit) {
        var ids: List<String> = emptyList()
        _taps.update { ids = InstantTaskRules.carried(it, v); InstantTaskRules.carryAll(it, v) }
        land(scope, ids, TaskMove.TOMORROW, onFail, call)
    }

    private fun land(scope: CoroutineScope, ids: List<String>, move: TaskMove, onFail: ((String) -> Unit)?, call: suspend () -> Unit) {
        scope.launch {
            try {
                runCatching { call() }.exceptionOrNull()?.let { e ->
                    if (e is CancellationException) throw e
                    onFail?.invoke(InstantTapRules.failedLine(InstantTaskRules.what(move), e.message))
                }
            } finally {
                _taps.update { InstantTaskRules.forgetAll(it, ids, move) }
            }
        }
    }
}
