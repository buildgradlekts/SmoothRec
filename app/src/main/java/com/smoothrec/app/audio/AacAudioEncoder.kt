package com.smoothrec.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.smoothrec.app.encoder.EncoderOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Codificador AAC en un hilo propio. Durante el arranque alimenta silencio para
 * obtener el formato de salida (csd-0) antes de iniciar el vídeo; esas muestras
 * de relleno no se escriben en el MP4.
 */
class AacAudioEncoder(
    private val provider: PcmProvider,
    private val spec: AudioFormatSpec,
    private val output: EncoderOutput,
    private val onSilent: () -> Unit = {},
) {

    private val formatLatch = CountDownLatch(1)
    private val finishedLatch = CountDownLatch(1)
    private val stopping = AtomicBoolean(false)
    @Volatile
    private var forwarding = false
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    /** Bloquea hasta que el formato AAC está disponible; false si no se consiguió. */
    fun start(): Boolean {
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            spec.sampleRate,
            spec.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (spec.stereo) 96_000 else 64_000)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
        }
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        codec = mediaCodec
        thread = Thread({ pump(mediaCodec) }, "SmoothRecAudioEncoder").apply { start() }

        val ready = formatLatch.await(3, TimeUnit.SECONDS)
        if (!ready) {
            Log.w(TAG, "El encoder AAC no entregó su formato a tiempo")
            stop()
        }
        return ready
    }

    /** Empieza a enviar audio real; los PTS arrancan en cero en este momento. */
    fun resume() {
        forwarding = true
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) {
            // Otra llamada ya está esperando; no salimos hasta que el hilo haya
            // drenado y liberado el codec.
            try {
                finishedLatch.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        try {
            finishedLatch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pump(mediaCodec: MediaCodec) {
        val frameSamples = 1024
        val pcm = ShortArray(frameSamples * spec.channelCount)
        var ptsUs = 0L
        val info = MediaCodec.BufferInfo()
        val silenceFramesToDetect = spec.sampleRate / frameSamples * 2
        var silentFrames = 0
        var silenceReported = false

        fun deliverOutput(index: Int) {
            if (forwarding && info.size > 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                val buffer = mediaCodec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    output.onEncodedData(EncoderOutput.TRACK_AUDIO, buffer, info)
                }
            }
            mediaCodec.releaseOutputBuffer(index, false)
        }

        try {
            while (!stopping.get()) {
                val inputIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = mediaCodec.getInputBuffer(inputIndex)
                    if (input != null) {
                        // Rellenamos el frame completo: una lectura corta o vacía
                        // no debe cambiar el tamaño esperado por el codificador.
                        java.util.Arrays.fill(pcm, 0)
                        val read = if (forwarding) provider.read(pcm) else pcm.size
                        if (forwarding && !silenceReported) {
                            silentFrames = if (read > 0 && hasSignal(pcm, read)) {
                                0
                            } else {
                                silentFrames + 1
                            }
                            if (silentFrames >= silenceFramesToDetect) {
                                silenceReported = true
                                Log.w(TAG, "La fuente de audio seleccionada no entrega señal")
                                onSilent()
                            }
                        }
                        input.clear()
                        val shortBuffer = input.asShortBuffer()
                        val count = min(pcm.size, shortBuffer.remaining())
                        if (count > 0) {
                            shortBuffer.put(pcm, 0, count)
                            mediaCodec.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
                        }
                        if (forwarding) {
                            ptsUs += frameSamples * 1_000_000L / spec.sampleRate
                        }
                    }
                }

                when (val outputIndex = mediaCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        output.onOutputFormatChanged(
                            EncoderOutput.TRACK_AUDIO,
                            mediaCodec.outputFormat,
                        )
                        formatLatch.countDown()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> deliverOutput(outputIndex)
                }
            }

            // Fin de stream: se drenan también las muestras finales al muxer.
            var eosQueued = false
            while (!eosQueued) {
                val index = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index >= 0) {
                    mediaCodec.queueInputBuffer(
                        index,
                        0,
                        0,
                        ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    eosQueued = true
                } else {
                    break
                }
            }
            var eosReceived = false
            var guard = 0
            while (!eosReceived && guard++ < 200) {
                val index = mediaCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (index >= 0) {
                    eosReceived = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    deliverOutput(index)
                }
            }
            if (!eosReceived) Log.w(TAG, "No se recibió el EOS del codificador AAC")
        } catch (e: Exception) {
            Log.e(TAG, "Error en el hilo del encoder AAC", e)
        } finally {
            try {
                mediaCodec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "El codec AAC ya estaba detenido", e)
            }
            try {
                mediaCodec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error al liberar el codec AAC", e)
            }
            codec = null
            finishedLatch.countDown()
        }
    }

    private fun hasSignal(buffer: ShortArray, count: Int): Boolean {
        for (i in 0 until count) {
            if (buffer[i].toInt() != 0) return true
        }
        return false
    }

    private companion object {
        const val TAG = "AacAudioEncoder"
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 20_000L
    }
}
