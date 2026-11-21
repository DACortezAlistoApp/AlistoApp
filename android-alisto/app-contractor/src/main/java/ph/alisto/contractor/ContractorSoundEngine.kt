package ph.alisto.contractor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

object ContractorSoundEngine {
    private const val SAMPLE_RATE = 24000
    @Volatile private var alarmActive = false
    @Volatile private var activeTrack: AudioTrack? = null

    /**
     * High-audibility repeating multi-harmonic dispatch alert routed via USAGE_ALARM
     * so riders on the road hear incoming offers clearly over traffic/helmet noise.
     */
    fun startIncomingOfferAlarm(context: Context) {
        stopIncomingOfferAlarm()
        alarmActive = true
        val appCtx = context.applicationContext
        thread(isDaemon = true, name = "AlistoOfferAlarm") {
            // Punchy 3-burst ascending dual-harmonic dispatch alert + brief pause
            val burst = buildSequencePcm(
                listOf(
                    ToneSeg(987.77, 1975.53, 140, 0.95),
                    ToneSeg(0.0, 0.0, 55, 0.0),
                    ToneSeg(1318.51, 2637.02, 140, 0.98),
                    ToneSeg(0.0, 0.0, 55, 0.0),
                    ToneSeg(1567.98, 3135.96, 260, 1.00),
                    ToneSeg(0.0, 0.0, 140, 0.0),
                    ToneSeg(1567.98, 2349.32, 240, 0.98)
                )
            )
            while (alarmActive) {
                vibratePattern(appCtx, longArrayOf(0, 260, 110, 260, 110, 380))
                playPcmBlocking(burst, AudioAttributes.USAGE_ALARM, trackAsAlarm = true)
                var slept = 0L
                while (alarmActive && slept < 950L) {
                    try { Thread.sleep(100L) } catch (_: InterruptedException) { break }
                    slept += 100L
                }
            }
        }
    }

    fun stopIncomingOfferAlarm() {
        alarmActive = false
        try {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.stop()
            activeTrack?.release()
        } catch (_: Exception) {}
        activeTrack = null
    }

    fun playAcceptedTone(context: Context? = null) {
        context?.let { vibratePattern(it, longArrayOf(0, 90, 50, 130)) }
        playAsync(
            listOf(
                ToneSeg(523.25, 1046.50, 95, 0.85),
                ToneSeg(659.25, 1318.51, 95, 0.88),
                ToneSeg(783.99, 1567.98, 105, 0.90),
                ToneSeg(1046.50, 2093.00, 180, 0.95)
            ),
            AudioAttributes.USAGE_NOTIFICATION
        )
    }

    fun playRejectedTone(context: Context? = null) {
        context?.let { vibratePattern(it, longArrayOf(0, 140)) }
        playAsync(
            listOf(
                ToneSeg(493.88, 739.99, 120, 0.78),
                ToneSeg(0.0, 0.0, 40, 0.0),
                ToneSeg(349.23, 523.25, 180, 0.80)
            ),
            AudioAttributes.USAGE_NOTIFICATION
        )
    }

    fun playStepUpdatedTone(context: Context? = null) {
        context?.let { vibratePattern(it, longArrayOf(0, 70)) }
        playAsync(
            listOf(
                ToneSeg(659.25, 1318.51, 85, 0.80),
                ToneSeg(0.0, 0.0, 35, 0.0),
                ToneSeg(880.00, 1760.00, 135, 0.86)
            ),
            AudioAttributes.USAGE_NOTIFICATION
        )
    }

    fun playCompletedTone(context: Context? = null) {
        context?.let { vibratePattern(it, longArrayOf(0, 100, 60, 100, 60, 220)) }
        playAsync(
            listOf(
                ToneSeg(587.33, 1174.66, 95, 0.88),
                ToneSeg(783.99, 1567.98, 95, 0.90),
                ToneSeg(987.77, 1975.53, 110, 0.92),
                ToneSeg(1174.66, 2349.32, 240, 0.96)
            ),
            AudioAttributes.USAGE_NOTIFICATION
        )
    }

    fun playDisputeTone(context: Context? = null) {
        context?.let { vibratePattern(it, longArrayOf(0, 220, 90, 220)) }
        playAsync(
            listOf(
                ToneSeg(932.33, 1396.91, 140, 0.90),
                ToneSeg(0.0, 0.0, 60, 0.0),
                ToneSeg(622.25, 932.33, 160, 0.88),
                ToneSeg(0.0, 0.0, 60, 0.0),
                ToneSeg(932.33, 1396.91, 200, 0.92)
            ),
            AudioAttributes.USAGE_ALARM
        )
    }

    private data class ToneSeg(val freq1: Double, val freq2: Double, val durationMs: Int, val gain: Double)

    private fun playAsync(segments: List<ToneSeg>, usage: Int) {
        thread(isDaemon = true, name = "AlistoTone") {
            val pcm = buildSequencePcm(segments)
            playPcmBlocking(pcm, usage, trackAsAlarm = false)
        }
    }

    private fun buildSequencePcm(segments: List<ToneSeg>): ShortArray {
        val totalSamples = segments.sumOf { (SAMPLE_RATE * it.durationMs) / 1000 }
        val out = ShortArray(totalSamples)
        var offset = 0
        for (seg in segments) {
            val count = (SAMPLE_RATE * seg.durationMs) / 1000
            val fadeSamples = (SAMPLE_RATE * 0.008).toInt().coerceAtLeast(1)
            for (i in 0 until count) {
                if (seg.freq1 <= 0.0 || seg.gain <= 0.0) {
                    out[offset + i] = 0
                } else {
                    val t = i.toDouble() / SAMPLE_RATE.toDouble()
                    val env = when {
                        i < fadeSamples -> i.toDouble() / fadeSamples
                        i > count - fadeSamples -> (count - i).toDouble() / fadeSamples
                        else -> 1.0
                    }
                    // Blend fundamental + bright harmonic so small phone speakers project loudly
                    val wave = 0.65 * sin(2.0 * PI * seg.freq1 * t) + 0.35 * sin(2.0 * PI * seg.freq2 * t)
                    val sample = (wave * env * seg.gain * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out[offset + i] = sample.toShort()
                }
            }
            offset += count
        }
        return out
    }

    private fun playPcmBlocking(pcm: ShortArray, usage: Int, trackAsAlarm: Boolean) {
        if (pcm.isEmpty()) return
        var track: AudioTrack? = null
        try {
            val byteLen = pcm.size * 2
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(byteLen)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(minBuf)
                .build()
            if (trackAsAlarm) activeTrack = track
            track.write(pcm, 0, pcm.size)
            track.play()
            val durMs = (pcm.size * 1000L) / SAMPLE_RATE + 40L
            var waited = 0L
            while (waited < durMs) {
                if (trackAsAlarm && !alarmActive) break
                try { Thread.sleep(40L) } catch (_: InterruptedException) { break }
                waited += 40L
            }
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
            if (trackAsAlarm && activeTrack == track) activeTrack = null
        }
    }

    @Suppress("DEPRECATION")
    private fun vibratePattern(context: Context, pattern: LongArray) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }
}
