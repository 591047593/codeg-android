package app.codeg.android.core.update

import java.io.IOException

enum class UpdateFailureReason { NO_ROUTE, TRUSTED_CHECKSUM, DOWNLOAD, VERIFICATION }

class UpdateDownloadException(val reason: UpdateFailureReason, cause: Throwable? = null) :
    IOException("Update failed: $reason", cause)
