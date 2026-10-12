package com.noiz.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Per-track R128 normalization (§6.4).
 *
 * Attached to every ExoPlayer's audio sink via
 * [androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain], so a
 * crossfade gives each track its own gain. Gain changes ramp over 50 ms at track
 * boundaries; a soft tanh limiter engages only when the requested boost would push
 * the true peak past the ceiling.
 *
 * Supported input: PCM 16-bit and PCM float (float output is enabled on the renderers
 * so 24-bit sources stay intact through the pipeline).
 */
class NormalizationProcessor(
    private val ceilingDbTp: Float = -1f,
) : BaseAudioProcessor() {

    /** Linear form of [ceilingDbTp]; the limiter asymptotes here, not at 1.0. */
    private val ceilingLinear: Float = 10f.pow(ceilingDbTp / 20f)

    /** Linear gain currently being ramped to. Written from the app thread. */
    @Volatile
    private var targetGainLinear: Float = 1f

    /** True when the lookahead limiter should shape peaks instead of clipping. */
    @Volatile
    private var limiterEngaged: Boolean = false

    /**
     * App-thread published gain request in dB. `queueInput` reads this once per buffer.
     *
     * The ramp fields below are only touched on the playback thread. They used to be
     * written by `setGain` from the app thread simultaneously, so a half-published step
     * could leave the applied gain permanently wrong (a track meant to sit at -6.0 dB
     * ending up at -6.4 dB).
     */
    @Volatile
    private var pendingGainDb: Float? = 0f

    private var currentGainLinear: Float = 1f
    private var rampStepPerSample: Float = 0f
    private var rampSamplesRemaining: Int = 0

    private var isFloat = false
    private var bytesPerSample = 2
    private var sampleRateHz = 48_000

    /**
     * Sets the gain for the *next* track. Call from the app thread before the track's
     * samples reach the sink; the ramp smooths any residual discontinuity.
     */
    fun setGain(trackLufs: Float?, truePeakDb: Float?, targetLufs: Float, rampMs: Int = 50) {
        val computed = computeGainDb(trackLufs, truePeakDb, targetLufs, ceilingDbTp)
        val linear = 10f.pow(computed / 20f).coerceIn(MIN_LINEAR_GAIN, MAX_LINEAR_GAIN)
        targetGainLinear = linear
        limiterEngaged = computed > 0f && truePeakDb != null &&
            truePeakDb + computed > ceilingDbTp
        pendingGainDb = computed
    }

    /** Pure mode / normalization off. */
    fun bypass() {
        targetGainLinear = 1f
        limiterEngaged = false
        pendingGainDb = null
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                isFloat = false
                bytesPerSample = 2
            }

            C.ENCODING_PCM_FLOAT -> {
                isFloat = true
                bytesPerSample = 4
            }

            // Pass unsupported encodings through untouched. Returning NOT_SET makes Media3 disable
            // the processor chain and tear down the AudioTrack, so an OEM path that hands
            // us PCM_24BIT_PACKED killed audio outright instead of passing it through.
            else -> return AudioFormat.NOT_SET
        }
        sampleRateHz = inputAudioFormat.sampleRate
        currentGainLinear = targetGainLinear
        rampSamplesRemaining = 0
        rampStepPerSample = 0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 hands PCM in native order; reading without this silently scrambles
        // samples on little-endian devices (which is every Android device).
        inputBuffer.order(ByteOrder.nativeOrder())
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val sampleCount = size / bytesPerSample

        // Consume the app thread's gain request here, on the playback thread, so the
        // ramp fields stay single-writer.
        pendingGainDb?.let { requested ->
            val target = 10f.pow(requested / 20f)
            if (target != targetGainLinear) {
                rampSamplesRemaining =
                    (sampleRateHz * RAMP_MS / 1000f).toInt().coerceAtLeast(1)
                rampStepPerSample = (target - currentGainLinear) / rampSamplesRemaining
                targetGainLinear = target
                pendingGainDb = null
            }
        }

        var gain = currentGainLinear
        var remaining = rampSamplesRemaining
        val step = rampStepPerSample
        val limiter = limiterEngaged
        val ceiling = ceilingLinear

        for (i in 0 until sampleCount) {
            if (remaining > 0) {
                gain += step
                remaining--
            } else {
                gain = targetGainLinear
            }

            if (isFloat) {
                val sample = inputBuffer.getFloat() * gain
                // The float path MUST clamp: `setEnableAudioFloatOutput(true)` is on, so
                // an over-driven sample goes straight to a float AudioTrack and clips
                // there. §6.1 guarantees normalization never clips the audio.
                output.putFloat(
                    if (limiter) softLimit(sample, ceiling) else sample.coerceIn(-1f, 1f),
                )
            } else {
                val sample = inputBuffer.getShort().toFloat() / 32_768f * gain
                val processed = if (limiter) softLimit(sample, ceiling) else sample
                output.putShort(
                    (processed * 32_767f).coerceIn(-32_768f, 32_767f).toInt().toShort(),
                )
            }
        }

        currentGainLinear = gain
        rampSamplesRemaining = remaining
        rampStepPerSample = if (remaining > 0) step else 0f
        output.flip()
    }

    override fun onFlush() {
        currentGainLinear = targetGainLinear
        rampSamplesRemaining = 0
        rampStepPerSample = 0f
    }

    override fun onReset() {
        currentGainLinear = 1f
        targetGainLinear = 1f
        limiterEngaged = false
        pendingGainDb = null
        rampSamplesRemaining = 0
        rampStepPerSample = 0f
    }

    companion object {
        /** Gain-change ramp (§6.4). */
        private const val RAMP_MS = 50

        /**
         * Sanity bounds on applied gain. EBU R128 treats anything under roughly −70
         * LUFS as unmeasurable; a corrupt metadata value could otherwise ask for ~99 dB
         * of boost (~89 000× linear).
         *
         * Computed from dB rather than literal floats because `const val` requires a
         * compile-time constant and `10f.pow(...)` is not one.
         */
        private val MIN_LINEAR_GAIN = 10f.pow(MIN_LINEAR_GAIN_DB / 20f)
        private val MAX_LINEAR_GAIN = 10f.pow(MAX_BOOST_DB / 20f)

        /** Never attenuate past this; the track is already far too quiet. */
        private const val MIN_LINEAR_GAIN_DB = -30f

        /** R128 cannot measure below this; treat anything lower as missing metadata. */
        const val MEASURABLE_FLOOR_LUFS = -70f

        /** Never boost more than this even when the true peak would allow it. */
        const val MAX_BOOST_DB = 12f

        /** Never attenuate past this; the track is already far too quiet. */
        const val MAX_ATTENUATION_DB = -30f
        /** Loudness targets: Off / Quiet / Normal / Loud (§6.4). */
        const val TARGET_QUIET_LUFS = -19f
        const val TARGET_NORMAL_LUFS = -14f
        const val TARGET_LOUD_LUFS = -11f

        /**
         * gain_dB = target − trackLUFS, clamped so truePeak + gain ≤ ceiling.
         * A boost above 0 dB is only allowed if that peak constraint holds.
         */
        fun computeGainDb(
            trackLufs: Float?,
            truePeakDb: Float?,
            targetLufs: Float,
            ceilingDbTp: Float = -1f,
        ): Float {
            if (trackLufs == null || !trackLufs.isFinite()) return 0f

            // EBU R128 measures down to roughly -70 LUFS. Anything lower is corrupt
            // metadata; without this guard `raw` could reach +3000 dB and the true-peak
            // cap would hand back a ~99 dB boost.
            if (trackLufs < MEASURABLE_FLOOR_LUFS) return 0f

            val raw = (targetLufs - trackLufs).coerceIn(MAX_BOOST_DB, MAX_ATTENUATION_DB)

            val peak = truePeakDb
            if (peak == null || !peak.isFinite() || peak > 0f) {
                // No usable true-peak measurement: never boost blind.
                return raw.coerceAtMost(0f)
            }

            // Attenuation always applies; boost is only allowed up to what the true
            // peak permits while staying under the ceiling.
            val headroom = ceilingDbTp - peak
            return if (raw > 0f) minOf(raw, maxOf(headroom, 0f)) else raw
        }
    }
}

/** Continuous soft knee: unity below the knee, asymptotic to [ceiling] above it. */
private fun softLimit(x: Float, ceiling: Float): Float {
    val knee = KNEE_LINEAR * ceiling
    val magnitude = abs(x)
    if (magnitude <= knee) return x

    val overshoot = magnitude - knee
    val headroom = (ceiling - knee).coerceAtLeast(1e-4f)
    val shaped = knee + headroom * (1f - exp(-overshoot / headroom))
    return if (x < 0f) -shaped else shaped
}

/** ≈ -3 dBFS, the conventional limiter knee. */
private const val KNEE_LINEAR = 0.7079f
