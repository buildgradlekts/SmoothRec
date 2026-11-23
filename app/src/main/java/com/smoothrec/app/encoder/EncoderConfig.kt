package com.smoothrec.app.encoder

import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Configuración del codificador.
 * Preset validado en el dispositivo: c2.qti.avc.encoder, 1920x1080, 60 FPS, High@L5.
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
    fun toMediaFormat(): MediaFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
        setInteger(MediaFormat.KEY_PROFILE, profile)
        setInteger(MediaFormat.KEY_LEVEL, level)
    }

    companion object {
        val PRESET_1080P60 = EncoderConfig()
    }
}
