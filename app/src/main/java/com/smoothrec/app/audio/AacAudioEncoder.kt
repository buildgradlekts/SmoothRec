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
) {

    private val formatLatch = CountDownLatch(1)
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
        if (!stopping.compareAndSet(false, true)) return
        try {
            thread?.join(2_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pump(mediaCodec: MediaCodec) {
        val frameSamples = 1024
        val pcm = ShortArray(frameSamples * spec.channelCount)
        var ptsUs = 0L
        val info = MediaCodec.BufferInfo()

        try {
            while (!stopping.get()) {
                val inputIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = mediaCodec.getInputBuffer(inputIndex)
                    if (input != null) {
                        val read: Int
                        if (forwarding) {
                            read = provider.read(pcm)
                        } else {
                            java.util.Arrays.fill(pcm, 0)
                            read = pcm.size
                        }
                        if (read > 0) {
                            input.clear()
                            val shortBuffer = input.asShortBuffer()
                            val count = min(read, shortBuffer.remaining())
                            shortBuffer.put(pcm, 0, count)
                            mediaCodec.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
                            if (forwarding) {
                                ptsUs += frameSamples * 1_000_000L / spec.sampleRate
                            }
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
                    else -> {
                        if (forwarding && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val buffer = mediaCodec.getOutputBuffer(outputIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                output.onEncodedData(EncoderOutput.TRACK_AUDIO, buffer, info)
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            // Fin de stream: una última entrada vacía y drenado de los buffers AAC.
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
                    mediaCodec.releaseOutputBuffer(index, false)
                }
            }
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
        }
    }

    private companion object {
        const val TAG = "AacAudioEncoder"
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 20_000L
    }
}
