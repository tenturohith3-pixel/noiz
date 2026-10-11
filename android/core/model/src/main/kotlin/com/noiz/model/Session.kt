package com.noiz.model

/**
 * Identity and server-capability models (§4.3, `/api/v1/me`).
 *
 * The client never holds administrator or storage credentials; it only knows which
 * *capabilities* this server grants the signed-in user, so buttons that would 403
 * are not shown in the first place (server enforcement still applies).
 */
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val canUpload: Boolean,
    val canDownload: Boolean,
) {
    val isAdmin: Boolean get() = role == "admin"
}

data class NoizSession(
    /** Stored verbatim; use [normalizedServerUrl] when dialling. */
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis the access token stops being valid; used to refresh proactively. */
    val expiresAtEpochMs: Long,
    val user: User,
) {
    val username: String get() = user.username
    val userId: String get() = user.id

    /**
     * The server URL with exactly one trailing slash, which is what every relative
     * `@Url` resolution and `HttpUrl` concatenation requires. Returning the raw field
     * meant `https://host` and `https://host/` produced different request URLs, and a
     * trailing path segment could be silently dropped.
     */
    val normalizedServerUrl: String
        get() = serverUrl.trim().trimEnd('/') + "/"
}

/** Server-side feature switches surfaced by `/api/v1/me` (§13). */
data class ServerCapabilities(
    val streaming: Boolean = true,
    val downloads: Boolean = true,
    val uploads: Boolean = true,
    val lyrics: Boolean = false,
    val recommendations: Boolean = false,
    val remotePlayback: Boolean = false,
    val offlineEncryptionPolicy: String = "optional",
)
