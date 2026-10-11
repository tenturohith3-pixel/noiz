package com.noiz.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds the ExoPlayer instances used by [DualPlayerEngine].
 *
 * Reference implementation from PRD §3.4. Two invariants matter:
 *  1. the cache key is the stable `noiz://track/{id}/{rendition}` URI and signatures
 *     are injected *below* the cache, so token rotation never invalidates bytes;
 *  2. both players share one audio session id, so a single DynamicsProcessing EQ
 *     (attached once to that session) processes the crossfaded mix.
 */
class ExoPlayerAssembler(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val streamCache: SimpleCache,
    private val resolver: SignedUrlResolver,
    private val errorPolicy: NoizLoadErrorHandlingPolicy,
) {

    /** Generated once; the EQ attaches to this id (§6.5). */
    val sharedAudioSessionId: Int =
        context.getSystemService(AudioManager::class.java)
            ?.generateAudioSessionId()
            ?: C.AUDIO_SESSION_ID_UNSET

    /**
     * @param handleFocus only the primary player holds audio focus; the secondary is
     *   built with `false` so a crossfade never double-requests focus (§11 row 4).
     */
    fun buildPlayer(
        handleFocus: Boolean,
        normalizer: NormalizationProcessor,
    ): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            /**
             * Appends the normalizer to Media3's *own* default chain.
             *
             * `DefaultAudioProcessorChain(vararg)` replaces the chain rather than
             * appending, so passing only the normalizer silently removed resampling and
             * channel downmix — playback then breaks or sounds wrong on any device whose
             * native output rate differs from the source. Overriding this hook keeps the
             * defaults (Sonic resampler, channel handling) and adds our gain stage.
             */
            override fun getDefaultAudioProcessorChain(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): List<AudioProcessor> {
                val defaults = super.getDefaultAudioProcessorChain(
                    context,
                    enableFloatOutput,
                    enableAudioTrackPlaybackParams,
                )
                // Gain belongs *after* resampling, so the limiter sees the signal at the
                // device's actual rate.
                return defaults + normalizer
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    // float PCM keeps 24-bit sources intact through the DSP chain
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
        }.setEnableAudioFloatOutput(true)

        val upstream = ResolvingDataSource.Factory(
            OkHttpDataSource.Factory(okHttpClient),
            resolver,
        )

        val cacheFactory = CacheDataSource.Factory()
            .setCache(streamCache)                                   // SimpleCache, LRU, 2 GB default
            .setUpstreamDataSourceFactory(upstream)                  // cache key = stable noiz:// URI
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)                  // VBR MP3 without a Xing header

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory, extractors))
            // Without this the whole NoizLoadErrorHandlingPolicy is discarded and
            // Media3's default applies: 1/2/3/4/5/5 s then give up after ~6 tries. §12.3
            // wants a transient dropout to survive, not kill playback.
            .setLoadErrorHandlingPolicy(errorPolicy)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 60_000, 1_000, 3_000)   // min, max, start, rebuffer
                    .setTargetBufferBytes(64 * 1024 * 1024)              // 60 s of ~9 Mbps hi-res FLAC
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleFocus,
            )
                    .setHandleAudioBecomingNoisy(true)                       // pause on unplug / BT drop
            .setWakeMode(C.WAKE_MODE_NETWORK)                        // CPU + Wi-Fi lock while streaming
            .build()
    }

    /** AudioProcessors are single-use per sink; build a fresh one per player. */
    fun newNormalizer(): NormalizationProcessor = NormalizationProcessor()

    /** Exposed so [DualPlayerEngine] can rebuild the upstream when tokens rotate. */
    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
}
