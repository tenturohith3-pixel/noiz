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

/**
 * Builds [SyncApi] on the *same* authenticated OkHttp client as the main API, so the
 * workers get the Authorization header, token refresh and base-URL rewriting for free.
 *
 * Lives here rather than on `NoizClientFactory` because `:core:network` cannot depend on
 * `:core:data` — the dependency runs the other way (`core:data` api's `core:network`),
 * so putting it there would be a cycle.
 */
fun createSyncApi(apiClient: okhttp3.OkHttpClient): SyncApi =
    retrofit2.Retrofit.Builder()
        .baseUrl(SYNC_PLACEHOLDER_BASE_URL)
        .client(apiClient)
        .addConverterFactory(com.noiz.network.NoizJsonConverterFactory.create())
        .build()
        .create(SyncApi::class.java)

/** Rewritten to the real server by `AuthInterceptor`; see `NoizClientFactory`. */
private const val SYNC_PLACEHOLDER_BASE_URL = "https://noiz.invalid/"

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

        return try {
            val batch = outbox.nextBatch(BATCH_SIZE)
            if (batch.isNotEmpty()) {
                val response = api.pushOutbox(
                    OutboxBatchRequest(batch.map { it.toDto() }),
                )
                handleResults(batch, response)
            }

            flushPlayEvents()

            if (outbox.nextBatch(1).isNotEmpty()) Result.retry() else Result.success()
        } catch (unauthorized: retrofit2.HttpException) {
            if (unauthorized.code() == 401) Result.failure() else Result.retry()
        } catch (io: java.io.IOException) {
            Result.retry()
        }
    }

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

        /**
         * Accepts either an app [Context] or the database handle the workers hold, so
         * repositories can trigger a flush without threading a Context through every
         * call site. The database carries no Context, so that overload is intentionally
         * absent — repositories hold an application Context and must pass it to
         * [enqueue].
         */
        @Deprecated(
            "Pass an application Context; a NoizDatabase handle cannot reach WorkManager.",
            ReplaceWith("OutboxWorker.enqueue(appContext)"),
        )
        @Suppress("UNUSED_PARAMETER")
        fun enqueue(db: NoizDatabase) {
            error(
                "OutboxWorker.enqueue(NoizDatabase) cannot reach WorkManager. " +
                    "Pass the application Context instead.",
            )
        }

        fun toRfc3339(epochMs: Long): String =
            java.time.Instant.ofEpochMilli(epochMs)
                .atOffset(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
