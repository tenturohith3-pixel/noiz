package com.noiz.download

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.IOException
import java.util.concurrent.Executors
import okhttp3.OkHttpClient

/**
 * Builds the `DownloadManager` used by [NoizDownloadService].
 *
 * The stack mirrors streaming (§3.4) for a deliberate reason: a downloaded file and a
 * streamed file must be byte-identical, so both go through the same signed-URL
 * resolution and the same cache-key scheme. The only difference is the final sink —
 * downloads land in the managed files directory instead of the streaming cache.
 */
fun buildNoizDownloadManager(
    context: Context,
    okHttpClient: OkHttpClient,
    streamCache: SimpleCache,
    resolver: (String, String) -> String,
): DownloadManager {
    val upstream = ResolvingUrlDataSource.Factory(
        base = OkHttpDataSource.Factory(okHttpClient),
        resolve = { dataSpec ->
            val segments = dataSpec.uri.pathSegments
            val trackId = segments.getOrNull(0) ?: return@ResolvingUrlDataSource dataSpec
            val rendition = segments.getOrNull(1) ?: "orig"
            val signed = resolver(trackId, rendition)
            if (signed.isNullOrBlank()) {
                throw IOException("No signed URL for $trackId/$rendition")
            }
            dataSpec.withUri(android.net.Uri.parse(signed))
        },
    )

    // DownloadManager's five-argument constructor *already* wraps the upstream factory
    // in its own CacheDataSource over the same cache. Passing a pre-wrapped
    // CacheDataSource produced CacheDataSource -> CacheDataSource over one SimpleCache,
    // so two writers fought over the same span key (SimpleCache.commitFile asserts the
    // content is fully locked) and downloads failed or corrupted the cache.
    // Passing the bare upstream leaves exactly one cache layer.
    return DownloadManager(
        context.applicationContext,
        StandaloneDatabaseProvider(context.applicationContext),
        streamCache,
        upstream,
        Executors.newSingleThreadExecutor { r -> Thread(r, "noiz-download") },
    )
}

/**
 * `DataSource.Factory` that rewrites the stable `noiz://` URI into a fresh signed URL
 * just before the request leaves the device.
 *
 * Resolution happens per-`DataSpec`, *below* the cache (§3.4): rotating a signature
 * never invalidates bytes we already have, and an expired URL is re-minted mid-download
 * instead of failing the job.
 */
private class ResolvingUrlDataSource(
    private val base: DataSource,
    private val resolve: (DataSpec) -> DataSpec,
) : BaseDataSource(/* isNetwork= */ true) {

    private var opened: DataSource? = null

    /**
     * Whether `transferStarted` was reached. `close()` may run after a *failed* open,
     * and calling `transferEnded()` without a matching `transferStarted()` throws
     * IllegalStateException — masking the real "No signed URL for …" IOException
     * inside DownloadManager.
     */
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val resolved = resolve(dataSpec)
        opened = base
        val length = opened!!.open(resolved)
        transferStarted(dataSpec)
        started = true
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = opened ?: throw IOException("DataSource not opened")
        val read = source.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun close() {
        val source = opened
        opened = null
        try {
            source?.close()
        } finally {
            if (started) {
                transferEnded()
                started = false
            }
        }
    }

    override fun getUri(): android.net.Uri? = opened?.uri

    override fun getResponseHeaders(): MutableMap<String, List<String>> =
        opened?.responseHeaders ?: mutableMapOf()

    class Factory(
        private val base: DataSource.Factory,
        private val resolve: (DataSpec) -> DataSpec,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ResolvingUrlDataSource(base.createDataSource(), resolve)
    }
}
