package app.codeg.android.core.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批v160 W5: carries a horizontal-swipe step (+1 / -1) from the shell's gesture
 * layer to the task board's kanban. When the user picks 「切任务状态」 as the
 * Tasks tab's swipe semantics, the shell no longer routes the swipe to the
 * device sequence — it emits here, and the board steps between its four
 * columns (stopping at the ends, [segmentStepIndex]).
 *
 * A hot SharedFlow, not state: a step is a gesture that happened NOW — there is
 * nothing to replay for a board that was mid-recomposition (extraBufferCapacity
 * absorbs a fast double-swipe; DROP_OLDEST keeps tryEmit non-suspending).
 */
@Singleton
class TaskSegmentBus @Inject constructor() {
    private val _steps = MutableSharedFlow<Int>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val steps: SharedFlow<Int> = _steps.asSharedFlow()

    fun step(delta: Int) {
        _steps.tryEmit(delta)
    }
}
