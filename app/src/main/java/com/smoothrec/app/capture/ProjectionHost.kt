package com.smoothrec.app.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.smoothrec.app.audio.CaptureDiagnostics
import kotlin.math.min

/** Dueño del VirtualDisplay y del callback durante toda la sesión de MediaProjection. */
class ProjectionHost(
    private val context: Context,
    private val projection: MediaProjection,
    private val onProjectionStopped: () -> Unit,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var released = false

    // Tamaño fijo del encoder de la grabación actual.
    private var encoderW = 0
    private var encoderH = 0

    // Último tamaño informado por Android para el contenido compartido.
    private var contentW = 0
    private var contentH = 0

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Proyección detenida por el sistema o el usuario")
            releaseDisplay()
            onProjectionStopped()
        }

        // Android 14+: cambia el tamaño de la app compartida (rotación, ventana).
        override fun onCapturedContentResize(width: Int, height: Int) {
            synchronized(this@ProjectionHost) {
                if (released || width <= 0 || height <= 0) return
                contentW = width
                contentH = height
                CaptureDiagnostics.log("Contenido compartido: ${width}x$height")
                applyFittedSize()
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            CaptureDiagnostics.log("Contenido compartido visible=$isVisible")
        }
    }

    init {
        // En Android 14+ el callback debe registrarse antes de crear el VirtualDisplay.
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** Ajusta el display a la proporción del contenido, sin superar el tamaño del encoder. */
    private fun applyFittedSize() {
        val vd = virtualDisplay ?: return
        if (encoderW <= 0 || encoderH <= 0) return
        val (w, h) = fittedSize()
        try {
            vd.resize(w, h, context.resources.displayMetrics.densityDpi)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo redimensionar el VirtualDisplay", e)
        }
    }

    private fun fittedSize(): Pair<Int, Int> {
        if (contentW <= 0 || contentH <= 0) return encoderW to encoderH
        val scale = min(encoderW.toFloat() / contentW, encoderH.toFloat() / contentH)
        val w = (contentW * scale).toInt().coerceAtLeast(2) and 1.inv()
        val h = (contentH * scale).toInt().coerceAtLeast(2) and 1.inv()
        return w to h
    }

    /** Conecta la Surface de una grabación. Crea el VirtualDisplay solo la primera vez. */
    @Synchronized
    fun attach(width: Int, height: Int, surface: Surface) {
        check(!released) { "ProjectionHost liberado" }
        encoderW = width
        encoderH = height
        val dpi = context.resources.displayMetrics.densityDpi
        val (w, h) = fittedSize()
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "SmoothRecCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null,
            )
        } else {
            vd.setSurface(null)
            vd.resize(w, h, dpi)
            vd.setSurface(surface)
        }
    }

    @Synchronized
    fun setSurface(surface: Surface?) {
        try {
            virtualDisplay?.setSurface(surface)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo actualizar la Surface", e)
        }
    }

    /** Fin de una grabación: suelta la Surface pero conserva el VirtualDisplay. */
    fun detach() = setSurface(null)

    @Synchronized
    private fun releaseDisplay() {
        try {
            virtualDisplay?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Error al liberar el VirtualDisplay", e)
        }
        virtualDisplay = null
    }

    /** Fin de la sesión completa. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        releaseDisplay()
        try {
            projection.unregisterCallback(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo desregistrar el callback", e)
        }
    }

    private companion object { const val TAG = "ProjectionHost" }
}
