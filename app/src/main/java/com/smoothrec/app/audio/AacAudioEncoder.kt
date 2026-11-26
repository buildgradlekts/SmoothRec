package com.smoothrec.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import com.smoothrec.app.encoder.EncoderOutput
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.min

/**
 * Codificador AAC en hilo de prioridad de audio urgente.
 *
 * Sincronización A/V:
 * - Usa CLOCK_MONOTONIC común con el vídeo (con soporte para pausa/reanudación).
 * - Ancla tras la primera lectura real (cuando AudioRecord.getTimestamp() ya está
 *   disponible) y compensa la latencia basal del búfer HAL.
 * - Solo contabiliza las muestras realmente leídas y corrige desbordamientos bajo
 *   carga de videojuegos sin reiniciar el códec.
 */
class AacAudioEncoder(
    private val provider: PcmProvider,
    private val spec: AudioFormatSpec,
    private val output: EncoderOutput,
    private val onSilent: () -> Unit = {},
) {

    enum class AudioHealthState { READY, ACTIVE, PAUSED, SILENT, ERROR }

    private val formatLatch = CountDownLatch(1)
    private val finishedLatch = CountDownLatch(1)
    private val stopping = AtomicBoolean(false)
    private val formatReady = AtomicBoolean(false)

    @Volatile private var forwarding = false
    @Volatile private var paused = false
    @Volatile private var pausedOffsetNs = 0L
    @Volatile private var reanchorNeeded = false
    @Volatile private var healthState = AudioHealthState.READY
    @Volatile private var abandoned = false
    @Volatile private var eosConfirmed = false

    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            spec.sampleRate,
            spec.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (spec.stereo) 96_000 else 64_000)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, spec.channelCount)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, spec.sampleRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
        }
        try {
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el codec AAC", e)
            CaptureDiagnostics.log("Encoder AAC: fallo al iniciar ${e.javaClass.simpleName}")
            try { mediaCodec.release() } catch (_: Exception) {}
            healthState = AudioHealthState.ERROR
            return false
        }
        codec = mediaCodec
        thread = Thread({ pump(mediaCodec) }, "SmoothRecAudioEncoder").apply { start() }

        val ready = formatLatch.await(3, TimeUnit.SECONDS)
        if (!ready) {
            Log.w(TAG, "El encoder AAC no entregó su formato a tiempo")
            healthState = AudioHealthState.ERROR
            stop()
        }
        return ready
    }

    fun resume() {
        forwarding = true
        healthState = AudioHealthState.ACTIVE
    }

    fun pause() {
        paused = true
        healthState = AudioHealthState.PAUSED
    }

    fun resumeAfterPause(totalPausedNs: Long) {
        pausedOffsetNs = totalPausedNs.coerceAtLeast(0L)
        reanchorNeeded = true
        paused = false
        healthState = AudioHealthState.ACTIVE
    }

    fun state(): AudioHealthState = healthState

    /** Nunca escribe en el muxer si el cierre ya se dio por fallido. */
    private fun deliver(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (abandoned) return
        output.onEncodedData(EncoderOutput.TRACK_AUDIO, buffer, info)
    }

    /** @return true si el hilo terminó a tiempo y ya no escribirá en el muxer. */
    fun stop(): Boolean {
        stopping.set(true)
        val t = thread ?: return false
        val finished = try {
            finishedLatch.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            abandoned = true
            healthState = AudioHealthState.ERROR
            t.interrupt()
            Log.e(TAG, "El hilo AAC no terminó en ${STOP_TIMEOUT_MS}ms")
            CaptureDiagnostics.log("Encoder AAC: no terminó en ${STOP_TIMEOUT_MS}ms; se descarta el MP4")
        } else if (!eosConfirmed) {
            CaptureDiagnostics.log("Encoder AAC: terminó sin confirmar EOS")
        }
        return finished
    }

    private fun pump(mediaCodec: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val frameSamples = 1024
        val pcm = ShortArray(frameSamples * spec.channelCount)
        val info = MediaCodec.BufferInfo()

        var rateHz = spec.sampleRate.coerceAtLeast(1)
        var framesQueued = 0L
        var anchorFrame = 0L
        var anchorNs = 0L
        var baselineHalOffsetNs = 0L
        var anchored = false
        var lastQueuedPtsUs = 0L
        var minForwardedPtsUs = -1L
        var firstInputPtsUs = -1L
        var lastInputPtsUs = -1L
        var firstOutputPtsUs = -1L
        var lastOutputPtsUs = -1L
        var overrunFixes = 0
        var silenceFramesToDetect = (rateHz / frameSamples) * 2
        var silentFrames = 0
        var silenceReported = false

        fun drainPendingOutputs(maxLoops: Int = 8) {
            var loops = 0
            while (loops++ < maxLoops) {
                val outIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        output.onOutputFormatChanged(EncoderOutput.TRACK_AUDIO, mediaCodec.outputFormat)
                        formatReady.set(true)
                        formatLatch.countDown()
                    }
                    outIndex >= 0 -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val minPts = minForwardedPtsUs
                        val validForward = forwarding && !paused && !isConfig && info.size > 0 &&
                            minPts >= 0L && info.presentationTimeUs >= minPts
                        if (validForward) {
                            if (firstOutputPtsUs < 0) firstOutputPtsUs = info.presentationTimeUs
                            if (info.presentationTimeUs <= lastOutputPtsUs) {
                                info.presentationTimeUs = lastOutputPtsUs + 1L
                            }
                            lastOutputPtsUs = info.presentationTimeUs
                            val buffer = mediaCodec.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                deliver(buffer, info)
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outIndex, false)
                    }
                    else -> break
                }
            }
        }

        try {
            while (!stopping.get()) {
                // 1. Arranque: emitir silencio solo hasta obtener csd-0 y drenar salidas.
                if (!forwarding && formatReady.get()) {
                    drainPendingOutputs()
                    LockSupport.parkNanos(2_000_000L)
                    continue
                }

                // 2. Pausa: seguir drenando AudioRecord para no perder permisos ni
                // llenar el ring buffer de AudioPlaybackCapture, sin codificar.
                if (forwarding && paused) {
                    provider.read(pcm)
                    drainPendingOutputs()
                    continue
                }

                // 3. Captura PCM real antes de obtener el input buffer, para anclar
                // el reloj exactamente al instante real de captura.
                val read: Int
                val readDoneNs: Long
                if (forwarding) {
                    read = provider.read(pcm)
                    readDoneNs = System.nanoTime()
                    if (read <= 0) {
                        drainPendingOutputs()
                        LockSupport.parkNanos(2_000_000L)
                        continue
                    }
                } else {
                    java.util.Arrays.fill(pcm, 0)
                    read = pcm.size
                    readDoneNs = 0L
                }

                val inputIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = mediaCodec.getInputBuffer(inputIndex)
                    if (input != null) {
                        val readFrames = (read / spec.channelCount).coerceAtLeast(1)
                        val ptsUs: Long
                        if (forwarding) {
                            val currentPausedOffsetNs = pausedOffsetNs
                            val frameDurationNs = (readFrames.toLong() * 1_000_000_000L) / rateHz
                            val wallStartNs = (readDoneNs - frameDurationNs) - currentPausedOffsetNs

                            if (!anchored) {
                                val clock = provider.clock()
                                if (clock != null) {
                                    rateHz = clock.sampleRate.coerceAtLeast(1)
                                    silenceFramesToDetect = (rateHz / frameSamples) * 2
                                    val hwStartNs = (clock.nanoTime -
                                        (clock.framePosition * 1_000_000_000L) / rateHz) -
                                        currentPausedOffsetNs
                                    anchorNs = hwStartNs
                                    anchorFrame = framesQueued
                                    baselineHalOffsetNs = (wallStartNs - hwStartNs)
                                        .coerceIn(0L, 120_000_000L)
                                    CaptureDiagnostics.log(
                                        "Audio anclado a getTimestamp: frame=${clock.framePosition} " +
                                            "rate=$rateHz halOffset=${baselineHalOffsetNs / 1_000_000}ms",
                                    )
                                } else {
                                    anchorNs = wallStartNs
                                    anchorFrame = framesQueued
                                    baselineHalOffsetNs = 0L
                                    CaptureDiagnostics.log("Audio anclado a nanoTime (sin getTimestamp)")
                                }
                                anchored = true
                            } else if (reanchorNeeded) {
                                reanchorNeeded = false
                                val minSafeStartNs = (lastQueuedPtsUs + 1_000L) * 1_000L
                                anchorNs = maxOf(wallStartNs - baselineHalOffsetNs, minSafeStartNs)
                                anchorFrame = framesQueued
                            }

                            var frameStartNs = anchorNs +
                                ((framesQueued - anchorFrame) * 1_000_000_000L) / rateHz

                            // Compensación de desbordamientos bajo carga de juego o deriva de reloj.
                            val expectedWallStartNs = frameStartNs + baselineHalOffsetNs
                            val driftNs = wallStartNs - expectedWallStartNs
                            if (driftNs > OVERRUN_THRESHOLD_NS) {
                                overrunFixes++
                                val recoveredStartNs = wallStartNs - baselineHalOffsetNs
                                anchorNs += recoveredStartNs - frameStartNs
                                frameStartNs = recoveredStartNs
                                Log.w(TAG, "Overrun de audio corregido (+${driftNs / 1_000_000} ms)")
                                CaptureDiagnostics.log(
                                    "Audio overrun #$overrunFixes corregido (+${driftNs / 1_000_000} ms)",
                                )
                            } else if (driftNs < -OVERRUN_THRESHOLD_NS) {
                                val minSafe = (lastQueuedPtsUs + 100L) * 1_000L
                                val corrected = maxOf(wallStartNs - baselineHalOffsetNs, minSafe)
                                anchorNs += corrected - frameStartNs
                                frameStartNs = corrected
                            } else if (abs(driftNs) > GENTLE_DRIFT_THRESHOLD_NS) {
                                val nudge = driftNs / 64L
                                anchorNs += nudge
                                frameStartNs += nudge
                            }

                            var candidatePtsUs = (frameStartNs / 1_000L).coerceAtLeast(0L)
                            if (minForwardedPtsUs < 0L) {
                                minForwardedPtsUs = candidatePtsUs
                            } else if (candidatePtsUs <= lastQueuedPtsUs) {
                                candidatePtsUs = lastQueuedPtsUs + 1L
                            }
                            ptsUs = candidatePtsUs
                        } else {
                            ptsUs = 0L
                        }

                        if (forwarding && !silenceReported) {
                            silentFrames = if (hasSignal(pcm, read)) 0 else silentFrames + 1
                            if (silentFrames >= silenceFramesToDetect) {
                                silenceReported = true
                                healthState = AudioHealthState.SILENT
                                Log.w(TAG, "La fuente de audio seleccionada no entrega señal")
                                CaptureDiagnostics.log(
                                    "Encoder: $silentFrames frames sin señal (PTS=${"%.2f".format(ptsUs / 1_000_000.0)}s)",
                                )
                                onSilent()
                            }
                        }

                        input.clear()
                        val shortBuffer = input.asShortBuffer()
                        val count = min(read, shortBuffer.remaining())
                        if (count > 0) {
                            shortBuffer.put(pcm, 0, count)
                            mediaCodec.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
                            if (forwarding) {
                                if (firstInputPtsUs < 0) firstInputPtsUs = ptsUs
                                lastInputPtsUs = ptsUs
                                lastQueuedPtsUs = ptsUs
                                framesQueued += (count / spec.channelCount).toLong()
                            }
                        }
                    }
                }

                drainPendingOutputs()
            }

            // Fin de stream: reintenta hasta conseguir un buffer de entrada para el EOS.
            var eosQueued = false
            var attempts = 0
            while (!eosQueued && attempts++ < 10) {
                val eosIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (eosIndex >= 0) {
                    mediaCodec.queueInputBuffer(
                        eosIndex, 0, 0, lastQueuedPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    eosQueued = true
                } else {
                    drainPendingOutputs()
                }
            }
            var eosReceived = false
            var guard = 0
            while (eosQueued && !eosReceived && !abandoned && guard++ < 150) {
                val index = mediaCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (index >= 0) {
                    eosReceived = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val minPts = minForwardedPtsUs
                    if (forwarding && !isConfig && info.size > 0 && minPts >= 0L &&
                        info.presentationTimeUs >= minPts
                    ) {
                        if (info.presentationTimeUs <= lastOutputPtsUs) {
                            info.presentationTimeUs = lastOutputPtsUs + 1L
                        }
                        lastOutputPtsUs = info.presentationTimeUs
                        val buffer = mediaCodec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            deliver(buffer, info)
                        }
                    }
                    mediaCodec.releaseOutputBuffer(index, false)
                }
            }
            eosConfirmed = eosReceived
            val summary = "AAC resumen: rate=$rateHz frames=$framesQueued overruns=$overrunFixes " +
                "PTS entrada $firstInputPtsUs..$lastInputPtsUs us, " +
                "PTS salida $firstOutputPtsUs..$lastOutputPtsUs us"
            Log.i(TAG, summary)
            CaptureDiagnostics.log(summary)
        } catch (e: Exception) {
            healthState = AudioHealthState.ERROR
            Log.e(TAG, "Error en el hilo del encoder AAC", e)
            CaptureDiagnostics.log("Encoder AAC: error ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                mediaCodec.stop()
            } catch (_: IllegalStateException) {
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
            if (abs(buffer[i].toInt()) >= SIGNAL_THRESHOLD) return true
        }
        return false
    }

    private companion object {
        const val TAG = "AacAudioEncoder"
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 20_000L
        const val OVERRUN_THRESHOLD_NS = 65_000_000L
        const val GENTLE_DRIFT_THRESHOLD_NS = 20_000_000L
        const val SIGNAL_THRESHOLD = 8
        const val STOP_TIMEOUT_MS = 6_000L
    }
}
