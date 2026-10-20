package app.codeg.android.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A coroutine scope that outlives any single screen.
 *
 * Needed for work that must complete even though the component that started it is
 * going away. The canonical case is "stop the agent on the server": the user taps Stop
 * and immediately presses back, which clears the ViewModel and cancels
 * `viewModelScope` before the HTTP request is written. The server would then keep the
 * turn running with nobody watching it.
 *
 * Scoped to the process rather than `GlobalScope`: it is injected, so it is a real
 * dependency that tests can substitute, and a [SupervisorJob] keeps one failed
 * fire-and-forget call from cancelling its siblings.
 */
@Singleton
class ApplicationScope @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Run [block] detached from any screen's lifecycle. */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}
