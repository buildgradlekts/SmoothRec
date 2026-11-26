package com.smoothrec.app.updater

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

/**
 * Expone el APK descargado en el almacenamiento privado de la app al instalador
 * del sistema. Evita depender de DownloadManager ni de permisos de almacenamiento
 * externo, y funciona igual en todas las versiones de Android soportadas.
 *
 * También guarda los metadatos de la descarga para poder reanudarla y para no
 * volver a descargar un APK que ya fue validado en esta sesión.
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

    data class Meta(
        val state: String,
        val url: String,
        val sha256: String?,
        val total: Long = -1L,
        val size: Long = -1L,
        val etag: String? = null,
        val lastModified: String? = null,
        val versionCode: Int = -1,
    )

    companion object {
        private const val FILE_NAME = "smoothrec-update.apk"
        private const val PART_FILE_NAME = "smoothrec-update.apk.part"
        private const val META_FILE_NAME = "smoothrec-update.apk.meta"
        const val STATE_PARTIAL = "partial"
        const val STATE_FINAL = "final"

        fun authority(context: Context): String = context.packageName + ".updatefileprovider"

        fun uriFor(context: Context): Uri =
            Uri.parse("content://${authority(context)}/$FILE_NAME")

        fun fileFor(context: Context): File = File(context.cacheDir, FILE_NAME)

        fun partialFileFor(context: Context): File = File(context.cacheDir, PART_FILE_NAME)

        fun metaFileFor(context: Context): File = File(context.cacheDir, META_FILE_NAME)

        fun fileForUri(uri: Uri, context: Context?): File? {
            if (uri.lastPathSegment != FILE_NAME || context == null) return null
            return fileFor(context)
        }

        fun readMeta(context: Context): Meta? {
            val file = metaFileFor(context)
            if (!file.exists()) return null
            return try {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                val url = props.getProperty("url") ?: return null
                Meta(
                    state = props.getProperty("state", STATE_PARTIAL),
                    url = url,
                    sha256 = props.getProperty("sha256")?.takeIf { it.isNotBlank() },
                    total = props.getProperty("total")?.toLongOrNull() ?: -1L,
                    size = props.getProperty("size")?.toLongOrNull() ?: -1L,
                    etag = props.getProperty("etag")?.takeIf { it.isNotBlank() },
                    lastModified = props.getProperty("last_modified")?.takeIf { it.isNotBlank() },
                    versionCode = props.getProperty("version_code")?.toIntOrNull() ?: -1,
                )
            } catch (e: Exception) {
                null
            }
        }

        fun writeMeta(context: Context, meta: Meta) {
            val props = Properties()
            props.setProperty("state", meta.state)
            props.setProperty("url", meta.url)
            meta.sha256?.let { props.setProperty("sha256", it) }
            if (meta.total >= 0) props.setProperty("total", meta.total.toString())
            if (meta.size >= 0) props.setProperty("size", meta.size.toString())
            meta.etag?.let { props.setProperty("etag", it) }
            meta.lastModified?.let { props.setProperty("last_modified", it) }
            if (meta.versionCode > 0) props.setProperty("version_code", meta.versionCode.toString())
            metaFileFor(context).outputStream().use { props.store(it, "SmoothRec update metadata") }
        }

        fun deleteMeta(context: Context) {
            metaFileFor(context).delete()
        }

        fun sameTarget(meta: Meta?, url: String, sha256: String?): Boolean {
            if (meta == null) return false
            if (meta.url != url) return false
            return meta.sha256.equals(sha256, ignoreCase = true)
        }
    }
}
