package com.noiz.download

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.noiz.database.DownloadDao
import com.noiz.database.DownloadEntity
import com.noiz.database.DownloadState as RoomDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline downloads (§6.3, §12.7).
 *
 * Design decisions worth keeping:
 *
 *  - **Media3 owns the bytes.** `DownloadService` runs in a foreground `dataSync`
 *    service (declared in `:app`'s manifest), persists its own state, survives Doze and
 *    process death, and gives us resume-for-free. Re-implementing that with WorkManager
 *    would trade a tested scheduler for ours.
 *  - **Room is the read model.** Screens never talk to `DownloadManager`; they observe
 *    [DownloadDao]. `ProgressRecorder` mirrors Media3's per-download progress into
 *    `DownloadEntity`, so a partially failed job is visible and resumable.
 *  - **The download id *is* the track id.** One track has exactly one downloaded copy
 *    per device, so `DownloadRequest.id = trackId` lets the DAO upsert by primary key
 *    without a second identifier to keep in sync.
 *  - **Content IDs are stable.** The stream URL is minted per request (§9.4), so the
 *    cache key is the track/rendition pair, not the signed URL — a rotated signature
 *    must never look like a new download.
 */

const val NOIZ_DOWNLOAD_NOTIFICATION_ID = 0x4E4F49 // 'NOI'

/** App-defined stop reason: Media3 reserves 0 = "no reason"; 1+ are ours. */
const val STOP_REASON_PAUSED = 1

/**
 * Builds `noiz://track/{id}/{rendition}` requests, which [NoizStreamDataSource] resolves.
 *
 * [mimeType] must come from the rendition's real codec: hardcoding `audio/flac` for
 * every original announced uploaded MP3/AAC/Ogg files as FLAC, which Media3 then used
 * for container inference and rendered in the Auto browse card.
 */
fun buildDownloadRequest(
    trackId: String,
    renditionId: String,
    title: String,
    artist: String,
    mimeType: String? = null,
): DownloadRequest =
    DownloadRequest.Builder(trackId, android.net.Uri.parse("noiz://track/$trackId/$renditionId"))
        .setData("$trackId|$renditionId".toByteArray(Charsets.UTF_8))
        .setCustomCacheKey("noiz/$trackId/$renditionId")
        .apply { mimeType?.let { setMimeType(it) } }
        .build()

/** Maps a server codec to the container Media3 should be told about. */
fun mimeTypeForCodec(codec: String?): String? = when (codec?.lowercase()) {
    "flac" -> "audio/flac"
    "alac", "m4a", "aac" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "opus", "vorbis", "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "aiff", "aif" -> "audio/aiff"
    else -> null
}

/**
 * Entry points for enqueueing, pausing and removing downloads.
 *
 * Callers (feature screens) only ever touch this — never `DownloadManager` directly —
 * so the Room row and the Media3 job can never disagree about who owns a download.
 */
class DownloadController(
    private val appContext: Context,
    private val dao: DownloadDao,
    private val scope: CoroutineScope,
    private val expectedSha256: suspend (trackId: String, renditionId: String) -> String? = { _, _ -> null },
    private val codecOf: suspend (trackId: String, renditionId: String) -> String? = { _, _ -> null },
) {

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    fun enqueue(trackId: String, renditionId: String, title: String, artist: String, pinnedBy: String? = null) {
        scope.launch {
            val sha = expectedSha256(trackId, renditionId).orEmpty()
            // The row must exist BEFORE Media3 starts: the first onDownloadChanged
            // callback can arrive before an async upsert commits, and the recorder
            // discards events for tracks it cannot find, leaving the row stuck at
            // QUEUED forever.
            dao.upsert(
                DownloadEntity(
                    trackId = trackId,
                    renditionId = renditionId,
                    state = RoomDownloadState.QUEUED,
                    bytesDone = 0L,
                    bytesTotal = 0L,
                    pinnedBy = pinnedBy,
                    completedAt = null,
                    sha256 = sha,
                ),
            )

            DownloadService.sendAddDownload(
                appContext,
                NoizDownloadService::class.java,
                buildDownloadRequest(
                    trackId = trackId,
                    renditionId = renditionId,
                    title = title,
                    artist = artist,
                    mimeType = mimeTypeForCodec(codecOf(trackId, renditionId)),
                ),
                /* foreground= */ false,
            )
        }
    }

    fun remove(trackId: String) {
        // Delete before telling Media3 to stop: the recorder maps STATE_REMOVING to a
        // row write, so deleting afterwards let it re-create the row the user just
        // removed.
        scope.launch {
            dao.delete(trackId)
            _progress.update { it - trackId }
        }
        DownloadService.sendRemoveDownload(
            appContext,
            NoizDownloadService::class.java,
            trackId,
            /* foreground= */ false,
        )
    }

    fun pause(trackId: String) {
        DownloadService.sendSetStopReason(
            appContext,
            NoizDownloadService::class.java,
            trackId,
            STOP_REASON_PAUSED,
            /* foreground= */ false,
        )
    }

    fun resume(trackId: String) {
        DownloadService.sendSetStopReason(
            appContext,
            NoizDownloadService::class.java,
            trackId,
            Download.STOP_REASON_NONE,
            /* foreground= */ false,
        )
        scope.launch {
            // Do NOT reset bytesDone: Media3 resumes from the partially cached span, so
            // zeroing it made the UI jump back to 0 %.
            dao.setState(trackId, RoomDownloadState.DOWNLOADING)
        }
    }

    /**
     * Mirrors Media3's view of the world into Room. Attached once, from the service.
     */
    fun attachProgressRecorder(manager: DownloadManager) {
        manager.addListener(ProgressRecorder(dao, scope, _progress))
    }
}

/** Translates `Download` snapshots into `DownloadEntity` rows. */
/**
 * Verifies a completed download against the server's content hash (§6.3).
 *
 * Media3 performs the atomic rename but never checks integrity, so a truncated or
 * corrupted transfer was previously reported as `COMPLETED` and only failed much later,
 * during playback.
 *
 * The bytes are read through a [CacheDataSource] rather than off disk: `SimpleCache` has
 * no `getCachedFile`, and touching its span files directly would bypass the span locking
 * that stops a concurrent download from mutating them mid-read.
 *
 * @return true when the bytes match, or when no hash is known for this rendition.
 */
suspend fun verifyDownloadChecksum(
    cache: SimpleCache,
    trackId: String,
    renditionId: String,
    expectedSha256: String?,
    onMismatch: suspend (String) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val expected = expectedSha256?.takeIf { it.isNotBlank() } ?: return@withContext true

    val key = "noiz/$trackId/$renditionId"
    val length = runCatching { cache.getCachedLength(key, 0L, C.LENGTH_UNSET.toLong()) }
        .getOrNull()
    if (length == null || length <= 0L) return@withContext true

    val actual = runCatching {
        // A COMPLETED download must already be in the cache; this upstream only exists
        // so a cache miss fails loudly instead of silently re-downloading. Media3 1.5.1
        // has no EmptyDataSource, so it is spelled out here.
        val failing: DataSource.Factory = DataSource.Factory {
            object : BaseDataSource(/* isNetwork= */ false) {
                override fun open(dataSpec: DataSpec): Long =
                    throw java.io.IOException("Cache miss for $key")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

                override fun getUri(): android.net.Uri = android.net.Uri.EMPTY

                override fun close() = Unit
            }
        }

        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(failing)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        // `DataSource.open` returns the length, not a stream — read from the source itself.
        dataSource.open(
            DataSpec.Builder()
                .setUri(android.net.Uri.EMPTY)
                .setKey(key)
                .setPosition(0L)
                .setLength(length)
                .build(),
        )

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        dataSource.close()
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull() ?: return@withContext false

    val matches = actual.equals(expected, ignoreCase = true)
    if (!matches) onMismatch(actual)
    matches
}

/** Translates `Download` snapshots into [DownloadEntity] rows. */
private class ProgressRecorder(
    private val dao: DownloadDao,
    private val scope: CoroutineScope,
    private val progress: MutableStateFlow<Map<String, Float>>,
) : DownloadManager.Listener {

    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, failure: Exception?) {
        val trackId = download.request.id
        val done = download.bytesDownloaded
        val total = download.contentLength
        val fraction = if (total > 0) done.toFloat() / total else 0f

        val state = when {
            // A failed download with a null `failure` used to fall through to `else`
            // and sit at DOWNLOADING forever.
            failure != null || download.state == Download.STATE_FAILED ->
                RoomDownloadState.FAILED

            download.state == Download.STATE_COMPLETED -> RoomDownloadState.COMPLETED
            download.state == Download.STATE_REMOVING -> RoomDownloadState.STALE
            download.state == Download.STATE_STOPPED -> RoomDownloadState.QUEUED
            else -> RoomDownloadState.DOWNLOADING
        }

        // Atomic update: this fires on the DownloadManager thread, and a plain
        // `progress.value = progress.value + …` could drop a concurrent entry. Entries
        // also have to be removed when a download ends, or the map grows forever.
        if (state == RoomDownloadState.COMPLETED || state == RoomDownloadState.STALE) {
            progress.update { it - trackId }
        } else {
            progress.update { it + (trackId to fraction) }
        }

        scope.launch {
            val existing = dao.byTrack(trackId) ?: return@launch
            dao.upsert(
                existing.copy(
                    state = state,
                    bytesDone = done,
                    bytesTotal = if (total > 0) total else existing.bytesTotal,
                    completedAt = if (state == RoomDownloadState.COMPLETED) System.currentTimeMillis()
                    else existing.completedAt,
                    failureReason = failure?.message,
                ),
            )
        }
    }
}

/**
 * The foreground service that hosts Media3's download loop.
 *
 * Declared in `:app` with `foregroundServiceType="dataSync"`; Media3 promotes it while
 * bytes are moving and stops it when the queue drains (§12.1).
 */
@UnstableApi
class NoizDownloadService : DownloadService(
    NOIZ_DOWNLOAD_NOTIFICATION_ID,
    FOREGROUND_NOTIFICATION_UPDATE_INTERVAL_MS,
    CHANNEL_ID,
    /* channelDescriptionResourceId= */ R.string.noiz_download_channel,
    /* channelNameResourceId= */ R.string.noiz_download_channel,
) {

    override fun getDownloadManager(): DownloadManager = NoizDownloadManagerProvider.manager(this)

    /**
     * Reschedules downloads across process death and reboot.
     *
     * `getScheduler()` is abstract in Media3 1.5.1 — it cannot return null, so the
     * previous `= null` stub did not compile and, had it been nullable, would have
     * silently disabled Doze-aware resumption (§12.1).
     *
     * [PlatformScheduler] is backed by `JobScheduler`, which is what we want here:
     * the manifest deliberately strips SCHEDULE_EXACT_ALARM, and
     * `AlarmManagerScheduler` would need it. JobScheduler also survives Doze properly.
     */
    override fun getScheduler(): Scheduler = NoizDownloadManagerProvider.scheduler(this)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): android.app.Notification =
        DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(
                this,
                android.R.drawable.stat_sys_download,
                null,
                getString(R.string.noiz_download_channel),
                downloads,
                notMetRequirements,
            )

    companion object {
        const val CHANNEL_ID = "noiz_downloads"

        /** Progress refresh cadence for the foreground notification. */
        private const val FOREGROUND_NOTIFICATION_UPDATE_INTERVAL_MS = 2_000L
    }
}

/**
 * One `DownloadManager` per process, wired with the noiz data source.
 *
 * Kept in an object because `DownloadService` is instantiated by the framework and has
 * nowhere to receive constructor injection; `:app` installs the factory in
 * `NoizApplication.onCreate` before any download starts.
 */
@UnstableApi
object NoizDownloadManagerProvider {

    @Volatile
    private var factory: ((Context) -> DownloadManager)? = null

    @Volatile
    private var cached: DownloadManager? = null

    @Volatile
    private var cachedScheduler: Scheduler? = null

    fun install(factory: (Context) -> DownloadManager) {
        this.factory = factory
        cached = null
    }

    fun manager(context: Context): DownloadManager =
        cached ?: synchronized(this) {
            cached ?: requireNotNull(factory) {
                "NoizDownloadManagerProvider.install(...) must run in NoizApplication.onCreate"
            }(context.applicationContext).also { cached = it }
        }

    /**
     * One [PlatformScheduler] per process.
     *
     * `DownloadService.getScheduler()` is abstract and called on every service start, so
     * a fresh instance per call would re-register the JobScheduler job each time.
     */
    fun scheduler(context: Context): Scheduler =
        cachedScheduler ?: synchronized(this) {
            cachedScheduler ?: PlatformScheduler(context.applicationContext, SCHEDULER_ID)
                .also { cachedScheduler = it }
        }

    private const val SCHEDULER_ID = 0x4E4F49 // 'NOI'
}
