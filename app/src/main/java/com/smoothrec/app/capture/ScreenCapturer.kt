package com.smoothrec.app.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface

/** Duplica la pantalla en la Surface de captura (directa o con marca de agua). */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        val density = context.resources.displayMetrics.densityDpi
        virtualDisplay = projection.createVirtualDisplay(
            "SmoothRecCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
    }

    fun setSurface(targetSurface: Surface?) {
        try {
            virtualDisplay?.setSurface(targetSurface)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo actualizar la Surface del VirtualDisplay", e)
        }
    }

    fun release() {
        try {
            virtualDisplay?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Error al liberar el VirtualDisplay", e)
        }
        virtualDisplay = null
    }

    private companion object {
        const val TAG = "ScreenCapturer"
    }
}
