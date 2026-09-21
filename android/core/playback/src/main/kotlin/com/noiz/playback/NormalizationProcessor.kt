package com.noiz.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

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

    /** Linear gain currently being ramped to. Written from the app thread. */
    @Volatile
    private var targetGainLinear: Float = 1f

    /** True when the lookahead limiter should shape peaks instead of clipping. */
    @Volatile
    private var limiterEngaged: Boolean = false

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
        val linear = 10f.pow(computed / 20f)
        targetGainLinear = linear
        limiterEngaged = computed > 0f && truePeakDb != null && truePeakDb + computed > ceilingDbTp
        rampSamplesRemaining = (sampleRateHz * rampMs / 1000f).toInt().coerceAtLeast(1)
        rampStepPerSample = (targetGainLinear - currentGainLinear) / rampSamplesRemaining
    }

    /** Pure mode / normalization off. */
    fun bypass() {
        targetGainLinear = 1f
        limiterEngaged = false
        rampSamplesRemaining = (sampleRateHz * 50 / 1000f).toInt().coerceAtLeast(1)
        rampStepPerSample = (1f - currentGainLinear) / rampSamplesRemaining
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
        var gain = currentGainLinear
        var remaining = rampSamplesRemaining
        val step = rampStepPerSample

        for (i in 0 until sampleCount) {
            if (remaining > 0) {
                gain += step
                remaining--
            } else {
                gain = targetGainLinear
            }

            if (isFloat) {
                val sample = inputBuffer.getFloat()
                output.putFloat(applyGainFloat(sample * gain))
            } else {
                val sample = inputBuffer.getShort().toFloat() / 32_768f
                val processed = applyGainFloat(sample * gain) * 32_767f
                output.putShort(processed.coerceIn(-32_768f, 32_767f).toInt().toShort())
            }
        }

        currentGainLinear = gain
        rampSamplesRemaining = remaining
        rampStepPerSample = if (remaining > 0) step else 0f
        output.flip()
    }

    private fun applyGainFloat(sample: Float): Float =
        if (limiterEngaged) {
            // Soft knee: transparent below -3 dBFS, asymptotic toward the ceiling.
            if (abs(sample) < 0.7079f) sample else tanh(sample)
        } else {
            sample
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
        rampSamplesRemaining = 0
        rampStepPerSample = 0f
    }

    companion object {
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
            val raw = targetLufs - trackLufs
            if (truePeakDb == null || !truePeakDb.isFinite()) {
                return raw.coerceAtMost(0f)
            }
            val maxBoost = ceilingDbTp - truePeakDb
            return minOf(raw, maxOf(maxBoost, raw.coerceAtMost(0f)))
        }
    }
}
