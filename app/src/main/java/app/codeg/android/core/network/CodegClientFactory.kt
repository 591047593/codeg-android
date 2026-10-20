package app.codeg.android.core.network

import app.codeg.android.core.common.DispatcherProvider
import dagger.Lazy
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds per-server [CodegClient] / [EventStream] instances over the shared
 * (singleton) Ktor [HttpClient]. The base URL + token are runtime values chosen
 * by the user when a server is selected, so the clients are cheap value objects
 * created on demand — mirroring the iOS value-type `CodegClient`.
 *
 * The [HttpClient] is injected as [Lazy] so the OkHttp engine (and the Ktor /
 * OkHttp class graph) is only constructed on the first actual network call, which
 * keeps engine construction off the cold-start first-frame critical path: this
 * factory sits in the singleton graph reached when the first ViewModel is created
 * during composition, and building the engine there would otherwise stall the
 * first frame (and, under heavy CPU contention, risk a startup ANR).
 *
 * That first call is marshalled off the main thread by the injected
 * [DispatcherProvider.io] rather than relying on the caller being on a background
 * dispatcher — every ViewModel here launches on `viewModelScope`, which is `Main`.
 */
@Singleton
class CodegClientFactory @Inject constructor(
    private val http: Lazy<HttpClient>,
    private val dispatchers: DispatcherProvider,
) {
    fun client(baseUrl: String, token: String): CodegClient =
        CodegClient(baseUrl, token, http.get(), dispatchers.io)

    fun eventStream(baseUrl: String, token: String): EventStream =
        EventStream(baseUrl, token, http.get())
}
