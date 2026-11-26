package com.smoothrec.app.encoder

/**
 * Métricas reales de la ruta de captura con marca de agua GL.
 *
 * `captureFps` se calcula con los eventos reales entregados por SurfaceTexture;
 * `staleFramesDropped` cuenta fotogramas obsoletos que nunca se renderizan porque
 * ya había uno más reciente en la cola. No se sintetizan fotogramas ni FPS.
 */
data class CaptureFrameStats(
    val captureFps: Int = 0,
    val receivedFrames: Long = 0L,
    val staleFramesDropped: Long = 0L,
    val maxFrameAgeMs: Int = 0,
    val maxPendingFrames: Int = 0,
)
