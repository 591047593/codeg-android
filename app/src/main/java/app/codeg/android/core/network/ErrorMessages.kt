package app.codeg.android.core.network

/** Maps a thrown error (typically an [ApiError]) to a short user-facing message. */
fun Throwable.displayMessage(): String = when (this) {
    is ApiError.Unauthorized -> "Invalid or missing token"
    is ApiError.TurnInProgress -> "A turn is already in progress"
    is ApiError.Transport -> detail
    is ApiError.Server -> serverMessage
    is ApiError.Decoding -> "Unexpected response from the server"
    // The conversation is too large for this client to load in one piece. Nothing the
    // user can do about it from here, so say what it means rather than showing the
    // OOM-triggering request as a generic failure.
    is ApiError.ResponseTooLarge -> "This conversation is too large to open on this device"
    else -> message ?: "Something went wrong"
}
