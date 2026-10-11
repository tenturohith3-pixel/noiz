package com.noiz.data

import android.content.Context
import com.noiz.database.NoizDatabase
import com.noiz.database.PlayEventEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Play history (§6.6).
 *
 * Every event carries a client-generated idempotency key, so a retry after a timeout
 * is deduplicated server-side instead of double-counting a listen. Events are written
 * locally first (never lost to a flaky network) and flushed by [OutboxWorker].
 *
 * `private` sessions are recorded but never uploaded — toggling privacy on discards
 * anything still queued rather than retroactively leaking it.
 */
@Singleton
class PlayHistoryLogger @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: NoizDatabase,
    private val scope: CoroutineScope,
) {

    fun recordStart(
        trackId: String,
        rendition: String?,
        offline: Boolean,
        isPrivate: Boolean,
    ): String {
        val id = UUID.randomUUID().toString()
        scope.launch {
            db.playEventDao().insert(
                PlayEventEntity(
                    id = id,
                    trackId = trackId,
                    startedAt = System.currentTimeMillis(),
                    playedMs = 0,
                    endedReason = "start",
                    sourceType = null,
                    sourceId = null,
                    rendition = rendition,
                    offline = offline,
                    private = isPrivate,
                ),
            )
        }
        return id
    }

    /**
     * A play counts once [playedMs] passes the meaningful threshold (30 s, or half of a
     * short track) — later updates just refine the stored duration.
     *
     * This must be an UPDATE, not an INSERT: the id is the primary key and inserts use
     * `OnConflictStrategy.IGNORE`, so re-inserting the same row was silently discarded
     * and every event shipped as `played_ms: 0`.
     */
    fun recordProgress(eventId: String, playedMs: Int) {
        scope.launch {
            db.playEventDao().updateProgress(
                id = eventId,
                playedMs = playedMs,
                endedReason = null,
            )
        }
    }

    fun recordEnd(eventId: String, reason: String, playedMs: Int) {
        scope.launch {
            db.playEventDao().updateProgress(
                id = eventId,
                playedMs = playedMs,
                endedReason = reason,
            )
            OutboxWorker.enqueue(appContext)
        }
    }

    /** Called when the user turns privacy on: queued-but-unsent events are dropped. */
    fun makeHistoryPrivate() {
        // Drops every still-unsent event, not only rows already flagged private —
        // toggling privacy on must not retroactively upload queued events.
        scope.launch { db.playEventDao().discardUnsynced() }
    }
}
