package com.noiz.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.noiz.network.NoizJsonConverterFactory

/**
 * Client construction for one server (§4.3).
 *
 * Three properties this class is responsible for:
 *
 *  1. **Tokens never leave the interceptor layer.** No API method takes an
 *     `Authorization` header; [AuthInterceptor] stamps it, and [TokenAuthenticator]
 *     rotates the refresh token exactly once per 401 and retries. A failed rotation
 *     surfaces as `401` to the caller, which is the signal to send the user back to
 *     Connect.
 *  2. **The base URL is user input.** It is normalised (scheme, trailing slash) before
 *     it reaches OkHttp, and `https` is enforced — production traffic must be TLS
 *     (§4.3). Retrofit still needs *a* base URL, so it gets a placeholder that is
 *     never dialed: every call is either relative to this client's base or absolute.
 *  3. **One client for blocking playback use.** The signed-URL resolver runs on
 *     ExoPlayer's loading thread and cannot suspend, so it gets a small synchronous
 *     helper ([StreamManifestSource]) on the same client instead of `runBlocking`
 *     around every Retrofit call.
 */

/** Where tokens come from. Implemented over `:core:datastore`'s SessionStore. */
interface TokenStore {
    /** Current pair, or `null` when signed out. */
    fun snapshot(): TokenSnapshot?

    /** Persisted by the authenticator after a successful refresh. */
    fun update(access: String, refresh: String, expiresAtEpochMs: Long)
}

data class TokenSnapshot(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

/** Signed base URL for the server the user connected to. */
fun interface ServerUrlProvider {
    fun baseUrl(): String
}

/**
 * Normalises user input into a base URL ending in `/`.
 *
 * Accepts `192.168.1.4:8000`, `noiz.example`, `https://noiz.example/api` and returns
 * `https://…/` — refusing anything that is not http(s) so a typo cannot silently send
 * credentials over an unrecognised scheme.
 *
 * The scheme check must run *before* `https://` is prepended; the previous ordering
 * made the check unreachable, so `ftp://noiz.example` became `https://ftp://noiz.example/`
 * and was handed to OkHttp as if it were a host.
 */
fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null

    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        // A scheme-looking prefix (anything matching `word:`) is a hard error rather
        // than something to prefix with https://.
        SCHEME_PREFIX.containsMatchIn(trimmed) -> return null
        else -> "https://$trimmed"
    }

    if (!withScheme.startsWith("https://", ignoreCase = true) &&
        !withScheme.startsWith("http://", ignoreCase = true)
    ) {
        return null
    }

    // Reject anything OkHttp would not accept as an absolute URL, so a malformed
    // value fails at the Connect screen instead of at the first request.
    val parsed = withScheme.toHttpUrlOrNull() ?: return null
    if (parsed.host.isBlank()) return null

    return parsed.newBuilder().addPathSegment("").build().toString()
}

private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/**
 * Strips the query string from a logged line. Signed stream URLs are bearer
 * credentials for six hours, so writing them to logcat hands out playable media
 * (§11.2 forbids recording full media URLs).
 */
internal fun String.redactQueryStrings(): String {
    val lineStart = indexOf("---> ").takeIf { it >= 0 }?.plus(4)
        ?: indexOf("<-- ").takeIf { it >= 0 }?.plus(4)
        ?: 0
    val queryStart = indexOf('?', startIndex = lineStart)
    if (queryStart < 0) return this

    val lineEnd = indexOf('\n', startIndex = queryStart)
        .let { if (it < 0) length else it }

    return substring(0, queryStart) + "?<redacted>" + substring(lineEnd)
}

/** Adds `Authorization: Bearer …` to every API call. */
class AuthInterceptor(
    private val tokens: TokenStore,
    private val serverUrl: ServerUrlProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Signed stream URLs already carry their own exp/sig parameters and must not
        // be re-stamped (§9.4): the signature *is* the credential.
        if (original.url.queryParameter("sig") != null) {
            return chain.proceed(original)
        }

        val access = tokens.snapshot()?.accessToken
        val authed = if (access.isNullOrBlank()) {
            original
        } else {
            original.newBuilder().header("Authorization", "Bearer $access").build()
        }

        // Retrofit was built with a placeholder base URL because the real server is
        // user-supplied and can change at runtime. Resolve relative paths against the
        // configured server here. The previous implementation computed `base` and then
        // discarded it, rewriting the host to the literal string "invalid" — so every
        // request failed with UnknownHostException.
        val resolved = resolveAgainstConfiguredServer(authed) ?: return chain.proceed(authed)
        return chain.proceed(resolved)
    }

    /**
     * Returns [request] with its scheme/host/port pointing at the configured server when
     * it was built against the Retrofit placeholder, or `null` when it already carries an
     * absolute URL from the caller.
     */
    private fun resolveAgainstConfiguredServer(request: Request): Request? {
        if (request.url.host != PLACEHOLDER_HOST) return null

        val base = serverUrl.baseUrl().trimEnd('/')
        if (base.isEmpty()) return null

        val baseUrl = base.toHttpUrlOrNull() ?: return null

        // Keep the placeholder request's path/query/fragment; only the origin is ours.
        val rebuilt = request.url.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()

        return request.newBuilder().url(rebuilt).build()
    }

    private companion object {
        const val PLACEHOLDER_HOST = "noiz.invalid"
    }
}

/**
 * Rotates the refresh token on 401 and replays the request once.
 *
 * A single-flight guard keeps a burst of parallel 401s (screen + worker + player)
 * from burning several refresh tokens in a row — the server rotates on every refresh,
 * so a lost rotation logs the user out.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val serverUrl: ServerUrlProvider,
    private val refreshCall: (refreshToken: String) -> retrofit2.Response<TokenResponseDto>?,
) : Authenticator {

    /**
     * Blocks every other 401-triggered refresh until the in-flight one finishes.
     *
     * The previous `AtomicBoolean` + `Thread.sleep(50)` version was not single-flight:
     * the loser woke up after a fixed delay, compared the still-unchanged token, gave
     * up and surfaced a 401 — logging the user out while a refresh was in progress.
     * Worse, a loser that did proceed issued a *second* rotation with an already-rotated
     * token, which the server treats as theft and revokes the whole device session.
     */
    private val refreshLock = Object()

    override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_ATTEMPTS) return null

        val snapshot = tokens.snapshot() ?: return null

        synchronized(refreshLock) {
            // Re-read inside the lock: another thread may have completed a refresh while
            // we were waiting. If the token already changed, use it and do NOT rotate
            // again.
            val current = tokens.snapshot()
            if (current != null && current.accessToken != snapshot.accessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${current.accessToken}")
                    .build()
            }

            val result = runCatching { refreshCall(snapshot.refreshToken) }.getOrNull()
            if (result == null || !result.isSuccessful) return null
            val body = result.body() ?: return null

            tokens.update(
                access = body.accessToken,
                refresh = body.refreshToken,
                expiresAtEpochMs = System.currentTimeMillis() + body.expiresIn * 1000L,
            )

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${body.accessToken}")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 1
        while (current?.priorResponse != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}

/**
 * Synchronous manifest fetch for the playback loading thread (§3.4).
 *
 * Returns `null` on any transport or decode failure — [com.noiz.playback.SignedUrlResolver]
 * turns that into an `IOException`, which the load-error policy then retries with
 * backoff rather than crashing the player.
 */
interface StreamManifestSource {
    fun manifestBlocking(trackId: String, rendition: String): MintedStreamUrl?
}

data class MintedStreamUrl(
    val renditionId: String,
    val url: String,
    val expiresAtEpochMs: Long,
) {
    fun isExpiringWithin(windowMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs - nowMs <= windowMs
}

class HttpStreamManifestClient(
    private val client: OkHttpClient,
    private val serverUrl: ServerUrlProvider,
    private val tokens: TokenStore,
) : StreamManifestSource {

    override fun manifestBlocking(trackId: String, rendition: String): MintedStreamUrl? {
        val base = serverUrl.baseUrl().trimEnd('/')
        if (base.isEmpty()) return null

        val request = Request.Builder()
            .url("$base/api/v1/tracks/$trackId/stream-manifest")
            .apply { tokens.snapshot()?.accessToken?.let { header("Authorization", "Bearer $it") } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = response.body?.string() ?: return null
            val manifest = NoizJson.decodeFromString(StreamManifestDto.serializer(), payload)

            // Never silently substitute a different rendition. Falling back to
            // `firstOrNull()` played e.g. a 64 kbps Opus file while the UI still claimed
            // "original lossless", which §2.2 forbids outright.
            val chosen = manifest.renditions.firstOrNull { it.id == rendition } ?: return null

            return MintedStreamUrl(
                renditionId = chosen.id,
                // The server returns a *relative* signed URL; make it absolute here so
                // the player can dial it without knowing the base URL.
                url = if (chosen.url.startsWith("http")) chosen.url else base + chosen.url,
                // `expires_at` is epoch seconds on the wire.
                expiresAtEpochMs = manifest.expiresAt * 1000L,
            )
        }
    }
}

/** Builds the two clients the app needs: API + blocking manifest. */
class NoizClientFactory(
    private val tokens: TokenStore,
    private val serverUrl: ServerUrlProvider,
    private val debug: Boolean = false,
) {

    /** Authenticated client used for every `/api/v1` call. */
    val apiClient: OkHttpClient by lazy {
        baseBuilder()
            .addInterceptor(AuthInterceptor(tokens, serverUrl))
            .authenticator(
                // TokenAuthenticator serialises refreshes internally, so the lambda
                // below does not need its own lock. Built on `plainClient`, which has no
                // authenticator, so a refresh can never recurse.
                TokenAuthenticator(tokens, serverUrl) { refreshToken ->
                    val base = serverUrl.baseUrl().trimEnd('/')
                    if (base.isEmpty()) {
                        return@TokenAuthenticator null
                    }
                    val jsonRequest = Request.Builder()
                        .url("$base/api/v1/auth/refresh")
                        .post(
                            NoizJson.encodeToString(
                                RefreshRequestDto.serializer(),
                                RefreshRequestDto(refreshToken),
                            ).toRequestBodyJson(),
                        )
                        .build()
                    val response = plainClient.newCall(jsonRequest).execute()
                    val bodyString = response.body?.string()
                    if (!response.isSuccessful || bodyString == null) {
                        retrofit2.Response.error(
                            response.code,
                            okhttp3.ResponseBody.create(null, bodyString.orEmpty()),
                        )
                    } else {
                        retrofit2.Response.success(
                            NoizJson.decodeFromString(TokenResponseDto.serializer(), bodyString),
                        )
                    }
                },
            )
            .build()
    }

    /** Unauthenticated client: refresh calls only. */
    private val plainClient: OkHttpClient by lazy { baseBuilder().build() }

    /** Client handed to ExoPlayer's upstream factory. */
    val streamClient: OkHttpClient by lazy {
        baseBuilder()
            .addInterceptor(AuthInterceptor(tokens, serverUrl))
            .build()
    }

    val api: NoizApi by lazy {
        Retrofit.Builder()
            // Placeholder: every call carries an absolute URL or is resolved by the
            // interceptor against the user-configured server.
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(apiClient)
            .addConverterFactory(NoizJsonConverterFactory.create())
            .build()
            .create(NoizApi::class.java)
    }

    private companion object {
        /** Rewritten to the real server by [AuthInterceptor]; see [PLACEHOLDER_HOST]. */
        const val PLACEHOLDER_BASE_URL = "https://noiz.invalid/"
    }

    val streamManifests: StreamManifestSource by lazy {
        HttpStreamManifestClient(streamClient, serverUrl, tokens)
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Range requests must never be buffered away or the seek path breaks (§6.2).
        .retryOnConnectionFailure(true)
        .apply {
            if (debug) {
                // Stream URLs carry `?exp=…&sig=…&user=…` — the signature IS the
                // credential (§9.4). HttpLoggingInterceptor emits the full request line
                // including the query string at EVERY level >= BASIC, so even BASIC
                // leaked playable signed URLs into logcat, against §11.2.
                addInterceptor(
                    HttpLoggingInterceptor { message ->
                        android.util.Log.d("NoizHttp", message.redactQueryStrings())
                    }.apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    },
                )
            }
        }

    private fun String.toRequestBodyJson(): okhttp3.RequestBody =
        toRequestBody("application/json; charset=utf-8".toMediaType())
}

/** Thrown when the server rejects credentials after a refresh attempt. */
class UnauthorizedException(cause: Throwable? = null) : IOException("Unauthorized", cause)
