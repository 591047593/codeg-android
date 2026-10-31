package app.codeg.android.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * How long any single HTTP response may stay silent before it is aborted.
     *
     * This was 30s, which is shorter than several genuinely slow endpoints — an
     * `acp_connect` that cold-starts an agent CLI, `acp_describe_agent_options`
     * (documented in [app.codeg.android.core.network.CodegClient] as probing the
     * agent and slow), `clone_repository`, and expert installs all legitimately
     * take longer. A 30s ceiling turned those into a `Transport` error the user
     * reads as "the server is down" while it was still working.
     */
    private val READ_TIMEOUT: Duration = Duration.ofMinutes(5)

    /**
     * The shared Ktor client (OkHttp engine) used for every server. Timeouts are
     * configured on the OkHttp engine rather than via Ktor's `HttpTimeout`
     * plugin: OkHttp's WebSocket ignores the read timeout once upgraded and uses
     * the ping interval for liveness, so a long-idle agent turn over the event
     * stream is never torn down — while ordinary HTTP POSTs still get a ceiling.
     * `expectSuccess = false` so [app.codeg.android.core.network.CodegClient]
     * maps non-2xx responses to its own typed errors.
     */
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        // Only the request ceiling is set here. Deliberately NOT installing a socket
        // timeout: Ktor's socketTimeout applies per read and would also cut the
        // long-lived `/ws/events` upgrade, which is meant to sit idle between
        // events. OkHttp's own read timeout below covers stalled HTTP bodies, and
        // OkHttp's WebSocket ignores it after the upgrade.
        install(HttpTimeout) {
            requestTimeoutMillis = READ_TIMEOUT.toMillis()
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            // Deliberately no `maxFrameSize`: the OkHttp engine rejects it at connect time
            // with "Max frame size switch is not supported in OkHttp engine", which broke
            // every prompt. The frame-size guard lives in EventStream instead, where the
            // text is already materialised and the check is engine-independent.
        }
        install(Logging) {
            level = LogLevel.NONE
        }
        engine {
            config {
                retryOnConnectionFailure(true)
                connectTimeout(Duration.ofSeconds(30))
                readTimeout(READ_TIMEOUT)
                writeTimeout(Duration.ofSeconds(30))
                pingInterval(Duration.ofSeconds(20))
            }
        }
    }
}
