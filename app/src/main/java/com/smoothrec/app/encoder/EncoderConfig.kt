package com.smoothrec.app.encoder

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/**
 * Configuración del codificador de vídeo H.264.
 */
data class EncoderConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 60,
    val bitRate: Int = 8_000_000,
    val iFrameIntervalSeconds: Int = 1,
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val profile: Int = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
    val level: Int = MediaCodecInfo.CodecProfileLevel.AVCLevel5,
) {
    fun toMediaFormat(
        includeProfileLevel: Boolean = true,
        includeRealtimeHints: Boolean = true,
    ): MediaFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
        if (includeProfileLevel) {
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_LEVEL, level)
        }
        if (includeRealtimeHints) {
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            // Prioridad 0 = tiempo real para reducir pérdida de frames en videojuegos.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
            }
        }
    }

    companion object {
        val PRESET_1080P60 = EncoderConfig()
    }
}
