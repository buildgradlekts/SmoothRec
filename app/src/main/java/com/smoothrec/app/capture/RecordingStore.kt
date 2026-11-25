package com.smoothrec.app.capture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prepara, publica, lista, obtiene miniaturas y elimina grabaciones exclusivas de SmoothRec. */
class RecordingStore(private val context: Context) {

    data class PendingRecording(val uri: Uri, val displayName: String)

    data class RecordingItem(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val dateAddedSec: Long,
    )

    fun prepare(displayName: String = defaultDisplayName()): PendingRecording {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + SUBDIRECTORY)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val targetDir = File(moviesDir, SUBDIRECTORY)
                if (!targetDir.exists()) targetDir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(targetDir, displayName).absolutePath)
            }
        }

        val collection = videoCollectionUri()
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore no devolvió Uri para la grabación")
        return PendingRecording(uri, displayName)
    }

    fun openForWrite(pending: PendingRecording): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(pending.uri, "rw")
            ?: throw IOException("No se pudo abrir el descriptor del vídeo")

    fun publish(pending: PendingRecording) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        context.contentResolver.update(pending.uri, values, null, null)
    }

    fun discard(pending: PendingRecording) {
        runCatching { context.contentResolver.delete(pending.uri, null, null) }
    }

    private fun defaultDisplayName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "SmoothRec-$timestamp.mp4"
    }

    companion object {
        private const val TAG = "RecordingStore"
        private const val MIME_TYPE = "video/mp4"
        private const val SUBDIRECTORY = "SmoothRec"

        private fun videoCollectionUri(): Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

        /**
         * Lista exclusivamente los vídeos grabados dentro de Movies/SmoothRec.
         * No mezcla grabaciones externas ni de otras aplicaciones.
         */
        fun listRecordings(context: Context): List<RecordingItem> {
            val resolver = context.contentResolver
            val collection = videoCollectionUri()
            val projection = mutableListOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
            )
            val selection: String
            val selectionArgs: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
                selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 0"
                selectionArgs = arrayOf("%Movies/$SUBDIRECTORY%")
            } else {
                @Suppress("DEPRECATION")
                selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                selectionArgs = arrayOf("%/$SUBDIRECTORY/%")
            }

            val items = mutableListOf<RecordingItem>()
            try {
                resolver.query(
                    collection,
                    projection.toTypedArray(),
                    selection,
                    selectionArgs,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "SmoothRec.mp4"
                        val size = cursor.getLong(sizeCol)
                        if (size <= 0L) continue
                        val uri = ContentUris.withAppendedId(collection, id)
                        items.add(
                            RecordingItem(
                                id = id,
                                uri = uri,
                                displayName = name,
                                durationMs = cursor.getLong(durCol).coerceAtLeast(0L),
                                width = cursor.getInt(wCol).coerceAtLeast(0),
                                height = cursor.getInt(hCol).coerceAtLeast(0),
                                sizeBytes = size,
                                dateAddedSec = cursor.getLong(dateCol).coerceAtLeast(0L),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron consultar las grabaciones", e)
            }
            return items
        }

        fun deleteRecording(context: Context, item: RecordingItem): Boolean {
            return try {
                context.contentResolver.delete(item.uri, null, null) > 0
            } catch (e: Exception) {
                Log.w(TAG, "Error al eliminar ${item.displayName}", e)
                false
            }
        }

        fun loadThumbnail(context: Context, item: RecordingItem): Bitmap? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    return context.contentResolver.loadThumbnail(item.uri, Size(240, 160), null)
                } catch (_: Exception) {
                }
            }
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, item.uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
