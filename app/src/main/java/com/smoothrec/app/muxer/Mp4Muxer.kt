package com.smoothrec.app.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.encoder.EncoderOutput
import java.nio.ByteBuffer

/** Escribe los paquetes H.264 en un contenedor MP4 sobre el fd de MediaStore. */
class Mp4Muxer(private val fileDescriptor: ParcelFileDescriptor) : EncoderOutput {

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var bytesWritten = 0L

    fun start() {
        muxer = MediaMuxer(
            fileDescriptor.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
    }

    @Synchronized
    override fun onOutputFormatChanged(format: MediaFormat) {
        val m = muxer ?: return
        if (trackIndex >= 0) return
        trackIndex = m.addTrack(format)
        m.start()
        started = true
    }

    @Synchronized
    override fun onEncodedData(byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        if (!started || trackIndex < 0 || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        m.writeSampleData(trackIndex, byteBuffer, info)
        bytesWritten += info.size
    }

    fun hasData(): Boolean = bytesWritten > 0

    @Synchronized
    fun stop() {
        val m = muxer ?: return
        try {
            if (started) m.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "El muxer no se pudo detener limpiamente", e)
        }
        try {
            m.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar el muxer", e)
        }
        muxer = null
        started = false
        trackIndex = -1
    }

    private companion object {
        const val TAG = "Mp4Muxer"
    }
}
