package app.codeg.android.shell.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.codeg.android.BuildConfig
import app.codeg.android.R
import app.codeg.android.core.update.AppUpdate
import app.codeg.android.core.update.UpdateChecker
import app.codeg.android.core.update.UpdateDownloader
import app.codeg.android.core.update.UpdateDownloadState
import app.codeg.android.core.update.UpdateCheckPolicy
import app.codeg.android.core.update.UpdateDownloadException
import app.codeg.android.core.update.UpdateFailureReason
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Activity-owned state; persistent timing survives process restarts. */
class UpdateFeature : ShellFeature {
    private var update by mutableStateOf<AppUpdate?>(null)
    private var downloading by mutableStateOf(false)
    private var downloadState by mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Testing)
    private var progress by mutableStateOf<Float?>(null)
    private var error by mutableStateOf<Int?>(null)
    private var file: File? = null
    private var checking by mutableStateOf(false)
    private var manualRequested = false
    private var manualCheckVisible by mutableStateOf(false)
    private var checkMessage by mutableStateOf<Int?>(null)
    private var downloadJob: Job? = null
    private var awaitingPermission = false

    private fun preferences(host: ShellHost) =
        host.activity.getSharedPreferences("shell_update", Context.MODE_PRIVATE)

    override fun onResume(host: ShellHost) {
        if (BuildConfig.DEBUG) return
        if (awaitingPermission) {
            awaitingPermission = false
            if (host.activity.packageManager.canRequestPackageInstalls()) install(host)
            else error = R.string.shell_update_install_failed
        }
        check(host, manual = false)
    }

    fun checkNow(host: ShellHost) {
        if (update != null || downloading) {
            manualCheckVisible = false
            return
        }
        manualCheckVisible = true
        manualRequested = true
        checkMessage = null
        if (BuildConfig.DEBUG) {
            checkMessage = R.string.shell_update_debug_disabled
            return
        }
        check(host, manual = true)
    }

    private fun check(host: ShellHost, manual: Boolean) {
        if (checking || downloading || update != null) return
        val prefs = preferences(host)
        if (!UpdateCheckPolicy.shouldCheck(System.currentTimeMillis(), prefs.getLong("last_success", 0),
                prefs.getLong("last_failure", 0), manual)) return
        checking = true
        host.scope.launch {
            try {
                // A successful empty result and a network failure are different outcomes.
                val available = UpdateChecker().use { it.check(BuildConfig.VERSION_CODE, manual = true) }
                prefs.edit().putLong("last_success", System.currentTimeMillis()).remove("last_failure").apply()
                if (available != null) {
                    val dismissedAt = prefs.getLong("dismissed_at", 0L)
                    val dismissed = prefs.getInt("dismissed_version", -1) == available.versionCode
                    val time = System.currentTimeMillis()
                    if (manualRequested || manual || !dismissed || time < dismissedAt || time - dismissedAt >= 4 * SIX_HOURS) {
                        update = available
                        manualCheckVisible = false
                    }
                } else if (manual || manualRequested) checkMessage = R.string.shell_update_up_to_date
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                prefs.edit().putLong("last_failure", System.currentTimeMillis()).apply()
                if (manual || manualRequested) checkMessage = R.string.shell_update_check_failed
            } finally {
                checking = false
                manualRequested = false
            }
        }
    }

    private fun dismiss(host: ShellHost) {
        if (downloading) return
        update?.let {
            preferences(host).edit().putInt("dismissed_version", it.versionCode)
                .putLong("dismissed_at", System.currentTimeMillis()).apply()
        }
        update = null
        file = null
        error = null
    }

    private fun download(host: ShellHost) {
        if (downloading) return
        if (file != null) {
            install(host)
            return
        }
        val available = update ?: return
        downloading = true
        downloadState = UpdateDownloadState.Testing
        progress = null
        error = null
        downloadJob = host.scope.launch {
            try {
                file = UpdateDownloader(host.activity.applicationContext).download(available,
                    onState = { state -> withContext(Dispatchers.Main.immediate) { downloadState = state } },
                ) { value ->
                    withContext(Dispatchers.Main.immediate) { progress = value }
                }
                install(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = when ((e as? UpdateDownloadException)?.reason) {
                    UpdateFailureReason.NO_ROUTE -> R.string.shell_update_no_route
                    UpdateFailureReason.TRUSTED_CHECKSUM -> R.string.shell_update_checksum_unavailable
                    UpdateFailureReason.VERIFICATION -> R.string.shell_update_verification_failed
                    else -> R.string.shell_update_download_failed
                }
            } finally {
                // Keep retry disabled until cancellation has closed the call and removed the half-file.
                downloading = false
                downloadJob = null
                progress = null
            }
        }
    }

    private fun cancelDownload() { downloadJob?.cancel() }

    private fun install(host: ShellHost) {
        val apk = file ?: return
        error = null
        try {
            if (!host.activity.packageManager.canRequestPackageInstalls()) {
                awaitingPermission = true
                host.activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${host.activity.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(host.activity, "${host.activity.packageName}.files", apk)
            host.activity.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            awaitingPermission = false
            error = R.string.shell_update_install_failed
        }
    }

    @Composable
    override fun Overlay(host: ShellHost) {
        val available = update
        if (available == null) {
            if (manualCheckVisible) AlertDialog(
                onDismissRequest = { manualCheckVisible = false },
                title = { Text(stringResource(R.string.shell_update_check)) },
                text = { Text(stringResource(if (checking) R.string.shell_update_checking else checkMessage ?: R.string.shell_update_check_failed)) },
                confirmButton = { TextButton(onClick = { manualCheckVisible = false }) { Text(stringResource(R.string.shell_update_close)) } },
            )
            return
        }
        AlertDialog(
            onDismissRequest = { if (downloading) cancelDownload() else dismiss(host) },
            title = { Text(stringResource(R.string.shell_update_available, available.versionName)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(available.notes.ifBlank { stringResource(R.string.shell_update_no_notes) })
                    if (downloading) {
                        Text(when (val state = downloadState) {
                            UpdateDownloadState.Testing -> stringResource(R.string.shell_update_testing_routes)
                            is UpdateDownloadState.Downloading -> stringResource(R.string.shell_update_downloading_route, state.route)
                            UpdateDownloadState.Verifying -> stringResource(R.string.shell_update_verifying)
                        })
                        val value = progress
                        if (value == null) LinearProgressIndicator()
                        else {
                            LinearProgressIndicator(progress = { value })
                            Text(stringResource(R.string.shell_update_download_progress,
                                (value * 100).toInt(), value * available.size / 1048576f, available.size / 1048576f))
                        }
                    }
                    error?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !downloading, onClick = { download(host) }) {
                    Text(stringResource(R.string.shell_update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (downloading) cancelDownload() else dismiss(host) }) {
                    Text(stringResource(if (downloading) R.string.shell_update_cancel_download else R.string.shell_update_later))
                }
            },
        )
    }

    private companion object {
        const val SIX_HOURS = 6 * 60 * 60 * 1000L
    }
}
