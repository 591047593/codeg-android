package app.codeg.android.shell.dialog

import android.graphics.Bitmap
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Draws the page's `window.confirm()` / `window.prompt()` calls.
 *
 * codeg web uses both on paths a phone user hits regularly:
 * - `src/contexts/workspace-context.tsx` confirms before closing file tabs with
 *   unsaved edits (four call sites);
 * - `src/lib/platform.ts` prompts for a server-side directory path, because a
 *   browser cannot pick one (`open({directory: true})`).
 *
 * A `WebChromeClient` that answers these without drawing anything makes both
 * silent no-ops — the prompt returns `null`, the confirm returns `false` — so
 * the tabs close without asking and "choose folder" does nothing. This feature
 * answers only within the server origin; anything else is cancelled by the
 * shell's default.
 */
class DialogFeature : ShellFeature {
    private var confirmMessage by mutableStateOf<String?>(null)
    private var prompt by mutableStateOf<PromptRequest?>(null)
    private var confirmResult: JsResult? = null

    private class PromptRequest(val message: String, val initial: String, val result: JsPromptResult)

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {
        finishAll()
    }

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        finishAll()
    }

    override fun onRenderProcessGone(
        host: ShellHost,
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        finishAll()
        return false
    }

    override fun onBack(host: ShellHost): Boolean {
        if (confirmResult == null && prompt == null) return false
        // Back cancels, exactly as the platform's own dialog does.
        finishAll()
        return true
    }

    private fun finishAll() {
        confirmResult?.let { result -> runCatching { result.cancel() } }
        confirmResult = null
        confirmMessage = null
        prompt?.let { pending -> runCatching { pending.result.cancel() } }
        prompt = null
    }

    private fun inServer(host: ShellHost, url: String?): Boolean {
        val server = host.currentServerUrl?.toHttpUrlOrNull() ?: return false
        val page = url?.toHttpUrlOrNull() ?: return false
        return page.scheme == server.scheme && page.host == server.host && page.port == server.port
    }

    override fun onJsConfirm(host: ShellHost, view: WebView, url: String, message: String, result: JsResult): Boolean {
        if (host.webView !== view || !inServer(host, url)) return false
        // One at a time: a page that stacks confirm() calls gets the newest and
        // the older is declined (Chrome does the same for a new dialog).
        finishAll()
        confirmResult = result
        confirmMessage = message
        return true
    }

    override fun onJsPrompt(
        host: ShellHost,
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean {
        if (host.webView !== view || !inServer(host, url)) return false
        finishAll()
        prompt = PromptRequest(message, defaultValue, result)
        return true
    }

    @Composable
    override fun Overlay(host: ShellHost) {
        confirmMessage?.let { message ->
            AlertDialog(
                onDismissRequest = {
                    confirmResult?.cancel()
                    confirmResult = null
                    confirmMessage = null
                },
                shape = RoundedCornerShape(32.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                text = {
                    Text(
                        message,
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmResult?.confirm()
                        confirmResult = null
                        confirmMessage = null
                    }) { Text(stringResource(R.string.shell_dialog_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmResult?.cancel()
                        confirmResult = null
                        confirmMessage = null
                    }) { Text(stringResource(R.string.shell_dialog_cancel)) }
                },
            )
        }
        prompt?.let { pending ->
            var value by remember(pending) { mutableStateOf(pending.initial) }
            AlertDialog(
                onDismissRequest = {
                    pending.result.cancel()
                    prompt = null
                },
                shape = RoundedCornerShape(32.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(pending.message) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            shape = RoundedCornerShape(32.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pending.result.confirm(value)
                        prompt = null
                    }) { Text(stringResource(R.string.shell_dialog_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pending.result.cancel()
                        prompt = null
                    }) { Text(stringResource(R.string.shell_dialog_cancel)) }
                },
            )
        }
    }
}
