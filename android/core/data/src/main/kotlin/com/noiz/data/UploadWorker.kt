package com.noiz.data

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noiz.common.formatBytes
import com.noiz.network.Bodies
import com.noiz.network.CompleteUploadRequestDto
import com.noiz.network.CreateUploadRequestDto
import com.noiz.network.NoizApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resumable, idempotent upload (§6.5).
 *
 * The protocol mirrors the server's: create a session with size + checksum, PUT each
 * part (a retried part simply overwrites itself, so a `Result.retry()` never
 * duplicates bytes), then finalize with the checksum. Progress is written after every
 * acknowledged part so a killed process resumes rather than restarting the file.
 *
 * URI permissions from the document picker are transient (§12.4): the worker copies the
 * stream straight through instead of assuming it can re-open the URI later.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: NoizApi,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
            ?: return Result.failure(errorData("Missing source URI"))
        val displayName = inputData.getString(KEY_NAME) ?: "audio"
        val sizeBytes = inputData.getLong(KEY_SIZE, 0L)

        return try {
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                val sha = applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        digest.update(buffer, 0, read)
                    }
                } ?: return@withContext Result.failure(errorData("Cannot read $displayName"))
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }

                val session = api.createUpload(
                    CreateUploadRequestDto(
                        fileName = displayName,
                        sizeBytes = sizeBytes,
                        partSizeBytes = PART_SIZE_BYTES,
                        sha256 = checksum,
                    ),
                )

                val received = session.receivedParts.toMutableSet()
                var partNumber = 1
                var offset = 0L

                while (offset < sizeBytes) {
                    val length = minOf(PART_SIZE_BYTES, sizeBytes - offset)

                    // A part the server already has is skipped: this is what makes a
                    // retry after a crash cheap instead of a full re-upload.
                    if (partNumber !in received) {
                        val bytes = readSlice(uri, offset, length)
                            ?: return@withContext Result.failure(errorData("Cannot read $displayName"))
                        api.uploadPart(session.id, partNumber, Bodies.bytes(bytes))
                    }

                    setProgress(
                        Data.Builder()
                            .putInt(KEY_PROGRESS, ((offset + length) * 100 / sizeBytes).toInt())
                            .putString(KEY_STATUS, "Uploading ${formatBytes(offset + length)} of ${formatBytes(sizeBytes)}")
                            .build(),
                    )

                    offset += length
                    partNumber++
                }

                api.completeUpload(session.id, CompleteUploadRequestDto(sha256 = checksum))
                Result.success(Data.Builder().putString(KEY_TRACK_HINT, displayName).build())
            }
        } catch (http: retrofit2.HttpException) {
            // 4xx (bad part, checksum mismatch, quota): retrying identically cannot help.
            if (http.code() in 400..499 && http.code() != 429) {
                Result.failure(errorData("Upload rejected (${http.code()})"))
            } else {
                Result.retry()
            }
        } catch (io: IOException) {
            Result.retry()
        } catch (security: SecurityException) {
            // The picker's transient grant is gone; the user must select again.
            Result.failure(errorData("File access was revoked"))
        }
    }

    private fun readSlice(uri: Uri, offset: Long, length: Long): ByteArray? =
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            if (offset > 0 && input.skip(offset) != offset) return@use null
            var read = 0
            val buffer = ByteArray(length.toInt())
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count == -1) break
                read += count
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_ERROR, message).build()

    companion object {
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "size"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_TRACK_HINT = "track_hint"

        /** 8 MiB — comfortably under typical proxy limits and cheap to retry. */
        const val PART_SIZE_BYTES = 8L * 1024 * 1024

        fun enqueue(context: Context, uris: List<Uri>) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val requests = uris.map { uri ->
                val name = queryDisplayName(context, uri) ?: "audio"
                val size = querySize(context, uri) ?: 0L

                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        Data.Builder()
                            .putString(KEY_URI, uri.toString())
                            .putString(KEY_NAME, name)
                            .putLong(KEY_SIZE, size)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                    .addTag("noiz-upload")
                    .build()
            }

            WorkManager.getInstance(context).enqueue(requests)
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? =
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }

        private fun querySize(context: Context, uri: Uri): Long? =
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
    }
}
