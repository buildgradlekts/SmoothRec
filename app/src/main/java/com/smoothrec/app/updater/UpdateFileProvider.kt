package com.smoothrec.app.updater

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Expone el APK descargado en el almacenamiento privado de la app al instalador
 * del sistema. Evita depender de DownloadManager ni de permisos de almacenamiento
 * externo, y funciona igual en todas las versiones de Android soportadas.
 */
class UpdateFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fileForUri(uri, getContext()) ?: throw FileNotFoundException("APK de actualización no encontrado")
        if (!file.exists()) throw FileNotFoundException("APK de actualización no encontrado")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val FILE_NAME = "smoothrec-update.apk"

        fun authority(context: Context): String = context.packageName + ".updatefileprovider"

        fun uriFor(context: Context): Uri =
            Uri.parse("content://${authority(context)}/$FILE_NAME")

        fun fileFor(context: Context): File = File(context.cacheDir, FILE_NAME)

        fun fileForUri(uri: Uri, context: Context?): File? {
            if (uri.lastPathSegment != FILE_NAME || context == null) return null
            return fileFor(context)
        }
    }
}
