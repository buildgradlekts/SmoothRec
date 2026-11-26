package com.smoothrec.app.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Encoder H.264 asíncrono con entrada Surface, optimizado para estabilidad en
 * videojuegos, pausa/reanudación sin reiniciar el códec y medición ligera de FPS.
 */
class HardwareVideoEncoder(private val codecName: String) : VideoEncoder {

    private val thread = HandlerThread("SmoothRecEncoder", Process.THREAD_PRIORITY_DISPLAY).apply {
        start()
    }
    private val handler = Handler(thread.looper)
    private val eosLatch = CountDownLatch(1)
    private val released = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    @Volatile private var paused = false
    @Volatile private var pausedOffsetUs = 0L
    @Volatile private var errorMessage: String? = null

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var output: EncoderOutput? = null
    private var buffersDelivered = 0
    private var firstRawPts = -1L
    private var lastRawPts = -1L

    private var frameRate = 60
    private var encodedFrames = 0L
    private var ptsGapFrames = 0L
    private var maxPtsGapMs = 0
    private var slowDeliveries = 0
    private var maxDeliveryNs = 0L
    private var previousEncodedPtsUs = -1L
    @Volatile private var statsSnapshot = EncoderStats()

    // FPS efectivos calculados con los PTS reales de los frames codificados.
    // Se mantiene una ventana corta para que el indicador responda rápido sin
    // asignaciones ni trabajo extra en el hilo de audio o en el muxer.
    private var fpsWindowStartPtsUs = -1L
    private var fpsWindowLastPtsUs = -1L
    private var fpsWindowFrames = 0
    @Volatile private var measuredFps = 0

    override fun start(config: EncoderConfig, output: EncoderOutput) {
        check(!released.get()) { "Encoder ya liberado" }
        this.output = output
        frameRate = config.frameRate.coerceAtLeast(1)

        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // La entrada es por Surface; no hay buffers de entrada.
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                val buffer: ByteBuffer? = try {
                    codec.getOutputBuffer(index)
                } catch (_: IllegalStateException) {
                    null
                }

                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buffer != null && info.size > 0 && !isConfig && !paused) {
                    buffersDelivered++
                    encodedFrames++
                    if (firstRawPts < 0) firstRawPts = info.presentationTimeUs
                    lastRawPts = info.presentationTimeUs

                    // Ajuste de continuidad tras pausa/reanudación.
                    val offset = pausedOffsetUs
                    if (offset > 0L) {
                        info.presentationTimeUs = (info.presentationTimeUs - offset).coerceAtLeast(0L)
                    }

                    updateEffectiveFps(info.presentationTimeUs)
                    recordPtsGap(info.presentationTimeUs)

                    // Medimos el tiempo real de entrega al muxer: si este hilo se
                    // bloquea, MediaCodec se queda sin buffers de salida y aparecen
                    // tirones. No cambia el flujo, solo cuantifica la saturación.
                    val deliveryStartNs = System.nanoTime()
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    try {
                        this@HardwareVideoEncoder.output?.onEncodedData(
                            EncoderOutput.TRACK_VIDEO,
                            buffer,
                            info,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al entregar paquete codificado", e)
                    } finally {
                        val deliveryNs = System.nanoTime() - deliveryStartNs
                        if (deliveryNs > maxDeliveryNs) maxDeliveryNs = deliveryNs
                        if (deliveryNs >= SLOW_DELIVERY_THRESHOLD_NS) slowDeliveries++
                    }
                }

                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Buffer ya liberado", e)
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    running.set(false)
                    eosLatch.countDown()
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                this@HardwareVideoEncoder.output?.onOutputFormatChanged(
                    EncoderOutput.TRACK_VIDEO,
                    format,
                )
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Error del MediaCodec", e)
                errorMessage = e.localizedMessage ?: e.javaClass.simpleName
                running.set(false)
                eosLatch.countDown()
            }
        }

        var mediaCodec: MediaCodec? = null
        var inputSurface: Surface? = null
        var started = false
        try {
            mediaCodec = MediaCodec.createByCodecName(codecName)
            configureWithFallback(mediaCodec, config, callback)
            inputSurface = mediaCodec.createInputSurface()
            mediaCodec.start()
            codec = mediaCodec
            surface = inputSurface
            running.set(true)
            started = true
        } finally {
            if (!started) {
                // Limpia los objetos locales que release() todavía no conoce.
                try { inputSurface?.release() } catch (_: Exception) {}
                try { mediaCodec?.release() } catch (_: Exception) {}
                this.output = null
            }
        }
    }

    /**
     * Intenta configurar el encoder con hints de baja latencia y perfil High,
     * degradando de forma controlada si el driver no acepta algún parámetro opcional.
     */
    private fun configureWithFallback(
        mediaCodec: MediaCodec,
        config: EncoderConfig,
        callback: MediaCodec.Callback,
    ) {
        val attempts = listOf(
            config.toMediaFormat(includeProfileLevel = true, includeRealtimeHints = true),
            config.toMediaFormat(includeProfileLevel = true, includeRealtimeHints = false),
            config.toMediaFormat(includeProfileLevel = false, includeRealtimeHints = false),
        )
        var lastError: Exception? = null
        for (format in attempts) {
            try {
                // El callback va antes de configure() y se repone tras cada reset().
                mediaCodec.setCallback(callback, handler)
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Configuración de encoder rechazada ($codecName): ${e.message}")
                lastError = e
                try {
                    mediaCodec.reset()
                } catch (_: Exception) {
                }
            }
        }
        throw lastError ?: IllegalStateException("No se pudo configurar $codecName")
    }

    /**
     * Calcula los FPS reales a partir de los presentationTimeUs de los buffers
     * de vídeo que el codec entrega, no del reloj de pared ni de la configuración.
     * Cada ~500 ms de tiempo de vídeo publica una medición nueva.
     */
    private fun updateEffectiveFps(ptsUs: Long) {
        if (fpsWindowStartPtsUs < 0L) {
            fpsWindowStartPtsUs = ptsUs
            fpsWindowLastPtsUs = ptsUs
            fpsWindowFrames = 1
            return
        }
        if (ptsUs <= fpsWindowLastPtsUs) return

        fpsWindowFrames++
        fpsWindowLastPtsUs = ptsUs
        val elapsedUs = ptsUs - fpsWindowStartPtsUs
        if (elapsedUs >= 500_000L) {
            measuredFps = ((fpsWindowFrames - 1) * 1_000_000.0 / elapsedUs).roundToInt()
            fpsWindowStartPtsUs = ptsUs
            fpsWindowLastPtsUs = ptsUs
            fpsWindowFrames = 1
            publishStats()
        }
    }

    /** Cuenta huecos reales entre PTS consecutivos; nunca genera PTS artificiales. */
    private fun recordPtsGap(ptsUs: Long) {
        val expectedUs = 1_000_000L / frameRate
        val previous = previousEncodedPtsUs
        if (previous >= 0L && ptsUs > previous) {
            val deltaUs = ptsUs - previous
            if (deltaUs > expectedUs * 3L / 2L) {
                val missing = (deltaUs / expectedUs).coerceAtLeast(1L) - 1L
                ptsGapFrames += missing.coerceAtLeast(1L)
                val gapMs = (deltaUs / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (gapMs > maxPtsGapMs) maxPtsGapMs = gapMs
            }
        }
        previousEncodedPtsUs = ptsUs
    }

    private fun publishStats() {
        statsSnapshot = EncoderStats(
            encodedFrames = encodedFrames,
            ptsGapFrames = ptsGapFrames,
            maxPtsGapMs = maxPtsGapMs,
            slowDeliveries = slowDeliveries,
            maxDeliveryMs = (maxDeliveryNs / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    override fun stats(): EncoderStats = statsSnapshot

    override fun inputSurface(): Surface =
        surface ?: throw IllegalStateException("El encoder todavía no está iniciado")

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            measuredFps = 0
            fpsWindowStartPtsUs = -1L
            fpsWindowLastPtsUs = -1L
            fpsWindowFrames = 0
            // Evita contar el salto de PTS de la reanudación como frame perdido.
            previousEncodedPtsUs = -1L
            publishStats()
        }
    }

    override fun setPausedOffsetUs(pausedOffsetUs: Long) {
        this.pausedOffsetUs = pausedOffsetUs.coerceAtLeast(0L)
        previousEncodedPtsUs = -1L
    }

    override fun requestSyncFrame() {
        val mediaCodec = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo solicitar keyframe al reanudar", e)
        }
    }

    override fun effectiveFps(): Int = measuredFps

    override fun isRunning(): Boolean = running.get() && !released.get()

    override fun lastError(): String? = errorMessage

    override fun signalEndOfStream() {
        val mediaCodec = codec ?: return
        paused = false
        try {
            mediaCodec.signalEndOfInputStream()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo señalar fin de stream", e)
            eosLatch.countDown()
        }
    }

    override fun stop() {
        if (codec == null) return
        try {
            eosLatch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        publishStats()
        Log.i(
            TAG,
            "Vídeo drenado: buffers=$buffersDelivered PTS crudos $firstRawPts..$lastRawPts us",
        )
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        publishStats()

        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "El codec ya estaba detenido", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar el codec", e)
        }
        try {
            surface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar la Surface", e)
        }

        codec = null
        surface = null
        output = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        try {
            thread.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "HardwareVideoEncoder"
        const val SLOW_DELIVERY_THRESHOLD_NS = 2_000_000L
    }
}
