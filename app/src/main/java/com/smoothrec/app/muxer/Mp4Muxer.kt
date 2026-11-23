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

    /**
     * MediaCodec con entrada Surface entrega PTS absolutos del reloj del sistema
     * (microsegundos desde el arranque). MediaMuxer solo los normaliza cuando la
     * pista de vídeo es la primera; con dos pistas escribe un edit list vacío
     * enorme y el MP4 queda con una duración absurda y un único fotograma visible.
     * Rebaseamos cada pista a su primera muestra y garantizamos PTS monotónicos.
     */
    private val firstPts = HashMap<String, Long>()
    private val lastPts = HashMap<String, Long>()

    private var expectedTracks = 1
    private var muxer: MediaMuxer? = null
    private var started = false
    private var released = false
    private var bytesWritten = 0L
    private var writeErrors = 0
    private var stopFailed = false

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
        if (started && !indices.containsKey(trackId)) {
            // MediaMuxer no admite añadir pistas después de start(): el formato
            // llegó tarde (por ejemplo, un encoder opcional que falló).
            Log.w(TAG, "Formato tardío de $trackId: la pista no se añadirá")
            return
        }
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
            set(0, copy.limit(), normalizePts(trackId, info.presentationTimeUs), info.flags)
        }

        val index = indices[trackId]
        if (!started || index == null) {
            pending.add(PendingSample(trackId, copy, copyInfo))
            return
        }
        write(index, copy, copyInfo)
    }

    /**
     * Convierte el PTS del codificador en un PTS relativo al inicio de la pista,
     * en microsegundos y estrictamente monotónico. Se aplica tanto a muestras
     * en directo como a las que estaban pendientes de que arrancara el muxer.
     */
    private fun normalizePts(trackId: String, raw: Long): Long {
        val origin = firstPts.getOrPut(trackId) {
            Log.i(TAG, "PTS inicial $trackId: $raw us")
            raw
        }
        var pts = raw - origin
        if (pts < 0) pts = 0
        val previous = lastPts[trackId]
        if (previous != null && pts <= previous) pts = previous + 1
        lastPts[trackId] = pts
        return pts
    }

    /** Arranca el muxer aunque no llegue el formato de alguna pista opcional. */
    @Synchronized
    fun startWithAvailableTracks() {
        if (released || started) return
        expectedTracks = 1
        if (formats.isNotEmpty()) {
            try {
                ensureMuxer()
                muxer!!.start()
                started = true
                flushPending()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar el muxer con las pistas disponibles", e)
            }
        }
    }

    private fun write(index: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            muxer?.writeSampleData(index, buffer, info)
            bytesWritten += info.size
        } catch (e: Exception) {
            writeErrors++
            Log.w(TAG, "Error al escribir una muestra ($writeErrors)", e)
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

    /** True solo si todas las muestras se escribieron y el contenedor se cerró bien. */
    fun finalizedOk(): Boolean = released && !stopFailed && writeErrors == 0

    @Synchronized
    fun stop() {
        if (released) return
        try {
            if (started) muxer?.stop()
        } catch (e: IllegalStateException) {
            stopFailed = true
            Log.w(TAG, "El muxer no se pudo detener limpiamente", e)
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            stopFailed = true
            Log.w(TAG, "Error al liberar el muxer", e)
        }
        released = true
        muxer = null
        started = false
        indices.clear()
        formats.clear()
        firstPts.clear()
        lastPts.clear()
        pending.clear()
    }

    private companion object {
        const val TAG = "Mp4Muxer"
    }
}
