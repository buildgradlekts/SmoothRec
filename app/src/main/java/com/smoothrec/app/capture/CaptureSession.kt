package com.smoothrec.app.capture

import android.content.Context
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.audio.AacAudioEncoder
import com.smoothrec.app.audio.AudioCaptureFactory
import com.smoothrec.app.audio.AudioPlan
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.encoder.CaptureSizeResolver
import com.smoothrec.app.encoder.EncoderOutput
import com.smoothrec.app.encoder.EncoderFactory
import com.smoothrec.app.encoder.GlWatermarkRenderer
import com.smoothrec.app.encoder.VideoEncoder
import com.smoothrec.app.muxer.Mp4Muxer
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.recording.RecordingSettings

/**
 * Orquesta la sesión completa de grabación:
 * MediaProjection -> VirtualDisplay -> [GlWatermarkRenderer opcional] -> MediaCodec -> MediaMuxer -> MediaStore.
 */
class CaptureSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val onProjectionStopped: () -> Unit,
    private val onNotice: (Int) -> Unit = {},
) {
    private val store = RecordingStore(context)

    private var pending: RecordingStore.PendingRecording? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var muxer: Mp4Muxer? = null
    private var encoder: VideoEncoder? = null
    private var watermarkRenderer: GlWatermarkRenderer? = null
    private var capturer: ScreenCapturer? = null
    private var audioPlan: AudioPlan? = null
    private var audioEncoder: AacAudioEncoder? = null
    private var settingsUsed: RecordingSettings = RecordingPreferences.load(context)
    private var released = false

    @Volatile private var paused = false
    private var pauseStartedNs = 0L
    private var totalPausedNs = 0L

    var appliedResolution: CaptureSizeResolver.ResolutionResult? = null
        private set

    var savedOk: Boolean = false
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Consentimiento retirado por el usuario")
            onProjectionStopped()
        }
    }

    init {
        projection.registerCallback(callback, null)
    }

    @Synchronized
    fun start() {
        if (released) return
        val settings = RecordingPreferences.load(context)
        settingsUsed = settings
        val resolution = CaptureSizeResolver.forContext(context, settings)
        val config = resolution.config
        appliedResolution = resolution
        try {
            pending = store.prepare()
            descriptor = store.openForWrite(pending!!)
            val sessionStartNs = System.nanoTime()
            val newMuxer = Mp4Muxer(descriptor!!)
            muxer = newMuxer

            val audioSetup = AudioCaptureFactory.create(
                context,
                projection,
                settings.audioSource,
                settings.channels,
            ) { onNotice(R.string.audio_internal_fallback) }
            audioSetup.noticeRes?.let(onNotice)
            val plan = audioSetup.plan
            newMuxer.prepare(if (plan == null) 1 else 2, sessionStartNs)

            if (plan != null) {
                val silentNotice = if (plan.option == AudioSourceOption.INTERNAL) {
                    R.string.audio_internal_silent
                } else {
                    R.string.audio_fallback_silent
                }
                val newAudioEncoder = AacAudioEncoder(plan.provider, plan.spec, newMuxer) {
                    onNotice(silentNotice)
                }
                if (newAudioEncoder.start()) {
                    audioEncoder = newAudioEncoder
                    audioPlan = plan
                    CaptureDiagnostics.log(
                        "Sesión: codificador AAC listo (${plan.spec.sampleRate} Hz, " +
                            "${plan.spec.channelCount} canal/es)",
                    )
                } else {
                    CaptureDiagnostics.log("Sesión: codificador AAC NO se pudo iniciar")
                    onNotice(R.string.audio_fallback_encoder)
                    newAudioEncoder.stop()
                    plan.release()
                    if (!newMuxer.hasTrack(EncoderOutput.TRACK_AUDIO)) {
                        newMuxer.prepare(1, sessionStartNs)
                    }
                }
            }
            if (plan == null || audioEncoder == null) {
                newMuxer.startWithAvailableTracks()
            }

            val newEncoder = EncoderFactory.create(config)
            try {
                newEncoder.start(config, muxer!!)
            } catch (e: Exception) {
                newEncoder.release()
                throw e
            }
            encoder = newEncoder

            // Solo instanciamos el pipeline GL si la marca de agua está activada.
            val captureSurface = if (settings.watermarkEnabled) {
                val renderer = GlWatermarkRenderer(
                    newEncoder.inputSurface(),
                    config.width,
                    config.height,
                )
                try {
                    val surface = renderer.start()
                    watermarkRenderer = renderer
                    CaptureDiagnostics.log("Sesión: marca de agua 'SmoothRec' activada en el vídeo")
                    surface
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback sin marca de agua GL por error al iniciar", e)
                    renderer.release()
                    newEncoder.inputSurface()
                }
            } else {
                newEncoder.inputSurface()
            }

            capturer = ScreenCapturer(
                context,
                projection,
                captureSurface,
                config.width,
                config.height,
            ).also { it.start() }

            if (plan != null && audioEncoder != null) {
                val sourceStart = plan.startSources()
                sourceStart.noticeRes?.let(onNotice)
                if (!sourceStart.success) {
                    Log.e(TAG, "No hay fuentes de audio: la grabación seguirá sin pista de audio")
                    audioEncoder?.stop()
                    audioEncoder = null
                    plan.release()
                    audioPlan = null
                    newMuxer.startWithAvailableTracks()
                } else {
                    audioEncoder?.resume()
                    CaptureDiagnostics.logAudioConfigurations(context)
                }
            }

            Log.i(
                TAG,
                "Grabación iniciada: ${config.width}x${config.height}@${config.frameRate} " +
                    "${config.bitRate / 1_000_000}Mbps watermark=${settings.watermarkEnabled}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la grabación", e)
            release()
            throw e
        }
    }

    @Synchronized
    fun pause() {
        if (released || paused) return
        paused = true
        pauseStartedNs = System.nanoTime()
        encoder?.setPaused(true)
        audioEncoder?.pause()
        val renderer = watermarkRenderer
        if (renderer != null) {
            renderer.setPaused(true)
        } else {
            capturer?.setSurface(null)
        }
        CaptureDiagnostics.log("Sesión: grabación EN PAUSA")
    }

    @Synchronized
    fun resume() {
        if (released || !paused) return
        val nowNs = System.nanoTime()
        if (pauseStartedNs > 0L && nowNs > pauseStartedNs) {
            totalPausedNs += (nowNs - pauseStartedNs)
        }
        pauseStartedNs = 0L
        paused = false

        val enc = encoder
        enc?.setPausedOffsetUs(totalPausedNs / 1_000L)
        enc?.setPaused(false)
        enc?.requestSyncFrame()

        val renderer = watermarkRenderer
        if (renderer != null) {
            renderer.setPaused(false)
        } else if (enc != null) {
            capturer?.setSurface(enc.inputSurface())
        }

        audioEncoder?.resumeAfterPause(totalPausedNs)
        CaptureDiagnostics.log("Sesión: grabación REANUDADA (pausa acumulada=${totalPausedNs / 1_000_000}ms)")
    }

    fun isPaused(): Boolean = paused

    /** FPS efectivos medidos por el encoder a partir de los PTS de vídeo. */
    fun effectiveVideoFps(): Int = encoder?.effectiveFps() ?: 0

    fun snapshotTelemetry(elapsedActiveMs: Long): RecordingTelemetry {
        val cfg = appliedResolution?.config
        val enc = encoder
        val aud = audioEncoder
        val captureStats = watermarkRenderer?.stats()
        val encoderStats = enc?.stats()
        val muxerStats = muxer?.stats()
        val audState = aud?.state()
        val encError = enc?.lastError()
        val encState = when {
            encError != null -> RecordingTelemetry.STATE_ERROR
            paused -> RecordingTelemetry.STATE_PAUSED
            enc?.isRunning() == true -> RecordingTelemetry.STATE_RUNNING
            else -> RecordingTelemetry.STATE_READY
        }
        return RecordingTelemetry(
            width = cfg?.width ?: 0,
            height = cfg?.height ?: 0,
            configuredFps = cfg?.frameRate ?: settingsUsed.fps.value,
            effectiveFps = enc?.effectiveFps() ?: 0,
            bitrateBps = cfg?.bitRate ?: 0,
            audioSource = settingsUsed.audioSource,
            audioActive = aud != null && audState != AacAudioEncoder.AudioHealthState.ERROR,
            audioSilent = audState == AacAudioEncoder.AudioHealthState.SILENT,
            encoderState = encState,
            paused = paused,
            elapsedActiveMs = elapsedActiveMs,
            lastError = encError,
            captureFps = captureStats?.captureFps ?: 0,
            droppedFrames = captureStats?.staleFramesDropped ?: 0L,
            captureBacklogMax = captureStats?.maxPendingFrames ?: 0,
            frameAgeMaxMs = captureStats?.maxFrameAgeMs ?: 0,
            ptsGapFrames = encoderStats?.ptsGapFrames ?: 0L,
            maxPtsGapMs = encoderStats?.maxPtsGapMs ?: 0,
            slowWrites = muxerStats?.slowWrites ?: 0,
            maxWriteMs = muxerStats?.maxWriteMs ?: 0,
        )
    }

    /** Resumen final con métricas reales, escrito en el diagnóstico de la sesión. */
    private fun logPerformanceSummary() {
        val captureStats = watermarkRenderer?.stats()
        val encoderStats = encoder?.stats()
        val muxerStats = muxer?.stats()
        if (captureStats == null && encoderStats == null && muxerStats == null) return
        CaptureDiagnostics.log(
            "Rendimiento: captura=${captureStats?.captureFps ?: 0}fps " +
                "recibidos=${captureStats?.receivedFrames ?: 0L} " +
                "obsoletos=${captureStats?.staleFramesDropped ?: 0L} " +
                "colaMax=${captureStats?.maxPendingFrames ?: 0} " +
                "edadMax=${captureStats?.maxFrameAgeMs ?: 0}ms | " +
                "codificados=${encoderStats?.encodedFrames ?: 0L} " +
                "huecosPTS=${encoderStats?.ptsGapFrames ?: 0L} " +
                "huecoMax=${encoderStats?.maxPtsGapMs ?: 0}ms " +
                "entregasLentas=${encoderStats?.slowDeliveries ?: 0} " +
                "entregaMax=${encoderStats?.maxDeliveryMs ?: 0}ms | " +
                "escrituras=${muxerStats?.samplesWritten ?: 0L} " +
                "escriturasLentas=${muxerStats?.slowWrites ?: 0} " +
                "escrituraMax=${muxerStats?.maxWriteMs ?: 0}ms",
        )
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        paused = false
        logPerformanceSummary()

        capturer?.release()
        capturer = null

        watermarkRenderer?.release()
        watermarkRenderer = null

        encoder?.signalEndOfStream()
        audioEncoder?.stop()
        audioEncoder = null
        audioPlan?.release()
        audioPlan = null

        encoder?.let {
            try {
                it.stop()
            } finally {
                it.release()
            }
        }
        encoder = null

        val currentMuxer = muxer
        currentMuxer?.stop()
        val publish = currentMuxer?.hasData() == true && currentMuxer.finalizedOk()
        savedOk = publish
        if (!publish && currentMuxer?.hasData() == true) {
            Log.w(TAG, "MP4 descartado: MediaMuxer no cerró correctamente")
        }
        muxer = null

        try {
            descriptor?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error al cerrar el descriptor", e)
        }
        descriptor = null

        pending?.let {
            if (publish) store.publish(it) else store.discard(it)
        }
        pending = null

        CaptureDiagnostics.write(context)

        try {
            projection.unregisterCallback(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo desregistrar el callback", e)
        }
    }

    private companion object {
        const val TAG = "CaptureSession"
    }
}
