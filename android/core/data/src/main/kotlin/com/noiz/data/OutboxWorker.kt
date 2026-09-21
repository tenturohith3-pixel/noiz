package com.noiz.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noiz.database.NoizDatabase
import com.noiz.database.OutboxEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Wire format (§8.4, §9.2)
// ---------------------------------------------------------------------------

@Serializable
data class OutboxOpDto(
    @SerialName("op_id") val opId: String,
    val type: String,
    /**
     * The repository already stores the op body as JSON text; embedding it as a
     * [JsonElement] forwards it verbatim instead of double-encoding it, so the worker
     * never needs to know the shape of every op type. `op_id` is the idempotency key.
     */
    val payload: JsonElement,
    val hlc: String,
)

@Serializable
data class OutboxBatchRequest(val ops: List<OutboxOpDto>)

@Serializable
data class OutboxOpResultDto(
    @SerialName("op_id") val opId: String,
    val status: String,
    val error: String? = null,
)

@Serializable
data class OutboxBatchResponse(val results: List<OutboxOpResultDto> = emptyList())

@Serializable
data class PlayEventDto(
    val id: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("played_ms") val playedMs: Int,
    @SerialName("ended_reason") val endedReason: String,
    val rendition: String? = null,
    val offline: Boolean = false,
    val private: Boolean = false,
)

@Serializable
data class PlayEventsRequest(val events: List<PlayEventDto>)

interface SyncApi {
    @POST("api/v1/sync/outbox")
    suspend fun pushOutbox(@Body body: OutboxBatchRequest): OutboxBatchResponse

    @POST("api/v1/me/events")
    suspend fun pushPlayEvents(@Body body: PlayEventsRequest)
}

// ---------------------------------------------------------------------------
// Worker
// ---------------------------------------------------------------------------

/**
 * Flushes the local mutation outbox (§8.4).
 *
 * Guarantees relied on by the sync design:
 *  - each op carries a client-generated `opId`, so the server processes it exactly
 *    once and a retry after a timeout is harmless;
 *  - ops are sent oldest-first, in batches of [BATCH_SIZE], which preserves HLC order
 *    for last-writer-wins fields;
 *  - a per-op rejection (`conflict` / `invalid`) is recorded and the op is dropped —
 *    retrying a permanently invalid mutation would wedge the queue forever.
 *
 * Triggered: periodically (15 min), on app foreground, and expedited ~5 s after any
 * outbox write. All triggers share one unique work name with KEEP, so a burst of
 * likes produces a single upload.
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: NoizDatabase,
    private val api: SyncApi,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outbox = db.outboxDao()
        val events = db.playEventDao()

        return try {
            val batch = outbox.nextBatch(BATCH_SIZE)
            if (batch.isNotEmpty()) {
                val response = api.pushOutbox(
                    OutboxBatchRequest(batch.map { it.toDto() }),
                )
                handleResults(batch, response)
            }

            flushPlayEvents()

            // Anything that failed transiently stays put and is retried with backoff.
            if (outbox.nextBatch(1).isNotEmpty()) Result.retry() else Result.success()
        } catch (unauthorized: retrofit2.HttpException) {
            // The auth interceptor refreshes the access token once; if we still get a
            // 401 the refresh token is gone and the user must sign in again.
            if (unauthorized.code() == 401) Result.failure() else Result.retry()
        } catch (io: java.io.IOException) {
            Result.retry()
        }
    }

    /**
     * Acknowledged and permanently-rejected ops are removed; transient failures keep
     * their row and bump `attempts`, and are dropped once they exceed [MAX_ATTEMPTS]
     * so a single poison op cannot block the queue.
     */
    private suspend fun handleResults(batch: List<OutboxEntity>, response: OutboxBatchResponse) {
        val dao = db.outboxDao()
        val byId = response.results.associateBy { it.opId }

        val acknowledged = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        batch.forEach { op ->
            val result = byId[op.opId]
            when (result?.status) {
                "ok" -> acknowledged += op.opId
                "conflict", "invalid" -> rejected += op.opId
                else -> dao.recordFailure(op.opId, result?.error ?: "no result")
            }
        }

        dao.deleteByIds(acknowledged + rejected)
        dao.dropExhausted(MAX_ATTEMPTS)
    }

    private suspend fun flushPlayEvents() {
        val dao = db.playEventDao()
        val unsynced = dao.unsynced(PLAY_EVENT_BATCH)
        if (unsynced.isEmpty()) return

        // Play events are idempotent by id, so a partial failure just re-sends them.
        api.pushPlayEvents(
            PlayEventsRequest(
                unsynced.map { event ->
                    PlayEventDto(
                        id = event.id,
                        trackId = event.trackId,
                        startedAt = toRfc3339(event.startedAt),
                        playedMs = event.playedMs,
                        endedReason = event.endedReason,
                        rendition = event.rendition,
                        offline = event.offline,
                        private = event.private,
                    )
                },
            ),
        )
        dao.markSynced(unsynced.map { it.id })
    }

    private fun OutboxEntity.toDto(): OutboxOpDto = OutboxOpDto(
        opId = opId,
        type = type,
        payload = JSON.parseToJsonElement(payloadJson),
        hlc = hlc,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        const val UNIQUE_NAME = "noiz-outbox-flush"
        const val BATCH_SIZE = 100
        const val PLAY_EVENT_BATCH = 200
        const val MAX_ATTEMPTS = 12
        const val DEBOUNCE_SECONDS = 5L

        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Expedited one-shot flush. `ExistingWorkPolicy.REPLACE` on the expedited request
         * collapses a burst of writes into one upload; KEEP would let an in-flight batch
         * finish and drop the newer trigger.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(NETWORK_CONSTRAINTS)
                .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun toRfc3339(epochMs: Long): String =
            java.time.Instant.ofEpochMilli(epochMs)
                .atOffset(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
