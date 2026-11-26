package com.smoothrec.app.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.smoothrec.app.audio.CaptureDiagnostics

/** Selecciona el encoder H.264 hardware más adecuado disponible en el dispositivo. */
object EncoderFactory {

    /** Prueba cada encoder AVC (hardware primero) hasta que uno realmente arranque. */
    fun createAndStart(config: EncoderConfig, output: EncoderOutput): VideoEncoder {
        val names = codecNames(config)
        if (names.isEmpty()) {
            throw IllegalStateException("No hay encoder H.264 disponible en el dispositivo")
        }
        var lastError: Exception? = null
        for (name in names) {
            val candidate = HardwareVideoEncoder(name)
            try {
                candidate.start(config, output)
                CaptureDiagnostics.log("Encoder en uso: $name")
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "Encoder $name no arrancó, probando siguiente", e)
                CaptureDiagnostics.log("Encoder $name falló: ${e.message}")
                candidate.release()
                lastError = e
            }
        }
        throw IllegalStateException(
            "Ningún encoder H.264 pudo iniciarse (probados: ${names.joinToString()})",
            lastError,
        )
    }

    fun create(config: EncoderConfig): VideoEncoder {
        val names = codecNames(config)
        if (names.isEmpty()) {
            throw IllegalStateException("No hay encoder H.264 disponible en el dispositivo")
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

    /** Nombres de codec que se intentarán, priorizando aceleración por hardware. */
    fun codecNames(config: EncoderConfig): List<String> {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val avcEncoders = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        val names = mutableListOf<String>()

        for (preferred in PREFERRED_CODECS) {
            val match = avcEncoders.firstOrNull { it.name == preferred && !isSoftware(it) }
            if (match != null) names.add(match.name)
        }

        // Otros encoders hardware del dispositivo (MediaTek, Exynos, Kirin, etc.)
        for (info in avcEncoders) {
            if (!isSoftware(info) && info.name !in names) {
                names.add(info.name)
            }
        }

        // Último recurso: software, solo si ningún hardware arranca.
        for (info in avcEncoders) {
            if (isSoftware(info) && info.name !in names) names.add(info.name)
        }

        if (names.isEmpty()) {
            val selected = MediaCodecList(MediaCodecList.ALL_CODECS)
                .findEncoderForFormat(config.toMediaFormat(includeProfileLevel = false, includeRealtimeHints = false))
            if (selected != null) names.add(selected)
        }
        return names
    }

    /** Información real del códec principal, para consultar sus VideoCapabilities. */
    fun primaryCodecInfo(config: EncoderConfig): MediaCodecInfo? {
        val name = codecNames(config).firstOrNull() ?: return null
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == name && it.isEncoder }
    }

    private val PREFERRED_CODECS = arrayOf(
        "c2.qti.avc.encoder",
        "OMX.qcom.video.encoder.avc",
        "c2.mtk.avc.encoder",
        "OMX.MTK.VIDEO.ENCODER.AVC",
        "c2.exynos.avc.encoder",
        "OMX.Exynos.AVC.Encoder",
    )

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly
        }
        val n = info.name.lowercase()
        return n.startsWith("c2.android.") || n.startsWith("c2.google.") || n.startsWith("omx.google.")
    }

    private const val TAG = "EncoderFactory"
}
