package com.noiz.app

import android.content.Context
import com.noiz.common.DefaultNoizDispatchers
import com.noiz.common.HlcClock
import com.noiz.datastore.SessionStore
import com.noiz.database.NoizDatabase
import com.noiz.network.NoizApi
import com.noiz.network.NoizClientFactory
import com.noiz.network.ServerUrlProvider
import com.noiz.network.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * DI bindings for the object graph.
 *
 * `AppGraph` deliberately stays a hand-written service locator for the *playback*
 * collaborators, because `PlaybackService` is instantiated by the framework and cannot
 * take constructor injection. Everything that Hilt constructs itself — the Room
 * database, the Retrofit API, the clock — is provided here instead, which is what makes
 * the `@HiltWorker` classes constructible at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NoizDatabase =
        NoizDatabase.get(context)

    @Provides
    @Singleton
    fun provideHlcClock(): HlcClock = HlcClock()

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore =
        SessionTokenStore(context)

    @Provides
    @Singleton
    fun provideServerUrl(@ApplicationContext context: Context): ServerUrlProvider =
        SessionServerUrl(context)

    @Provides
    @Singleton
    fun provideClientFactory(
        tokens: TokenStore,
        serverUrl: ServerUrlProvider,
    ): NoizClientFactory = NoizClientFactory(tokens, serverUrl)

    @Provides
    @Singleton
    fun provideNoizApi(factory: NoizClientFactory): NoizApi = factory.api

    /**
     * `SyncApi` is the retrofit interface the outbox/upload workers post through. It is
     * built on the same authenticated OkHttp client as the main API, so auth, token
     * refresh and base-URL rewriting behave identically.
     */
    @Provides
    @Singleton
    fun provideSyncApi(factory: NoizClientFactory): com.noiz.data.SyncApi =
        com.noiz.data.createSyncApi(factory.apiClient)

    /** Application-scoped coroutine scope for fire-and-forget repository writes. */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + DefaultNoizDispatchers.io)
}

/**
 * Token + server-URL store backed by the DataStore session.
 *
 * These wrap [SessionStore] so Hilt can inject the token dependencies the workers and
 * the auth interceptor need. Reads are blocking by design — both call sites already run
 * on a background thread (an OkHttp dispatcher thread or the loader thread), and the
 * values are cached on first read so this is not on a hot path.
 */
private class SessionTokenStore(private val context: Context) : TokenStore {

    @Volatile
    private var cached: com.noiz.network.TokenSnapshot? = null

    override fun snapshot(): com.noiz.network.TokenSnapshot? {
        cached?.let { snap ->
            // A cached access token past its expiry must not be reused: the API client
            // would attach it to every request until the process restarted.
            if (snap.expiresAtEpochMs <= 0L || snap.expiresAtEpochMs > System.currentTimeMillis()) {
                return snap
            }
        }
        cached = null
        val fresh = kotlinx.coroutines.runBlocking {
            SessionStore(context).session.first()?.let {
                com.noiz.network.TokenSnapshot(
                    accessToken = it.accessToken,
                    refreshToken = it.refreshToken,
                    expiresAtEpochMs = it.expiresAtEpochMs,
                )
            }
        }
        cached = fresh
        return fresh
    }

    override fun update(access: String, refresh: String, expiresAtEpochMs: Long) {
        cached = com.noiz.network.TokenSnapshot(access, refresh, expiresAtEpochMs)
        kotlinx.coroutines.runBlocking {
            SessionStore(context).updateTokens(access, refresh, expiresAtEpochMs)
        }
    }
}

private class SessionServerUrl(private val context: Context) : ServerUrlProvider {
    override fun baseUrl(): String =
        kotlinx.coroutines.runBlocking { SessionStore(context).serverUrl() }
}