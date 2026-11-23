package com.smoothrec.app.capture

import android.content.Context
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.encoder.CaptureSizeResolver
import com.smoothrec.app.encoder.EncoderFactory
import com.smoothrec.app.encoder.VideoEncoder
import com.smoothrec.app.muxer.Mp4Muxer
import com.smoothrec.app.recording.RecordingPreferences

/**
 * Orquesta la sesión completa de grabación:
 * MediaProjection -> VirtualDisplay -> MediaCodec (H.264) -> MediaMuxer -> MediaStore.
 */
class CaptureSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val onProjectionStopped: () -> Unit,
) {
    private val store = RecordingStore(context)

    private var pending: RecordingStore.PendingRecording? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var muxer: Mp4Muxer? = null
    private var encoder: VideoEncoder? = null
    private var capturer: ScreenCapturer? = null
    private var released = false

    /** Configuración efectivamente aplicada, disponible para la notificación y la UI. */
    var appliedResolution: CaptureSizeResolver.ResolutionResult? = null
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

    fun start() {
        // Tamaño, FPS y orientación se fijan al iniciar: el MP4 no admite cambiar de pista a mitad.
        val settings = RecordingPreferences.load(context)
        val resolution = CaptureSizeResolver.forContext(context, settings)
        val config = resolution.config
        appliedResolution = resolution
        try {
            pending = store.prepare()
            descriptor = store.openForWrite(pending!!)
            muxer = Mp4Muxer(descriptor!!).also { it.start() }

            val newEncoder = EncoderFactory.create(config)
            try {
                newEncoder.start(config, muxer!!)
            } catch (e: Exception) {
                newEncoder.release()
                throw e
            }
            encoder = newEncoder

            capturer = ScreenCapturer(
                context,
                projection,
                newEncoder.inputSurface(),
                config.width,
                config.height,
            ).also { it.start() }

            Log.i(
                TAG,
                "Grabación iniciada: ${config.width}x${config.height}@${config.frameRate} " +
                    "(elegido ${resolution.requested.width}x${resolution.requested.height}@${resolution.requested.frameRate})",
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la grabación", e)
            release()
            throw e
        }
    }

    fun release() {
        if (released) return
        released = true

        capturer?.release()
        capturer = null

        encoder?.let {
            try {
                it.stop()
            } finally {
                it.release()
            }
        }
        encoder = null

        val currentMuxer = muxer
        val hasData = currentMuxer?.hasData() == true
        currentMuxer?.stop()
        muxer = null

        try {
            descriptor?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error al cerrar el descriptor", e)
        }
        descriptor = null

        pending?.let {
            if (hasData) store.publish(it) else store.discard(it)
        }
        pending = null

        try {
            projection.unregisterCallback(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo desregistrar el callback", e)
        }
        try {
            projection.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "La proyección ya estaba detenida", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudo detener la proyección", e)
        }
    }

    private companion object {
        const val TAG = "CaptureSession"
    }
}
