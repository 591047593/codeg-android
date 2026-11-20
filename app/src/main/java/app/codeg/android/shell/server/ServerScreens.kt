package app.codeg.android.shell.server

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import app.codeg.android.shell.display.ShellTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.shell.ShellHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@Composable
internal fun ServerManager(feature: ServerFeature, host: ShellHost, onClose: () -> Unit) {
    val profiles by feature.profiles.collectAsState()
    val current by feature.current.collectAsState()
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServerProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (adding || editing != null) {
        ServerEditor(feature, host, editing, onDone = { adding = false; editing = null }, onCancel = { adding = false; editing = null })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(enabled = !busy, onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.shell_server_close))
                }
                Text(
                    stringResource(R.string.shell_server_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                profiles.forEach { profile ->
                    ServerCard(
                        profile = profile,
                        selected = serverOrigin(profile.baseUrl) == current && feature.selectedId == profile.id,
                        enabled = !busy,
                        onSelect = {
                            busy = true
                            scope.launch {
                                try { feature.select(profile.id); onClose() }
                                catch (_: java.io.IOException) { failed = true }
                                finally { busy = false }
                            }
                        },
                        onEdit = { editing = profile },
                        onDelete = { deleting = profile },
                    )
                }
                if (failed) Text(
                    stringResource(R.string.shell_server_storage_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { host.checkForUpdates() }, modifier = Modifier.fillMaxWidth(), shape = ServerPill) {
                Text(stringResource(R.string.shell_update_check))
            }
            Button(
                enabled = !busy, onClick = { adding = true }, shape = ServerPill,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.shell_server_add)) }
        }
    }
    deleting?.let { profile ->
        AlertDialog(shape = ServerPill, containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { if (!busy) deleting = null }, title = { Text(stringResource(R.string.shell_server_delete)) },
            text = { Text(stringResource(R.string.shell_server_delete_confirm, profile.name.ifBlank { profile.baseUrl })) },
            confirmButton = { Button(
                enabled = !busy, shape = ServerPill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ), onClick = {
                busy = true
                scope.launch {
                    try {
                        feature.delete(profile.id)
                        deleting = null
                        if (feature.profiles.value.isEmpty()) onClose()
                    } catch (_: java.io.IOException) { failed = true; deleting = null } finally { busy = false }
                }
            }) { Text(stringResource(R.string.shell_server_delete)) } },
            dismissButton = { OutlinedButton(enabled = !busy, shape = ServerPill,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), onClick = { deleting = null }) { Text(stringResource(R.string.shell_server_cancel)) } })
    }
}

@Composable
internal fun ServerEditor(feature: ServerFeature, host: ShellHost, profile: ServerProfile?, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var address by rememberSaveable(profile?.id) { mutableStateOf(profile?.baseUrl ?: "") }
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<ServerProfile?>(null) }
    val scope = rememberCoroutineScope()
    fun persist(value: ServerProfile) {
        busy = true
        scope.launch {
            try { feature.save(value); pending = null; onDone() }
            catch (_: java.io.IOException) { pending = null; error = R.string.shell_server_storage_error }
            finally { busy = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(if (profile == null) R.string.shell_server_add else R.string.shell_server_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ServerInput(
                value = address, onValueChange = { address = it; error = null },
                label = stringResource(R.string.shell_server_address), enabled = !busy,
                error = error?.let { stringResource(it) }, keyboardType = KeyboardType.Uri,
            )
            ServerInput(
                value = name, onValueChange = { name = it },
                label = stringResource(R.string.shell_server_name), enabled = !busy,
            )
            Button(enabled = !busy, shape = ServerPill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                val origin = serverOrigin(address)
                if (origin == null) { error = R.string.shell_server_invalid; return@Button }
                val value = ServerProfile(profile?.id ?: UUID.randomUUID().toString(), name.trim(), origin)
                busy = true
                scope.launch {
                    val problem = probe(origin)
                    busy = false
                    if (problem == null) persist(value) else { error = problem; pending = value }
                }
            }) { Text(stringResource(if (busy) R.string.shell_server_checking else R.string.shell_server_save)) }
            if (onCancel == null) OutlinedButton(onClick = { host.checkForUpdates() }, shape = ServerPill, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shell_update_check))
            }
            onCancel?.let { OutlinedButton(
                enabled = !busy, shape = ServerPill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), onClick = it) { Text(stringResource(R.string.shell_server_cancel)) } }
        }
    }
    pending?.let { value ->
        AlertDialog(shape = ServerPill, containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { if (!busy) pending = null }, title = { Text(stringResource(R.string.shell_server_probe_failed)) },
            text = { Text(stringResource(error ?: R.string.shell_server_unreachable)) },
            confirmButton = { Button(enabled = !busy, shape = ServerPill, onClick = { persist(value) }) { Text(stringResource(R.string.shell_server_save_anyway)) } },
            dismissButton = { OutlinedButton(enabled = !busy, shape = ServerPill,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), onClick = { pending = null }) { Text(stringResource(R.string.shell_server_cancel)) } })
    }
}

// Match the web rounded-2xl card and rounded-4xl controls using shared theme tokens.
private val ServerPill = RoundedCornerShape(32.dp)

@Composable
private fun ServerCard(
    profile: ServerProfile,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }
    val title = profile.name.ifBlank { profile.baseUrl }
    Card(
        onClick = onSelect, enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected) Surface(
                    shape = ServerPill,
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Text(
                        stringResource(R.string.shell_server_current),
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Box {
                IconButton(enabled = enabled, onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.shell_server_more, title))
                }
                DropdownMenu(expanded = menuOpen && enabled, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shell_server_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shell_server_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            shape = ServerPill, interactionSource = interaction, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.then(
                if (focused) Modifier.border(
                    3.dp,
                    (if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.3f),
                    ServerPill,
                ) else Modifier,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        error?.let { message ->
            Text(
                message, modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Preview uses the same card/fields and ShellTheme as the live screens. */
@Preview(name = "Server controls · light", showBackground = true, widthDp = 360, locale = "zh")
@Preview(name = "Server controls · dark", showBackground = true, widthDp = 360, locale = "zh", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Server controls · English", showBackground = true, widthDp = 360, locale = "en")
@Composable
private fun ServerControlsPreview() {
    ShellTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.shell_server_title), style = MaterialTheme.typography.titleLarge)
                ServerCard(
                    ServerProfile("preview", "T13Test", "http://127.0.0.1:3082"),
                    selected = true, enabled = true, onSelect = {}, onEdit = {}, onDelete = {},
                )
                ServerInput(
                    value = "ftp://example.com", onValueChange = {},
                    label = stringResource(R.string.shell_server_address), enabled = true,
                    error = stringResource(R.string.shell_server_invalid),
                )
                ServerInput(
                    value = "T13Test", onValueChange = {},
                    label = stringResource(R.string.shell_server_name), enabled = true,
                )
                Button(onClick = {}, shape = ServerPill, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.shell_server_add))
                }
                OutlinedButton(
                    onClick = {}, shape = ServerPill,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.shell_server_cancel)) }
            }
        }
    }
}

private val probeClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()

private suspend fun probe(origin: String): Int? = withContext(Dispatchers.IO) {
    try {
        probeClient.newCall(Request.Builder().url("$origin/").get().build()).execute().use { response ->
            val body = response.body
            val sample = if (body == null || response.code !in 200..299) "" else body.byteStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                String(bytes, 0, count, Charsets.UTF_8)
            }
            when (classifyServerProbe(response.code, response.header("Content-Type").orEmpty(), sample)) {
                ServerProbeOutcome.READY -> null
                ServerProbeOutcome.AUTH_REQUIRED -> R.string.shell_server_auth_required
                ServerProbeOutcome.NOT_FOUND -> R.string.shell_server_not_found
                ServerProbeOutcome.SERVER_ERROR -> R.string.shell_server_http_error
                ServerProbeOutcome.UNRECOGNIZED -> R.string.shell_server_unrecognized
            }
        }
    } catch (_: SocketTimeoutException) { R.string.shell_server_timeout }
    catch (_: java.io.InterruptedIOException) { R.string.shell_server_timeout }
    catch (_: SSLException) { R.string.shell_server_certificate }
    catch (_: java.io.IOException) { R.string.shell_server_unreachable }
}
