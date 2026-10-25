package os.meka.android.goals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import os.meka.core.domain.GoalsView
import os.meka.core.domain.InstantHabitRules
import os.meka.core.domain.InstantTapRules
import os.meka.core.facade.MekaCore

/**
 * Taps feel instant for habits ([InstantHabitRules]): one set of ticks on their way for the whole app, so a tick on the
 * face's dots shows on the chips, Goals and Needs you in the same frame too. Each tick is forgotten when its call
 * returns (or is cancelled); a failed one puts the habit back and [onFail] gets the bar's line.
 */
object HabitTaps {
    private val _taps = MutableStateFlow(emptyMap<String, Boolean>())
    val taps: StateFlow<Map<String, Boolean>> = _taps.asStateFlow()

    fun tick(scope: CoroutineScope, core: MekaCore?, id: String, done: Boolean, onFail: ((String) -> Unit)? = null) {
        if (core == null) return
        _taps.update { InstantHabitRules.tick(it, id, done) }
        scope.launch {
            try {
                val r = runCatching { core.setHabitDone(id, done) }
                r.exceptionOrNull()?.let { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    onFail?.invoke(InstantTapRules.failedLine(if (done) "Tick" else "Untick", e.message))
                }
            } finally {
                _taps.update { InstantHabitRules.forget(it, id, done) }
            }
        }
    }
}

/** The core's goals with the habit ticks still on their way drawn over them. */
@Composable
fun shownGoals(goals: StateFlow<GoalsView>): GoalsView {
    val core = goals.collectAsState().value
    val taps = HabitTaps.taps.collectAsState().value
    return remember(core, taps) { InstantHabitRules.shown(core, taps) }
}
