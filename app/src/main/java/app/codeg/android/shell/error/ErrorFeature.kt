package app.codeg.android.shell.error

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.codeg.android.BuildConfig
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns only main-document failures; API and asset failures remain with the website. */
class ErrorFeature : ShellFeature {
    private var reason by mutableStateOf<Int?>(null)
    private var status by mutableIntStateOf(0)
    private var progress by mutableIntStateOf(100)
    private var loading by mutableStateOf(false)
    private var webViewVersion by mutableStateOf<String?>(null)
    private var versionWarningDismissed by mutableStateOf(false)
    private var failedUrl: String? = null
    private var failedLoad = false
    private var retryJob: Job? = null
    private var loadTimeoutJob: Job? = null
    private val retryGate = PageRetryGate()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(host: ShellHost) {
        host.activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) stopRecovery(host)
        })
    }

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        clearError(host)
        loading = true
        progress = 0
        failedLoad = false
        val version = WebView.getCurrentWebViewPackage()?.versionName
        webViewVersion = version?.takeIf { it.substringBefore('.').toIntOrNull()?.let { major -> major < 111 } == true }
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {
        trace("started active=${view === host.webView} failed=$failedLoad")
        if (view !== host.webView) return
        // Chromium can start its internal error document after reporting a failure.
        // A different real navigation (including history back) may recover as well.
        if (failedLoad && url != failedUrl && !url.startsWith("chrome-error://")) {
            failedLoad = false
        }
        loading = reason == null
        progress = 0
        retryGate.begin()
        armLoadTimeout(host, view)
    }

    override fun onProgressChanged(host: ShellHost, view: WebView, progress: Int) {
        if (view !== host.webView || !loading) return
        this.progress = progress.coerceIn(0, 100)
        // Completion is decided by onPageFinished: Chromium also reports 100 for errors.
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        trace("finished active=${view === host.webView} failed=$failedLoad internal=${url.startsWith("chrome-error://")}")
        if (view !== host.webView) return
        loadTimeoutJob?.cancel()
        retryGate.finish()
        loading = false
        progress = 100
        if (url.startsWith("chrome-error://")) {
            if (reason == null) {
                failedUrl = url
                showError(host, R.string.shell_error_connection)
            }
        } else if (!failedLoad) {
            clearError(host)
        }
    }

    override fun onReceivedError(host: ShellHost, view: WebView, request: WebResourceRequest, error: WebResourceError) {
        trace("error active=${view === host.webView} main=${request.isForMainFrame} code=${error.errorCode}")
        if (view !== host.webView || !request.isForMainFrame) return
        failedUrl = request.url.toString()
        showError(host, when (error.errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> R.string.shell_error_dns
            WebViewClient.ERROR_TIMEOUT -> R.string.shell_error_timeout
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> R.string.shell_error_ssl
            else -> R.string.shell_error_connection
        })
    }

    override fun onReceivedHttpError(host: ShellHost, view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        trace("http active=${view === host.webView} main=${request.isForMainFrame} status=${response.statusCode}")
        if (view !== host.webView || !request.isForMainFrame || response.statusCode !in 500..599) return
        failedUrl = request.url.toString()
        status = response.statusCode
        showError(host, R.string.shell_error_http)
    }

    private fun showError(host: ShellHost, message: Int) {
        loadTimeoutJob?.cancel()
        retryGate.finish()
        failedLoad = true
        loading = false
        val firstFailure = reason == null
        reason = message
        if (!firstFailure) return // Failed retries must not reset the six-attempt budget.
        retryJob = host.scope.launch {
            var attempts = 0
            while (reason != null && attempts < 6) {
                delay(10_000)
                if (reason == null) return@launch
                if (retryGate.isLoading) continue
                if (!host.isWebContentVisible) continue
                attempts++
                retry(host)
            }
        }
        val manager = host.activity.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var recovered = false
            private var wasOffline = manager.activeNetwork?.let {
                manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } != true
            override fun onLost(network: Network) { wasOffline = true }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Initial registration also delivers the current network. Only retry a recovery.
                if (!wasOffline || recovered || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                recovered = true
                host.scope.launch { if (reason != null) retry(host) }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: SecurityException) {
            // Periodic and manual retries remain available if network observation is restricted.
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d("CodegShellError", message)
    }

    private fun retry(host: ShellHost) {
        val view = host.webView ?: return
        if (!host.isWebContentVisible || !retryGate.begin()) return
        // Never interrupt an already-running manual, network or automatic load.
        failedLoad = false
        host.reload()
        armLoadTimeout(host, view)
    }

    private fun armLoadTimeout(host: ShellHost, view: WebView) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = host.scope.launch {
            delay(45_000)
            if (host.webView !== view || !retryGate.isLoading) return@launch
            view.stopLoading()
            retryGate.finish()
            showError(host, R.string.shell_error_timeout)
        }
    }

    private fun openWebViewStore(host: ShellHost) {
        try {
            host.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview")))
        } catch (_: ActivityNotFoundException) {
            try {
                host.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(host.activity, R.string.shell_error_store_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopRecovery(host: ShellHost) {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        retryGate.finish()
        retryJob?.cancel()
        retryJob = null
        networkCallback?.let {
            host.activity.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    private fun clearError(host: ShellHost) {
        reason = null
        status = 0
        failedUrl = null
        stopRecovery(host)
    }

    override fun onResume(host: ShellHost) {
        if (reason != null) retry(host)
    }

    override fun onRenderProcessGone(host: ShellHost, view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (view === host.webView) {
            clearError(host)
            host.recreateWebView()
            Toast.makeText(host.activity, R.string.shell_error_reloaded, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    @Composable
    override fun Overlay(host: ShellHost) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            reason?.let { message ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⚠", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(24.dp))
                        Text(stringResource(R.string.shell_error_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text(if (message == R.string.shell_error_http) stringResource(message, status) else stringResource(message))
                        Spacer(Modifier.height(12.dp))
                        Text(host.currentServerUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(28.dp))
                        Button(onClick = { retry(host) }) { Text(stringResource(R.string.shell_error_retry)) }
                        TextButton(onClick = { host.openServerManager() }) { Text(stringResource(R.string.shell_error_switch)) }
                    }
                }
            }
            if (loading && reason == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            AnimatedVisibility(visible = loading, exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                if (progress == 0 || progress == 100) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                } else {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
            if (!versionWarningDismissed) webViewVersion?.let { version ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.shell_error_webview_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.shell_error_webview_version, version, 111))
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { openWebViewStore(host) }) { Text(stringResource(R.string.shell_error_webview_store)) }
                        TextButton(onClick = { versionWarningDismissed = true }) { Text(stringResource(R.string.shell_error_webview_continue)) }
                    }
                }
            }
        }
    }
}
