package com.noiz.data

import android.content.Context
import com.noiz.database.DownloadDao
import com.noiz.database.DownloadEntity
import com.noiz.database.DownloadState
import com.noiz.database.NoizDatabase
import com.noiz.download.DownloadController
import com.noiz.model.QualityProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Offline downloads from the UI's point of view (§6.3).
 *
 * Screens call [enqueue]; this class decides the rendition, writes the optimistic Room
 * row through the download controller, and lets Media3 do the actual transfer. The row
 * is the contract: if it says `COMPLETED`, the file is on disk and verified.
 */
@Singleton
class DownloadsRepository @Inject constructor(
    private val db: NoizDatabase,
    private val controller: DownloadController,
) {

    /**
     * Every download, not only completed ones. Observing just `COMPLETED` hid queued,
     * downloading and failed downloads from the UI, so a failure reason written to the
     * row was never displayed and the user saw nothing at all (§6.5).
 */
    val downloads: Flow<List<DownloadEntity>> = db.downloadDao().observeAll()

    fun observe(trackId: String): Flow<DownloadEntity?> = db.downloadDao().observeTrack(trackId)

    val progress: Flow<Map<String, Float>> = controller.progress

    /**
     * Picks the rendition for [profile] and enqueues it.
     *
     * Delegates to the tested [com.noiz.model.Track.renditionFor] rather than
     * reimplementing the policy. The old local copy fell back to `firstOrNull()` with no
     * lossless check, so "offline lossless" could resolve to a 64 kbps Opus transcode —
     * exactly what §2.2 forbids.
     */
    suspend fun enqueue(
        context: Context,
        trackId: String,
        profile: QualityProfile,
        pinnedBy: String? = null,
    ): Boolean {
        val track = db.trackDao().byId(trackId) ?: return false
        val renditions = db.renditionDao().forTrack(trackId).map { entity ->
            com.noiz.model.Rendition(
                id = entity.id,
                codec = entity.codec,
                bitrateKbps = entity.bitrateKbps,
                sampleRate = entity.sampleRate,
                bitDepth = entity.bitDepth,
                sizeBytes = entity.sizeBytes,
                sha256 = entity.sha256,
            )
        }

        val chosen = com.noiz.model.Track(
            id = track.id,
            title = track.title,
            renditions = renditions,
        ).renditionFor(profile) ?: return false

        controller.enqueue(
            trackId = trackId,
            renditionId = chosen.id,
            title = track.title,
            artist = track.artistName,
            pinnedBy = pinnedBy,
        )
        return true
    }

    suspend fun remove(trackId: String) = controller.remove(trackId)

    suspend fun pause(trackId: String) = controller.pause(trackId)

    suspend fun resume(trackId: String) = controller.resume(trackId)

    /** Bytes currently consumed by verified downloads, for the storage screen. */
    suspend fun usedBytes(): Long = db.downloadDao().completedBytes() ?: 0L

    /** Largest unpinned downloads, for the "free up space" screen (§12.7). */
    suspend fun evictionCandidates(limit: Int = 20): List<DownloadEntity> =
        db.downloadDao().evictionCandidates(limit)

    /**
     * LRU eviction when a new download would push us over [capBytes]. Pinned downloads
     * are never touched: the user asked for them to stay.
     */
    suspend fun enforceCap(capBytes: Long) {
        var used = usedBytes()
        if (used <= capBytes) return

        for (candidate in evictionCandidates()) {
            if (used <= capBytes) break
            controller.remove(candidate.trackId)
            used -= candidate.bytesTotal
        }
    }
}
