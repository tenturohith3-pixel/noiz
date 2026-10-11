package com.noiz.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.noiz.model.NoizSession
import com.noiz.model.QualityProfile
import com.noiz.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore("noiz_session")
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("noiz_settings")

/**
 * Signed-in session (§4.3).
 *
 * Access/refresh tokens live here rather than in plain SharedPreferences. The store is
 * deliberately app-private and excluded from backup (`android:allowBackup="false"`),
 * which is the documented trade-off until the tokens move into Keystore-backed
 * encrypted storage.
 */
class SessionStore(private val context: Context) {

    val session: Flow<NoizSession?> = context.sessionDataStore.data.map { prefs ->
        val serverUrl = prefs[KEY_SERVER_URL]
        val accessToken = prefs[KEY_ACCESS_TOKEN]
        val refreshToken = prefs[KEY_REFRESH_TOKEN]
        val userId = prefs[KEY_USER_ID]
        if (serverUrl == null || accessToken == null || refreshToken == null || userId == null) {
            null
        } else {
            NoizSession(
                serverUrl = serverUrl.trimEnd('/'),
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochMs = prefs[KEY_EXPIRES_AT] ?: 0L,
                user = User(
                    id = userId,
                    username = prefs[KEY_USERNAME].orEmpty(),
                    displayName = prefs[KEY_DISPLAY_NAME].orEmpty(),
                    role = prefs[KEY_ROLE].orEmpty(),
                    canUpload = prefs[KEY_CAN_UPLOAD] ?: false,
                    canDownload = prefs[KEY_CAN_DOWNLOAD] ?: false,
                ),
            )
        }
    }

    /** Server URL is remembered before sign-in so the connect screen can prefill it. */
    val savedServerUrl: Flow<String> = context.sessionDataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    suspend fun serverUrl(): String = savedServerUrl.first()

    suspend fun write(session: NoizSession) {
        context.sessionDataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = session.serverUrl
            prefs[KEY_ACCESS_TOKEN] = session.accessToken
            prefs[KEY_REFRESH_TOKEN] = session.refreshToken
            prefs[KEY_EXPIRES_AT] = session.expiresAtEpochMs
            prefs[KEY_USER_ID] = session.user.id
            prefs[KEY_USERNAME] = session.user.username
            prefs[KEY_DISPLAY_NAME] = session.user.displayName
            prefs[KEY_ROLE] = session.user.role
            prefs[KEY_CAN_UPLOAD] = session.user.canUpload
            prefs[KEY_CAN_DOWNLOAD] = session.user.canDownload
        }
    }

    suspend fun updateTokens(accessToken: String, refreshToken: String, expiresAtEpochMs: Long) {
        context.sessionDataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = accessToken
            prefs[KEY_REFRESH_TOKEN] = refreshToken
            prefs[KEY_EXPIRES_AT] = expiresAtEpochMs
        }
    }

    suspend fun rememberServerUrl(url: String) {
        context.sessionDataStore.edit { prefs -> prefs[KEY_SERVER_URL] = url.trimEnd('/') }
    }

    suspend fun clear(keepServerUrl: Boolean = true) {
        val retained = if (keepServerUrl) serverUrl() else null
        context.sessionDataStore.edit { prefs ->
            prefs.clear()
            retained?.let { prefs[KEY_SERVER_URL] = it }
        }
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_CAN_UPLOAD = booleanPreferencesKey("can_upload")
        val KEY_CAN_DOWNLOAD = booleanPreferencesKey("can_download")
    }
}

/**
 * Playback + appearance preferences (§6.1, §6.4, §7.2).
 *
 * Read once when the playback service starts ([com.noiz.playback.PlaybackSettings])
 * and observed live by the settings screen; every field has a default that matches the
 * PRD so a fresh install is correct without a migration or an onboarding step.
 */
data class NoizSettings(
    /** 0 disables dual-player mode entirely — the gapless code path (§6.2). */
    val crossfadeMs: Long = 0L,
    val normalizationEnabled: Boolean = true,
    /** Loudness target for normalization; ignored when [normalizationEnabled] is false. */
    val targetLufs: Float = -14f,
    /** True = leave the source untouched (bit-perfect when the device path allows). */
    val pureMode: Boolean = false,
    val qualityProfile: QualityProfile = QualityProfile.HIGH,
    /** `auto | full | static | opaque` — persisted form of GlassTierPreference. */
    val glassTier: String = "auto",
    /** Maps to the platform "Remove transparency" accessibility setting. */
    val reduceTransparency: Boolean = false,
    /** Play history stays on device and is never uploaded (§6.6, §13). */
    val privateHistory: Boolean = false,
    /** Skip crossfade for spoken-word content when the catalog flags it. */
    val spokenWordCrossfadeOff: Boolean = true,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<NoizSettings> = context.settingsDataStore.data.map { prefs ->
        NoizSettings(
            crossfadeMs = prefs[KEY_CROSSFADE_MS] ?: 0L,
            normalizationEnabled = prefs[KEY_NORMALIZATION] ?: true,
            targetLufs = prefs[KEY_TARGET_LUFS] ?: -14f,
            pureMode = prefs[KEY_PURE_MODE] ?: false,
            qualityProfile = QualityProfile.fromWire(prefs[KEY_QUALITY]),
            glassTier = prefs[KEY_GLASS_TIER] ?: "auto",
            reduceTransparency = prefs[KEY_REDUCE_TRANSPARENCY] ?: false,
            privateHistory = prefs[KEY_PRIVATE_HISTORY] ?: false,
            spokenWordCrossfadeOff = prefs[KEY_SPOKEN_WORD] ?: true,
        )
    }

    suspend fun current(): NoizSettings = settings.first()

    suspend fun setCrossfadeMs(value: Long) = edit { it[KEY_CROSSFADE_MS] = value.coerceIn(0, MAX_CROSSFADE_MS) }

    suspend fun setNormalization(enabled: Boolean) = edit { it[KEY_NORMALIZATION] = enabled }

    suspend fun setTargetLufs(value: Float) = edit { it[KEY_TARGET_LUFS] = value.coerceIn(-24f, -6f) }

    suspend fun setPureMode(enabled: Boolean) = edit { it[KEY_PURE_MODE] = enabled }

    suspend fun setQualityProfile(profile: QualityProfile) = edit { it[KEY_QUALITY] = profile.wire }

    suspend fun setGlassTier(value: String) = edit { it[KEY_GLASS_TIER] = value }

    suspend fun setReduceTransparency(enabled: Boolean) = edit { it[KEY_REDUCE_TRANSPARENCY] = enabled }

    suspend fun setPrivateHistory(enabled: Boolean) = edit { it[KEY_PRIVATE_HISTORY] = enabled }

    suspend fun setSpokenWordCrossfadeOff(enabled: Boolean) = edit { it[KEY_SPOKEN_WORD] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    companion object {
        /** §6.1: crossfade is a 1–12 s preference; 0 means "gapless, not dual". */
        const val MAX_CROSSFADE_MS = 12_000L

        private val KEY_CROSSFADE_MS = longPreferencesKey("crossfade_ms")
        private val KEY_NORMALIZATION = booleanPreferencesKey("normalization_enabled")
        private val KEY_TARGET_LUFS = floatPreferencesKey("target_lufs")
        private val KEY_PURE_MODE = booleanPreferencesKey("pure_mode")
        private val KEY_QUALITY = stringPreferencesKey("quality_profile")
        private val KEY_GLASS_TIER = stringPreferencesKey("glass_tier")
        private val KEY_REDUCE_TRANSPARENCY = booleanPreferencesKey("reduce_transparency")
        private val KEY_PRIVATE_HISTORY = booleanPreferencesKey("private_history")
        private val KEY_SPOKEN_WORD = booleanPreferencesKey("spoken_word_crossfade_off")
    }
}
