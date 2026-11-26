package com.smoothrec.app.muxer

/** Medición real del coste de escribir muestras en el MP4. */
data class MuxerStats(
    val samplesWritten: Long = 0L,
    val bytesWritten: Long = 0L,
    val maxWriteMs: Int = 0,
    val slowWrites: Int = 0,
    val writeErrors: Int = 0,
)
