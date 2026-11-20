package app.codeg.android.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class UpdateDownloader @Inject constructor(@ApplicationContext private val context: Context) {
    private val engine = UpdateDownloadEngine()

    suspend fun download(
        update: AppUpdate,
        onState: suspend (UpdateDownloadState) -> Unit = {},
        progress: suspend (Float?) -> Unit,
    ): File = engine.download(update, File(context.cacheDir, "updates"), onState, progress)
}
