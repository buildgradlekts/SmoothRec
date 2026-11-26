package com.smoothrec.app.encoder

/**
 * Métricas reales del encoder, derivadas de los buffers que MediaCodec entrega.
 * No se estiman FPS: los huecos se calculan comparando PTS consecutivos reales.
 */
data class EncoderStats(
    val encodedFrames: Long = 0L,
    val ptsGapFrames: Long = 0L,
    val maxPtsGapMs: Int = 0,
    val slowDeliveries: Int = 0,
    val maxDeliveryMs: Int = 0,
)
