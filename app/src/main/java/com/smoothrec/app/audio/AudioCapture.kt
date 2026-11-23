package com.smoothrec.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.recording.AudioSourceOption
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.tanh

/** Formato PCM real seleccionado para la pista de audio. */
data class AudioFormatSpec(
    val sampleRate: Int,
    val channelConfig: Int,
) {
    val channelCount: Int
        get() = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1

    val stereo: Boolean get() = channelCount == 2

    fun buildAudioFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(channelConfig)
        .build()
}

interface PcmProvider {
    /** Número de muestras (no bytes) escritas en el buffer. */
    fun read(buffer: ShortArray): Int
}

/** Una fuente PCM: audio interno de otras apps o micrófono. */
class PcmSource private constructor(
    private val record: AudioRecord,
    val spec: AudioFormatSpec,
) : PcmProvider {

    private var started = false

    fun start() {
        if (started) return
        try {
            record.startRecording()
            started = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo iniciar AudioRecord", e)
        }
    }

    override fun read(buffer: ShortArray): Int = readInto(buffer, 0, buffer.size)

    fun readInto(buffer: ShortArray, offset: Int, length: Int): Int {
        if (!started || length <= 0) return 0
        return try {
            // No bloqueante: si la fuente no entrega datos devolvemos 0 y el
            // codificador rellena con silencio sin quedar colgado al detener.
            val count = record.read(buffer, offset, length, AudioRecord.READ_NON_BLOCKING)
            if (count > 0) count else 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord ya estaba detenido", e)
        }
    }

    fun release() {
        stop()
        try {
            record.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar AudioRecord", e)
        }
    }

    companion object {
        private const val TAG = "PcmSource"

        fun createInternal(projection: MediaProjection, spec: AudioFormatSpec): PcmSource? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(
                    spec.sampleRate,
                    spec.channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) return null
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(spec.buildAudioFormat())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                PcmSource(record, spec)
            } catch (e: Exception) {
                Log.w(TAG, "Audio interno no disponible: $spec", e)
                null
            }
        }

        fun createMic(spec: AudioFormatSpec): PcmSource? {
            return try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    spec.sampleRate,
                    spec.channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) return null
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(spec.buildAudioFormat())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                PcmSource(record, spec)
            } catch (e: Exception) {
                Log.w(TAG, "Micrófono no disponible: $spec", e)
                null
            }
        }
    }
}

/** Mezcla dos fuentes PCM con limitación suave para evitar saturación. */
class AudioMixer(private val sources: List<PcmSource>) : PcmProvider {

    override fun read(buffer: ShortArray): Int {
        if (sources.size == 1) return sources[0].read(buffer)
        if (sources.size < 2) return 0

        val left = ShortArray(buffer.size)
        val right = ShortArray(buffer.size)
        val leftCount = readFully(sources[0], left)
        val rightCount = readFully(sources[1], right)
        val count = min(leftCount, rightCount)

        for (i in 0 until count) {
            val mixed = left[i].toFloat() * MIX_GAIN + right[i].toFloat() * MIX_GAIN
            buffer[i] = softLimit(mixed)
        }
        return count
    }

    private fun readFully(source: PcmSource, destination: ShortArray): Int {
        var total = 0
        while (total < destination.size) {
            val read = source.readInto(destination, total, destination.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun softLimit(value: Float): Short {
        val normalized = value / 32768f
        val limited = if (abs(normalized) <= KNEE) {
            normalized
        } else {
            sign(normalized) * (KNEE + (1f - KNEE) * tanh((abs(normalized) - KNEE) / (1f - KNEE)))
        }
        return (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    private companion object {
        const val MIX_GAIN = 0.7f
        const val KNEE = 0.75f
    }
}

/**
 * Si la fuente elegida entrega silencio de forma sostenida, pasa a una fuente
 * alternativa real (normalmente el micrófono) en lugar de grabar un MP4 mudo.
 * El cambio es permanente durante la sesión para evitar alternancias.
 */
class SilenceFallbackProvider(
    private val primary: PcmProvider,
    private val fallback: PcmProvider?,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val onFallback: () -> Unit,
) : PcmProvider {

    @Volatile
    private var usingFallback = false
    private var silentFrames = 0

    override fun read(buffer: ShortArray): Int {
        if (usingFallback) return fallback?.read(buffer) ?: 0
        val count = primary.read(buffer)
        val frames = if (count > 0) count / channelCount else buffer.size / channelCount
        silentFrames = if (count > 0 && hasSignal(buffer, count)) 0 else silentFrames + frames
        if (fallback != null && silentFrames >= sampleRate * SILENCE_SECONDS) {
            usingFallback = true
            silentFrames = 0
            Log.w(TAG, "Fuente principal sin señal durante ${SILENCE_SECONDS}s: se usa la alternativa")
            onFallback()
            return fallback.read(buffer)
        }
        return count
    }

    private fun hasSignal(buffer: ShortArray, count: Int): Boolean {
        for (i in 0 until count) {
            if (buffer[i].toInt() != 0) return true
        }
        return false
    }

    private companion object {
        const val TAG = "SilenceFallbackProvider"
        const val SILENCE_SECONDS = 3
    }
}

/** Fuente PCM lista para codificar. */
data class AudioPlan(
    val provider: PcmProvider,
    val spec: AudioFormatSpec,
    val sources: List<PcmSource>,
    val noticeRes: Int? = null,
) {
    fun release() = sources.forEach { it.release() }
}

/** Resultado de la detección real: puede incluir un aviso para el usuario. */
data class AudioSetupResult(
    val plan: AudioPlan?,
    val noticeRes: Int? = null,
)

/** Detecta qué modalidad y formato puede usar realmente este dispositivo. */
object AudioCaptureFactory {

    private const val TAG = "AudioCaptureFactory"
    private val SAMPLE_RATES = intArrayOf(48_000, 44_100)
    private val CHANNEL_CONFIGS = intArrayOf(
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.CHANNEL_IN_MONO,
    )

    fun create(
        context: Context,
        projection: MediaProjection,
        option: AudioSourceOption,
        onFallback: () -> Unit = {},
    ): AudioSetupResult {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AudioSetupResult(null, R.string.audio_fallback_permission)
        }

        for (rate in SAMPLE_RATES) {
            for (channels in CHANNEL_CONFIGS) {
                val spec = AudioFormatSpec(rate, channels)
                if (!aacEncoderAvailable(spec)) continue

                val plan = tryCreateSources(projection, option, spec, onFallback)
                if (plan != null) {
                    plan.sources.forEach { it.start() }
                    Log.i(
                        TAG,
                        "Audio ${option.key}: $rate Hz, ${spec.channelCount} canales " +
                            "(estéreo=${spec.stereo}), fuentes=${plan.sources.size}",
                    )
                    return AudioSetupResult(plan, plan.noticeRes)
                }
            }
        }

        val notice = when (option) {
            AudioSourceOption.INTERNAL -> R.string.audio_fallback_internal
            AudioSourceOption.MIC -> R.string.audio_fallback_mic
            AudioSourceOption.MIXED -> R.string.audio_fallback_mixed
        }
        return AudioSetupResult(null, notice)
    }

    private fun tryCreateSources(
        projection: MediaProjection,
        option: AudioSourceOption,
        spec: AudioFormatSpec,
        onFallback: () -> Unit,
    ): AudioPlan? {
        val sources = mutableListOf<PcmSource>()
        fun releaseAll() {
            sources.forEach { it.release() }
            sources.clear()
        }

        when (option) {
            AudioSourceOption.INTERNAL -> {
                val internal = PcmSource.createInternal(projection, spec) ?: return null
                sources.add(internal)
                // La captura de reproducción depende de que la app de origen
                // permita ser grabada. Si no entrega señal, el micrófono evita
                // que la pista AAC quede compuesta solo por silencio.
                val mic = PcmSource.createMic(spec)
                if (mic != null) sources.add(mic)
                val provider = if (mic != null) {
                    SilenceFallbackProvider(
                        internal,
                        mic,
                        spec.sampleRate,
                        spec.channelCount,
                        onFallback,
                    )
                } else {
                    internal
                }
                return AudioPlan(provider, spec, sources.toList())
            }
            AudioSourceOption.MIC -> {
                val mic = PcmSource.createMic(spec) ?: return null
                sources.add(mic)
            }
            AudioSourceOption.MIXED -> {
                val internal = PcmSource.createInternal(projection, spec)
                val mic = PcmSource.createMic(spec)
                if (internal != null) sources.add(internal)
                if (mic != null) sources.add(mic)
                if (sources.isEmpty()) return null
                if (sources.size == 1) {
                    val notice = if (mic != null) R.string.audio_fallback_mixed_mic
                    else R.string.audio_fallback_mixed_internal
                    return AudioPlan(sources[0], spec, sources.toList(), notice)
                }
            }
        }

        val provider: PcmProvider = if (sources.size == 1) sources[0] else AudioMixer(sources)
        return AudioPlan(provider, spec, sources.toList())
    }

    private fun aacEncoderAvailable(spec: AudioFormatSpec): Boolean {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            spec.sampleRate,
            spec.channelCount,
        )
        return MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format) != null
    }
}
