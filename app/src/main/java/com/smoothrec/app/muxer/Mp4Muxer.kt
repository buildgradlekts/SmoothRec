package com.smoothrec.app.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.encoder.EncoderOutput
import java.nio.ByteBuffer

/**
 * Escribe vídeo H.264 y, cuando está disponible, audio AAC en un MP4 sobre el fd
 * de MediaStore. Las pistas se registran con su formato real y el muxer arranca
 * cuando están todas listas, de modo que no se pierden muestras iniciales.
 */
class Mp4Muxer(private val fileDescriptor: ParcelFileDescriptor) : EncoderOutput {

    private data class PendingSample(
        val trackId: String,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo,
    )

    private val lock = Any()
    private val formats = LinkedHashMap<String, MediaFormat>()
    private val indices = HashMap<String, Int>()
    private val pending = ArrayList<PendingSample>()

    private var expectedTracks = 1
    private var muxer: MediaMuxer? = null
    private var started = false
    private var released = false
    private var bytesWritten = 0L

    /** Debe llamarse antes de iniciar los codificadores. */
    fun prepare(expectedTracks: Int) {
        synchronized(lock) {
            this.expectedTracks = expectedTracks.coerceAtLeast(1)
        }
    }

    private fun ensureMuxer() {
        if (muxer == null) {
            muxer = MediaMuxer(
                fileDescriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        }
    }

    @Synchronized
    override fun onOutputFormatChanged(trackId: String, format: MediaFormat) {
        if (released || formats.containsKey(trackId)) return
        formats[trackId] = format
        try {
            ensureMuxer()
            indices[trackId] = muxer!!.addTrack(format)
            if (!started && formats.size >= expectedTracks) {
                muxer!!.start()
                started = true
                flushPending()
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar la pista $trackId", e)
        }
    }

    @Synchronized
    override fun onEncodedData(trackId: String, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (released || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        val copy = ByteBuffer.allocateDirect(info.size).apply {
            put(byteBuffer.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            })
            flip()
        }
        val copyInfo = MediaCodec.BufferInfo().apply {
            set(0, copy.limit(), info.presentationTimeUs, info.flags)
        }

        val index = indices[trackId]
        if (!started || index == null) {
            pending.add(PendingSample(trackId, copy, copyInfo))
            return
        }
        write(index, copy, copyInfo)
    }

    private fun write(index: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            muxer?.writeSampleData(index, buffer, info)
            bytesWritten += info.size
        } catch (e: Exception) {
            Log.w(TAG, "Error al escribir una muestra", e)
        }
    }

    private fun flushPending() {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            val index = indices[sample.trackId]
            if (index != null) write(index, sample.data, sample.info)
            iterator.remove()
        }
    }

    fun hasData(): Boolean = bytesWritten > 0

    fun hasTrack(trackId: String): Boolean = synchronized(lock) { indices.containsKey(trackId) }

    @Synchronized
    fun stop() {
        if (released) return
        try {
            if (started) muxer?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "El muxer no se pudo detener limpiamente", e)
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar el muxer", e)
        }
        released = true
        muxer = null
        started = false
        indices.clear()
        formats.clear()
        pending.clear()
    }

    private companion object {
        const val TAG = "Mp4Muxer"
    }
}
