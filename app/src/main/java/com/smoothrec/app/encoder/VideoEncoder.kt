package com.smoothrec.app.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/** Salida del codificador: el muxer se suscribe a estos eventos. */
interface EncoderOutput {
    fun onOutputFormatChanged(format: MediaFormat)
    fun onEncodedData(byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo)
}

/** Contrato del encoder de vídeo implementado en Fase 1. */
interface VideoEncoder {
    fun start(config: EncoderConfig, output: EncoderOutput)
    fun inputSurface(): Surface
    /** Señala el fin del stream y espera el drenado de los buffers pendientes. */
    fun stop()
    fun release()
}
