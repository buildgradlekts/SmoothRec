package com.smoothrec.app.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/** Selecciona el encoder H.264 hardware disponible en el dispositivo. */
object EncoderFactory {

    fun create(config: EncoderConfig): VideoEncoder {
        val names = codecNames(config)
        if (names.isEmpty()) {
            throw IllegalStateException("No hay encoder H.264 hardware disponible")
        }

        var lastError: Exception? = null
        for (name in names) {
            try {
                Log.i(TAG, "Usando encoder: $name")
                return HardwareVideoEncoder(name)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo crear $name, probando siguiente", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No se pudo crear ningún encoder")
    }

    /** Nombres de codec hardware que se intentarán, en el mismo orden que en create(). */
    fun codecNames(config: EncoderConfig): List<String> {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val names = mutableListOf<String>()

        // Preferimos el C2 de Qualcomm detectado en el análisis del dispositivo.
        for (preferred in PREFERRED_CODECS) {
            if (list.codecInfos.any { it.name == preferred && it.isEncoder && !isSoftware(preferred) }) {
                names.add(preferred)
            }
        }

        if (names.isEmpty()) {
            val selected = list.findEncoderForFormat(config.toMediaFormat())
            if (selected != null && !isSoftware(selected)) {
                names.add(selected)
            }
        }
        return names
    }

    /** Información real del códec que se usará, para consultar sus VideoCapabilities. */
    fun primaryCodecInfo(config: EncoderConfig): MediaCodecInfo? {
        val name = codecNames(config).firstOrNull() ?: return null
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == name && it.isEncoder }
    }

    private val PREFERRED_CODECS = arrayOf("c2.qti.avc.encoder", "OMX.qcom.video.encoder.avc")

    private fun isSoftware(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("c2.android.") || n.startsWith("c2.google.") || n.startsWith("omx.google.")
    }

    private const val TAG = "EncoderFactory"
}
