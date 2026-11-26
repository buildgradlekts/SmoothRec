package com.smoothrec.app.capture

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.smoothrec.app.audio.CaptureDiagnostics

/**
 * Monitor térmico ligero. Solo informa: no cambia resolución, FPS ni bitrate.
 * Disponible en API 29+; en versiones anteriores el estado es "no disponible".
 */
@TargetApi(Build.VERSION_CODES.Q)
class ThermalMonitor(private val context: Context) {

    @Volatile
    var status: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    private var registered = false

    private val listener = object : PowerManager.OnThermalStatusChangedListener {
        override fun onThermalStatusChanged(newStatus: Int) {
            val previous = status
            status = newStatus
            if (newStatus != previous && newStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                CaptureDiagnostics.log("Temperatura: ${label(newStatus)} ($newStatus)")
            }
        }
    }

    fun start() {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val manager = context.getSystemService(PowerManager::class.java) ?: return
            status = manager.currentThermalStatus
            manager.addThermalStatusListener(listener)
            registered = true
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                CaptureDiagnostics.log("Temperatura al iniciar: ${label(status)} ($status)")
            }
        } catch (e: Exception) {
            CaptureDiagnostics.log("Monitor térmico no disponible: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            context.getSystemService(PowerManager::class.java)
                ?.removeThermalStatusListener(listener)
        } catch (e: Exception) {
            CaptureDiagnostics.log("No se pudo retirar el monitor térmico: ${e.javaClass.simpleName}")
        }
    }

    fun label(): String = label(status)

    private fun label(value: Int): String = when (value) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "leve"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderada"
        PowerManager.THERMAL_STATUS_SEVERE -> "alta"
        PowerManager.THERMAL_STATUS_CRITICAL -> "crítica"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "apagón inminente"
        6 -> "emergencia" // THERMAL_STATUS_EMERGENCY (API 33)
        else -> "desconocida"
    }

    companion object {
        const val STATUS_UNAVAILABLE = -1
    }
}
