package app.codeg.android.shell.upload

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.io.File
import java.util.Locale

/** Owns one WebView upload callback, including every cancellation path. */
class UploadFeature : ShellFeature {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var menu by mutableStateOf(false)
    private var mimeTypes = arrayOf("*/*")
    private var multiple = false
    private var externalPending = false
    private var cameraUri: Uri? = null
    private var cameraFile: File? = null
    private lateinit var photo: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var photos: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var document: ActivityResultLauncher<Array<String>>
    private lateinit var documents: ActivityResultLauncher<Array<String>>
    private lateinit var folder: ActivityResultLauncher<Uri?>
    private lateinit var camera: ActivityResultLauncher<Uri>

    override fun onCreate(host: ShellHost) {
        val activity = host.activity
        photo = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            externalPending = false
            complete(it?.let { uri -> arrayOf(uri) })
        }
        photos = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
            externalPending = false
            complete(it.takeIf { uris -> uris.isNotEmpty() }?.toTypedArray())
        }
        document = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            externalPending = false
            complete(it?.let { uri -> arrayOf(uri) })
        }
        documents = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            externalPending = false
            complete(it.takeIf { uris -> uris.isNotEmpty() }?.toTypedArray())
        }
        // `webkitdirectory` (codeg web's folder upload, src/components/layout/
        // workspace-upload-dialog.tsx) arrives as MODE_OPEN_FOLDER. Chromium
        // answers with ACTION_OPEN_DOCUMENT_TREE's result and enumerates the
        // tree itself, so the tree URI is the whole answer.
        folder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            externalPending = false
            complete(it?.let { uri -> arrayOf(uri) })
        }
        camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            externalPending = false
            val uri = cameraUri
            if (!success) cameraFile?.delete()
            cameraUri = null
            cameraFile = null
            complete(if (success && uri != null) arrayOf(uri) else null)
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                complete(null)
                // Keep an in-flight camera file: the external camera may still be writing it.
                photo.unregister()
                photos.unregister()
                document.unregister()
                documents.unregister()
                folder.unregister()
                camera.unregister()
            }
        })
    }

    override fun onShowFileChooser(
        host: ShellHost,
        view: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        // Never let an old external result complete a newer request.
        if (externalPending) {
            callback.onReceiveValue(null)
            return true
        }
        complete(null)
        this.callback = callback
        mimeTypes = normalizeTypes(params.acceptTypes)
        multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        // Directory picking (`<input type=file webkitdirectory>`) is its own
        // contract: the browser wants a tree, not files, and only
        // ACTION_OPEN_DOCUMENT_TREE produces one. `MODE_OPEN_FOLDER` is 2 in
        // WebChromeClient.FileChooserParams but is not part of the public SDK
        // (it is @SystemApi-hidden), so the value is spelled out here.
        if (params.mode == MODE_OPEN_FOLDER) {
            launch { folder.launch(null) }
            return true
        }
        if (params.mode != WebChromeClient.FileChooserParams.MODE_OPEN && !multiple) {
            complete(null)
        } else if (params.isCaptureEnabled && isVisual() && canTakePhoto()) {
            takePhoto(host)
        } else if (isVisual() || canTakePhoto()) {
            menu = true
        } else {
            openDocuments()
        }
        return true
    }

    private fun complete(uris: Array<Uri>?) {
        menu = false
        val pending = callback
        callback = null
        pending?.onReceiveValue(uris)
    }

    private fun isVisual() = mimeTypes.all { it.startsWith("image/") || it.startsWith("video/") }
    private fun canTakePhoto() = mimeTypes.any { it == "image/*" || it == "image/jpeg" || it == "*/*" }

    private fun launch(action: () -> Unit) {
        menu = false
        externalPending = true
        try {
            action()
        } catch (_: RuntimeException) {
            externalPending = false
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
            complete(null)
        }
    }

    private fun openDocuments() = launch {
        if (multiple) documents.launch(mimeTypes) else document.launch(mimeTypes)
    }

    private fun openPhotos() = launch {
        val type = when {
            mimeTypes.contentEquals(arrayOf("*/*")) -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            mimeTypes.size == 1 && mimeTypes[0] != "image/*" && mimeTypes[0] != "video/*" ->
                ActivityResultContracts.PickVisualMedia.SingleMimeType(mimeTypes[0])
            mimeTypes.all { it.startsWith("image/") } -> ActivityResultContracts.PickVisualMedia.ImageOnly
            mimeTypes.all { it.startsWith("video/") } -> ActivityResultContracts.PickVisualMedia.VideoOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        val request = PickVisualMediaRequest(type)
        if (multiple) photos.launch(request) else photo.launch(request)
    }

    private fun takePhoto(host: ShellHost) = launch {
        val directory = File(host.activity.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("upload-", ".jpg", directory)
        cameraFile = file
        val uri = FileProvider.getUriForFile(host.activity, "${host.activity.packageName}.files", file)
        cameraUri = uri
        camera.launch(uri)
    }

    override fun onBack(host: ShellHost): Boolean {
        if (!menu) return false
        complete(null)
        return true
    }

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        complete(null)
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        complete(null)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Overlay(host: ShellHost) {
        if (!menu) return
        ModalBottomSheet(
            onDismissRequest = { complete(null) },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            // ModalBottomSheet consumes the navigation bar inset; keep an extra visual gutter.
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.shell_upload_title),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isVisual() || mimeTypes.contentEquals(arrayOf("*/*"))) {
                    UploadOption(Icons.Outlined.PhotoLibrary, stringResource(R.string.shell_upload_gallery)) { openPhotos() }
                }
                if (canTakePhoto()) {
                    UploadOption(Icons.Outlined.PhotoCamera, stringResource(R.string.shell_upload_camera)) { takePhoto(host) }
                }
                UploadOption(Icons.AutoMirrored.Outlined.InsertDriveFile, stringResource(R.string.shell_upload_files)) { openDocuments() }
            }
        }
    }

    @Composable
    private fun UploadOption(icon: ImageVector, label: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    private fun normalizeTypes(accept: Array<String>): Array<String> = accept
        .flatMap { it.split(',') }
        .map { it.trim().lowercase(Locale.ROOT).substringBefore(';').trim() }
        .filter { it.isNotEmpty() }
        .map { type ->
            when {
                type.startsWith('.') -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(type.drop(1)) ?: "*/*"
                type.matches(Regex("[a-z0-9!#$&^_.+*-]+/[a-z0-9!#$&^_.+*-]+")) -> type
                else -> "*/*"
            }
        }.distinct().let { types ->
            if (types.isEmpty() || "*/*" in types) arrayOf("*/*") else types.toTypedArray()
        }
}

/**
 * `WebChromeClient.FileChooserParams.MODE_OPEN_FOLDER`. The constant exists on
 * the platform (value 2, between MODE_OPEN_MULTIPLE and MODE_SAVE) but is not
 * exported to the public SDK, so it cannot be referenced by name.
 */
private const val MODE_OPEN_FOLDER = 2
