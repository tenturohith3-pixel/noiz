package com.noiz.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/**
 * Mints fresh HMAC stream signatures below the cache layer (§3.4).
 *
 * The cache key is the stable `noiz://track/{id}/{rendition}` URI, so rotating a
 * signature never invalidates cached bytes and never touches SimpleCache.
 */
class SignedUrlResolver(
    private val tokenStore: StreamTokenStore,
    private val manifestClient: StreamManifestClient,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != NOIZ_SCHEME) return dataSpec

        // `noiz://track/{trackId}/{rendition}` puts the literal "track" in `uri.host`,
        // so reading the track id from `host` minted a signature for a track called
        // "track" for every single item. The path segments carry the real values.
        val segments = uri.pathSegments
        val trackId = segments.getOrNull(0) ?: return dataSpec
        val rendition = segments.getOrNull(1) ?: RENDITION_ORIG

        val cached = tokenStore.signatureFor(trackId, rendition)
        val signed = if (cached != null && !cached.isExpiringWithin(EXPIRY_SKEW_MS)) {
            cached
        } else {
            // Blocking is acceptable: this runs on the ExoPlayer loading thread.
            val fresh = manifestClient.streamUrlBlocking(trackId, rendition)
                ?: throw IOException("Unable to mint a signed URL for $trackId/$rendition")
            tokenStore.put(trackId, rendition, fresh)
            fresh
        }

        return dataSpec.withUri(Uri.parse(signed.url))
    }

    companion object {
        const val NOIZ_SCHEME = "noiz"
        const val RENDITION_ORIG = "orig"

        /** Refresh when the signature is within 5 minutes of expiry (§9.4). */
        const val EXPIRY_SKEW_MS = 5 * 60 * 1000L
    }
}

/** A minted, time-limited stream URL. */
data class SignedStreamUrl(val url: String, val expiresAtEpochMs: Long) {
    fun isExpiringWithin(windowMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs - nowMs <= windowMs
}

interface StreamTokenStore {
    fun signatureFor(trackId: String, rendition: String): SignedStreamUrl?
    fun put(trackId: String, rendition: String, url: SignedStreamUrl)
    fun invalidate(trackId: String, rendition: String)

    /**
     * Drops every minted URL after an auth failure.
     *
     * The error arrives with the *resolved* https URI, so the original
     * `noiz://track/{id}/{rendition}` is no longer recoverable; clearing the small
     * in-memory map is cheaper than leaving dead signatures in place.
     */
    fun invalidateAll() = Unit
}

/** Blocking manifest fetch. Implemented in :core:network on top of the sync OkHttp client. */
interface StreamManifestClient {
    fun streamUrlBlocking(trackId: String, rendition: String): SignedStreamUrl?
}

/**
 * Retry policy from §11 row 6: 1 s, 2 s, 4 s, 8 s, then every 10 s forever while the
 * device has a validated network. [networkPolicy] decides when "forever" ends.
 */
class NoizLoadErrorHandlingPolicy(
    private val isValidatedNetwork: () -> Boolean,
    /** Drops a cached signature so the next resolve mints a fresh one. */
    private val tokenStore: StreamTokenStore? = null,
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // An expired or revoked signature must be re-minted, not blindly retried.
        // Media3 1.5.1 has no `shouldRetryLoadException` hook, so the decision happens
        // here: a 401/403 invalidates the cached signature and retries almost
        // immediately so the next DataSpec resolve mints a fresh one.
        (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.let { httpError ->
            when (httpError.responseCode) {
                401, 403 -> {
                    invalidateSignature(httpError.dataSpec.uri)
                    return AUTH_RETRY_MS
                }
                // The rendition is gone for good; retrying cannot help.
                404, 410 -> return C.TIME_UNSET
            }
        }

        val attempt = loadErrorInfo.errorCount - 1
        if (!isValidatedNetwork()) return RETRY_WHILE_OFFLINE_MS

        return when {
            attempt < BACKOFF_MS.size -> BACKOFF_MS[attempt]
            // Keep the session alive; UI shows "Reconnecting…" on the seek bar.
            else -> LONG_RETRY_MS
        }
    }

    private fun invalidateSignature(uri: Uri?) {
        // The error carries the resolved https URL, not the noiz:// key the cache is
        // indexed by. Invalidate the specific entry when it still has the custom
        // scheme, and clear the map otherwise so the next resolve cannot reuse a dead
        // signature for twenty retries.
        val target = uri
        if (target != null && target.scheme == SignedUrlResolver.NOIZ_SCHEME) {
            val id = target.pathSegments.getOrNull(0)
            val rendition = target.pathSegments.getOrNull(1) ?: SignedUrlResolver.RENDITION_ORIG
            if (id != null) tokenStore?.invalidate(id, rendition)
        } else {
            tokenStore?.invalidateAll()
        }
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_RETRIES

    companion object {
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
        const val LONG_RETRY_MS = 10_000L
        const val RETRY_WHILE_OFFLINE_MS = 5_000L
        const val MAX_RETRIES = 20

        /** After a 401/403 the next resolve mints a new signature; retry at once. */
        private const val AUTH_RETRY_MS = 0L
    }
}
