package com.smoothrec.app.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prepara, abre y publica la entrada de vídeo en MediaStore. */
class RecordingStore(private val context: Context) {

    data class PendingRecording(val uri: Uri, val displayName: String)

    fun prepare(displayName: String = defaultDisplayName()): PendingRecording {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + SUBDIRECTORY)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore no devolvió Uri para la grabación")
        return PendingRecording(uri, displayName)
    }

    fun openForWrite(pending: PendingRecording): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(pending.uri, "rw")
            ?: throw IOException("No se pudo abrir el descriptor del vídeo")

    /** Hace visible el archivo una vez que el MP4 contiene datos válidos. */
    fun publish(pending: PendingRecording) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        context.contentResolver.update(pending.uri, values, null, null)
    }

    /** Elimina la entrada cuando no se ha escrito contenido. */
    fun discard(pending: PendingRecording) {
        context.contentResolver.delete(pending.uri, null, null)
    }

    private fun defaultDisplayName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "SmoothRec-$timestamp.mp4"
    }

    private companion object {
        const val MIME_TYPE = "video/mp4"
        const val SUBDIRECTORY = "SmoothRec"
    }
}
