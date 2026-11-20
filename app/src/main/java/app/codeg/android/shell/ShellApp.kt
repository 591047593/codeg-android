package app.codeg.android.shell

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.codeg.android.shell.display.ShellTheme

/**
 * Root UI: no server yet → server setup; server manager open → manager; otherwise the
 * WebView with every feature's overlay on top.
 */
@Composable
fun ShellApp(host: ShellActivityHost) {
    val features = host.features
    val server by features.server.current.collectAsState()
    val managerOpen by host.serverManagerOpen.collectAsState()
    val generation by host.webViewGeneration.collectAsState()
    val serversReady by features.server.ready.collectAsState()
    val startupFailed by features.server.startupFailed.collectAsState()
    ShellTheme {
        when {
            !serversReady || startupFailed -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(if (startupFailed) R.string.shell_server_startup_error else R.string.shell_server_loading))
                    if (startupFailed) Button(onClick = { features.server.retryStartup(host) }) {
                        Text(stringResource(R.string.shell_server_retry))
                    }
                }
            }
            server == null -> features.server.SetupScreen(host)
            else -> {
                var modifier: Modifier = Modifier.fillMaxSize()
                features.all.forEach { modifier = modifier.then(it.webViewModifier(host)) }
                Box(Modifier.fillMaxSize()) {
                    // A new generation (renderer crash, server switch) builds a brand-new WebView.
                    key(generation, server) {
                        AndroidView(
                            modifier = modifier,
                            factory = { context -> host.createWebView(context) },
                            onRelease = { host.releaseWebView(it) },
                        )
                    }
                    if (!managerOpen) features.all.forEach { it.Overlay(host) }
                    if (managerOpen) features.server.ManagerScreen(host, onClose = { host.closeServerManager() })
                }
            }
        }
        // Update checks also report results while the native manager is open.
        if (managerOpen || server == null) features.update.Overlay(host)
    }
}

internal fun WebView.applyShellLayout() {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
