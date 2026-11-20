package app.codeg.android.shell.server

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.webkit.WebView
import androidx.compose.runtime.Composable
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.ServerStore
import app.codeg.android.core.security.SecretStore
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServerDependencies {
    fun serverStore(): ServerStore
    fun secretStore(): SecretStore
}

internal fun serverOrigin(input: String): String? {
    val text = input.trim()
    val url = (if ("://" in text) text else "http://$text").toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
}

class ServerFeature : ShellFeature {
    private val state = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = state
    internal val profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    internal var selectedId: String? = null
        private set
    private lateinit var store: ServerStore
    private lateinit var secrets: SecretStore
    private val migrating = mutableSetOf<String>()
    internal val ready = MutableStateFlow(false)
    internal val startupFailed = MutableStateFlow(false)
    private var startupJob: Job? = null

    override fun onCreate(host: ShellHost) {
        val deps = EntryPointAccessors.fromApplication(host.activity.applicationContext, ServerDependencies::class.java)
        store = deps.serverStore()
        secrets = deps.secretStore()
        retryStartup(host)
        val intent = Intent(ACTION).setClassName(host.activity.packageName, "app.codeg.android.MainActivity")
        // Launcher shortcuts are optional; restrictions must not prevent startup.
        runCatching {
            host.activity.getSystemService(ShortcutManager::class.java).addDynamicShortcuts(listOf(
                ShortcutInfo.Builder(host.activity, "shell_server_manager")
                    .setShortLabel(host.activity.getString(R.string.shell_server_switch))
                    .setIntent(intent).build(),
            ))
        }
    }

    internal fun retryStartup(host: ShellHost) {
        if (startupJob?.isActive == true) return
        startupFailed.value = false
        startupJob = host.scope.launch {
            try { refresh(); ready.value = true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { startupFailed.value = true }
        }
    }

    /** Restoration must not race the startup refresh or treat failure as an empty store. */
    internal suspend fun awaitInitialization(): Boolean {
        startupJob?.join()
        return ready.value && !startupFailed.value
    }

    internal suspend fun savedProfile(origin: String): ServerProfile? =
        store.profiles.first().firstOrNull { serverOrigin(it.baseUrl) == origin }

    private suspend fun refresh() {
        val list = store.profiles.first()
        val selected = store.selectedId.first()
        val profile = list.firstOrNull { it.id == selected } ?: list.firstOrNull()
        if (profile?.id != selected) store.select(profile?.id)
        profiles.value = list
        selectedId = profile?.id
        state.value = profile?.baseUrl?.let(::serverOrigin)
    }

    internal suspend fun save(profile: ServerProfile) {
        val old = profiles.value.firstOrNull { it.id == profile.id }
        // A token belongs to its original origin, never to an edited address.
        if (old != null && serverOrigin(old.baseUrl) != profile.baseUrl) secrets.remove(profile.id)
        store.upsert(profile)
        refresh()
    }

    internal suspend fun select(id: String) { store.select(id); refresh() }
    internal suspend fun delete(id: String) { store.delete(id); secrets.remove(id); refresh() }

    override fun onIntent(host: ShellHost, intent: Intent): Boolean {
        if (intent.action != ACTION) return false
        host.openServerManager()
        return true
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        val origin = current.value ?: return
        if (serverOrigin(url) != origin || serverOrigin(view.url ?: "") != origin || host.webView !== view) return
        val profile = profiles.value.firstOrNull { it.id == selectedId } ?: return
        val prefs = host.activity.getSharedPreferences("shell_server_migrations", Context.MODE_PRIVATE)
        val key = "${profile.id}:$origin"
        if (prefs.getBoolean(key, false) || !migrating.add(key)) return
        host.scope.launch {
            val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { secrets.get(profile.id)?.takeIf { it.isNotBlank() } }
            if (host.webView !== view || current.value != origin || serverOrigin(view.url ?: "") != origin) {
                migrating.remove(key)
                return@launch
            }
            val script = """
                (function() {
                  if (location.origin !== ${JSONObject.quote(origin)}) return 'retry';
                  try {
                    if (!localStorage.getItem('codeg_token') && ${token != null}) {
                      localStorage.setItem('codeg_token', ${JSONObject.quote(token ?: "")});
                      return 'reload';
                    }
                    return 'done';
                  } catch (_) { return 'retry'; }
                })()
            """.trimIndent()
            view.evaluateJavascript(script) { result ->
                migrating.remove(key)
                if (result == "\"done\"" || result == "\"reload\"") {
                    prefs.edit().putBoolean(key, true).apply()
                    if (result == "\"reload\"" && host.webView === view && current.value == origin && serverOrigin(view.url ?: "") == origin) view.reload()
                }
            }
        }
    }

    @Composable
    fun SetupScreen(host: ShellHost) = ServerEditor(this, host, null, onDone = {})

    @Composable
    fun ManagerScreen(host: ShellHost, onClose: () -> Unit) = ServerManager(this, host, onClose)

    private companion object { const val ACTION = "app.codeg.android.action.MANAGE_SERVERS" }
}
