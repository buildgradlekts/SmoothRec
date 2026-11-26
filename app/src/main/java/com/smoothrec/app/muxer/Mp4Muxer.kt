package com.smoothrec.app.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.encoder.EncoderOutput
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Escribe vídeo H.264 y audio AAC en un MP4 sobre el descriptor de MediaStore.
 *
 * Optimizaciones y sincronización:
 * - Cero asignaciones de ByteBuffer directo por fotograma una vez iniciado el muxer.
 * - Alinea el origen común cuando todas las pistas han entregado su primera muestra,
 *   conservando el desfase real y protegiendo frente a épocas de reloj distintas.
 * - Garantiza PTS estrictamente monótonos por pista.
 */
class Mp4Muxer(private val fileDescriptor: ParcelFileDescriptor) : EncoderOutput {

    private data class PendingSample(
        val trackId: String,
        val data: ByteBuffer,
        val rawPtsUs: Long,
        val arrivalNs: Long,
        val flags: Int,
    )

    private val lock = Any()
    private val formats = LinkedHashMap<String, MediaFormat>()
    private val indices = HashMap<String, Int>()
    private val pending = ArrayList<PendingSample>(16)

    private var sessionStartNs = 0L
    private val firstRawPts = HashMap<String, Long>()
    private val firstArrivalNs = HashMap<String, Long>()
    private val trackOriginsUs = HashMap<String, Long>()
    private val lastPts = HashMap<String, Long>()
    private var originsResolved = false
    private var monotonicFixes = 0

    private val reusableInfo = MediaCodec.BufferInfo()
    private var expectedTracks = 1
    private var muxer: MediaMuxer? = null
    private var started = false
    private var released = false
    private var bytesWritten = 0L
    private var writeErrors = 0
    private var slowWrites = 0
    private var maxWriteNs = 0L
    private var samplesWritten = 0L
    private var stopFailed = false
    private var firstVideoSampleNs = 0L

    fun prepare(expectedTracks: Int, sessionStartNs: Long = System.nanoTime()) {
        synchronized(lock) {
            this.expectedTracks = expectedTracks.coerceAtLeast(1)
            this.sessionStartNs = sessionStartNs
        }
        Log.i(TAG, "Muxer preparado: pistas=$expectedTracks")
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
            Log.w(TAG, "Formato tardío de $trackId: la pista no se añadirá")
            return
        }
        formats[trackId] = format
        if (trackId == EncoderOutput.TRACK_AUDIO) {
            // Se registra con la primera muestra real para evitar pistas mudas.
            return
        }
        try {
            ensureMuxer()
            indices[trackId] = muxer!!.addTrack(format)
            maybeStart()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar la pista $trackId", e)
        }
    }

    private fun maybeStart() {
        if (started || muxer == null) return
        if (indices.size < expectedTracks) return
        // Si esperamos audio y vídeo, esperamos a tener al menos 1 muestra de cada
        // pista registrada antes de fijar el origen temporal común.
        if (expectedTracks > 1 && firstRawPts.size < indices.size) return
        try {
            resolveOriginsLocked()
            muxer!!.start()
            started = true
            flushPending()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el muxer", e)
        }
    }

    @Synchronized
    override fun onEncodedData(trackId: String, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (released || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        val nowNs = System.nanoTime()
        if (!started && expectedTracks > 1 && trackId == EncoderOutput.TRACK_VIDEO) {
            if (firstVideoSampleNs == 0L) {
                firstVideoSampleNs = nowNs
            } else if (nowNs - firstVideoSampleNs > AUDIO_WAIT_TIMEOUT_NS) {
                Log.w(TAG, "Sin muestras de audio tras 2 s: se inicia el MP4 solo con vídeo")
                startWithAvailableTracks()
            }
        }

        var trackIndex = indices[trackId]
        if (trackIndex == null) {
            val format = formats[trackId]
            if (!started && format != null) {
                try {
                    ensureMuxer()
                    trackIndex = muxer!!.addTrack(format)
                    indices[trackId] = trackIndex
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo registrar la pista $trackId", e)
                    return
                }
            } else {
                Log.w(TAG, "Muestra de $trackId descartada: pista no registrada antes de start()")
                return
            }
        }

        if (!firstRawPts.containsKey(trackId)) {
            firstRawPts[trackId] = info.presentationTimeUs
            firstArrivalNs[trackId] = nowNs
        }

        if (!started) {
            // Solo copiamos muestras en el breve arranque previo a muxer.start().
            val copy = ByteBuffer.allocateDirect(info.size).apply {
                put(byteBuffer.duplicate().apply {
                    position(info.offset)
                    limit(info.offset + info.size)
                })
                flip()
            }
            pending.add(PendingSample(trackId, copy, info.presentationTimeUs, nowNs, info.flags))
            maybeStart()
            return
        }

        // Ruta rápida sin asignaciones ni copias de ByteBuffer durante la grabación.
        val pts = normalizePts(trackId, info.presentationTimeUs)
        byteBuffer.position(info.offset)
        byteBuffer.limit(info.offset + info.size)
        reusableInfo.set(info.offset, info.size, pts, info.flags)
        write(trackIndex, byteBuffer, reusableInfo)
    }

    private fun resolveOriginsLocked() {
        if (originsResolved) return
        originsResolved = true
        if (firstRawPts.isEmpty()) return

        val videoRaw = firstRawPts[EncoderOutput.TRACK_VIDEO]
        val audioRaw = firstRawPts[EncoderOutput.TRACK_AUDIO]
        if (videoRaw != null && audioRaw != null) {
            val diff = abs(videoRaw - audioRaw)
            if (diff <= MAX_SAME_EPOCH_DIFF_US) {
                val shared = minOf(videoRaw, audioRaw)
                trackOriginsUs[EncoderOutput.TRACK_VIDEO] = shared
                trackOriginsUs[EncoderOutput.TRACK_AUDIO] = shared
                Log.i(
                    TAG,
                    "Origen común sincronizado: $shared us (desfase inicial=${diff / 1000} ms)",
                )
                CaptureDiagnostics.log(
                    "Muxer: origen común=$shared us, delta inicial=${diff / 1000} ms",
                )
            } else {
                // Épocas distintas (p. ej. encoder empieza en 0 y audio usa reloj monotónico):
                // alineamos por instante real de llegada de la primera muestra.
                val minArrival = minOf(
                    firstArrivalNs[EncoderOutput.TRACK_VIDEO] ?: sessionStartNs,
                    firstArrivalNs[EncoderOutput.TRACK_AUDIO] ?: sessionStartNs,
                )
                for ((id, raw) in firstRawPts) {
                    val arrival = firstArrivalNs[id] ?: minArrival
                    val offsetUs = ((arrival - minArrival) / 1_000L).coerceAtLeast(0L)
                    trackOriginsUs[id] = raw - offsetUs
                }
                Log.w(TAG, "Épocas distintas detectadas ($diff us); alineado por llegada")
                CaptureDiagnostics.log("Muxer: épocas distintas ($diff us); alineado por llegada")
            }
        } else {
            for ((id, raw) in firstRawPts) {
                trackOriginsUs[id] = raw
            }
        }
    }

    private fun normalizePts(trackId: String, raw: Long): Long {
        val origin = trackOriginsUs[trackId] ?: raw.also { trackOriginsUs[trackId] = it }
        var pts = (raw - origin).coerceAtLeast(0L)
        val previous = lastPts[trackId]
        if (previous != null && pts <= previous) {
            monotonicFixes++
            pts = previous + 1L
        }
        lastPts[trackId] = pts
        return pts
    }

    @Synchronized
    fun startWithAvailableTracks() {
        if (released || started) return
        expectedTracks = indices.size.coerceAtLeast(1)
        if (indices.isNotEmpty()) {
            ensureMuxer()
            resolveOriginsLocked()
            try {
                muxer!!.start()
                started = true
                flushPending()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo arrancar el muxer con pistas disponibles", e)
            }
        }
    }

    private fun write(index: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val startNs = System.nanoTime()
        try {
            muxer?.writeSampleData(index, buffer, info)
            bytesWritten += info.size
            samplesWritten++
        } catch (e: Exception) {
            writeErrors++
            Log.w(TAG, "Error al escribir una muestra ($writeErrors)", e)
        } finally {
            val elapsedNs = System.nanoTime() - startNs
            if (elapsedNs > maxWriteNs) maxWriteNs = elapsedNs
            if (elapsedNs >= SLOW_WRITE_THRESHOLD_NS) slowWrites++
        }
    }

    fun stats(): MuxerStats = synchronized(lock) {
        MuxerStats(
            samplesWritten = samplesWritten,
            bytesWritten = bytesWritten,
            maxWriteMs = (maxWriteNs / 1_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            slowWrites = slowWrites,
            writeErrors = writeErrors,
        )
    }

    private fun flushPending() {
        for (sample in pending) {
            val index = indices[sample.trackId] ?: continue
            val pts = normalizePts(sample.trackId, sample.rawPtsUs)
            reusableInfo.set(0, sample.data.limit(), pts, sample.flags)
            write(index, sample.data, reusableInfo)
        }
        pending.clear()
    }

    fun hasData(): Boolean = bytesWritten > 0

    fun hasTrack(trackId: String): Boolean = synchronized(lock) { indices.containsKey(trackId) }

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
        val summary = "Muxer: PTS finales ${lastPts.entries.joinToString { "${it.key}=${it.value}" }} " +
            "us, correcciones de monotonía=$monotonicFixes"
        Log.i(TAG, summary)
        CaptureDiagnostics.log(summary)
        indices.clear()
        formats.clear()
        firstRawPts.clear()
        firstArrivalNs.clear()
        trackOriginsUs.clear()
        lastPts.clear()
        pending.clear()
        monotonicFixes = 0
        slowWrites = 0
        maxWriteNs = 0L
        samplesWritten = 0L
    }

    private companion object {
        const val TAG = "Mp4Muxer"
        const val AUDIO_WAIT_TIMEOUT_NS = 2_000_000_000L
        const val MAX_SAME_EPOCH_DIFF_US = 5_000_000L
        const val SLOW_WRITE_THRESHOLD_NS = 2_000_000L
    }
}
