package com.smoothrec.app.updater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.smoothrec.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateDownloader(context: Context, private val info: UpdateManager.UpdateInfo) {

    interface Listener {
        fun onProgress(downloaded: Long, total: Long)
        fun onVerifying()
        fun onReady(file: File)
        fun onError(messageRes: Int, detail: String?)
    }

    private class Failure(val messageRes: Int, val detail: String?, val recoverable: Boolean) :
        Exception(detail)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    fun start(listener: Listener) {
        if (thread?.isAlive == true) return
        cancelled = false
        thread = Thread({ run(listener) }, "SmoothRecUpdateDl").apply { start() }
    }

    fun cancel() {
        cancelled = true
        thread?.interrupt()
    }

    private fun post(block: () -> Unit) = main.post { block() }

    private fun run(l: Listener) {
        synchronized(LOCK) {
            try {
                if (!UpdateManager.isSecureUrl(info.apkUrl)) {
                    throw Failure(R.string.update_error, null, false)
                }
                if (info.sha256.isNullOrBlank()) {
                    throw Failure(R.string.update_verify_hash_missing, null, false)
                }
                var attempt = 0
                var result: File? = null
                while (result == null) {
                    attempt++
                    try {
                        result = downloadOnce(l)
                    } catch (e: Failure) {
                        if (!e.recoverable || attempt >= MAX_ATTEMPTS) throw e
                        Thread.sleep(RETRY_MS[attempt - 1])
                    } catch (e: IOException) {
                        if (attempt >= MAX_ATTEMPTS) throw Failure(R.string.update_error, e.message, false)
                        Thread.sleep(RETRY_MS[attempt - 1])
                    }
                }
                val apk: File = result
                if (cancelled) return
                post { l.onVerifying() }
                when (val v = UpdateManager.verifyDownloadedApk(app, apk, info.versionCode, info.sha256)) {
                    is UpdateManager.ApkValidation.Valid -> post { l.onReady(apk) }
                    is UpdateManager.ApkValidation.Invalid -> {
                        apk.delete()
                        UpdateFileProvider.partialFileFor(app).delete()
                        UpdateFileProvider.deleteMeta(app)
                        throw Failure(v.messageRes, v.detail, false)
                    }
                }
            } catch (_: InterruptedException) {
                // cancelado por el usuario
            } catch (e: Failure) {
                if (!cancelled) post { l.onError(e.messageRes, e.detail) }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo inesperado en la descarga", e)
                if (!cancelled) post { l.onError(R.string.update_error, e.message) }
            }
        }
    }

    private fun downloadOnce(l: Listener): File {
        val url = info.apkUrl
        val finalFile = UpdateFileProvider.fileFor(app)
        val part = UpdateFileProvider.partialFileFor(app)
        finalFile.parentFile?.mkdirs()
        val meta = UpdateFileProvider.readMeta(app)
        val same = UpdateFileProvider.sameTarget(meta, url, info.sha256)

        if (same && meta?.state == UpdateFileProvider.STATE_FINAL &&
            finalFile.exists() && meta.size == finalFile.length()
        ) {
            val size = finalFile.length()
            post { l.onProgress(size, size) }
            return finalFile
        }

        var requested = 0L
        var etag: String? = null
        var lastMod: String? = null
        if (same && meta?.state == UpdateFileProvider.STATE_PARTIAL && part.exists()) {
            requested = part.length()
            etag = meta.etag
            lastMod = meta.lastModified
        } else {
            part.delete(); finalFile.delete(); UpdateFileProvider.deleteMeta(app)
        }

        val (conn, offset) = connect(url, requested, etag, lastMod, part)
        try {
            if (offset != requested) { etag = null; lastMod = null }
            var total = info.assetSize
            if (conn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                parseRange(conn.getHeaderField("Content-Range"))?.let { total = it.second }
            } else {
                conn.contentLengthLong.takeIf { it > 0 }?.let { total = it }
            }
            etag = conn.getHeaderField("ETag") ?: etag
            lastMod = conn.getHeaderField("Last-Modified") ?: lastMod
            val totalFinal = total

            UpdateFileProvider.writeMeta(
                app,
                UpdateFileProvider.Meta(
                    UpdateFileProvider.STATE_PARTIAL, url, info.sha256,
                    totalFinal, offset, etag, lastMod, info.versionCode,
                ),
            )

            var downloaded = offset
            var lastUi = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi >= 200L) {
                            lastUi = now
                            val d = downloaded
                            post { l.onProgress(d, totalFinal) }
                        }
                    }
                }
            }

            if (totalFinal > 0 && downloaded != totalFinal) {
                throw Failure(R.string.update_download_incomplete, null, true)
            }
            if (downloaded < 100_000L) {
                throw Failure(R.string.update_verify_invalid, "$downloaded bytes", false)
            }
            val calculated = UpdateManager.sha256(part)
                ?: throw Failure(R.string.update_verify_hash_error, null, true)
            if (!calculated.equals(info.sha256, ignoreCase = true)) {
                part.delete(); UpdateFileProvider.deleteMeta(app)
                throw Failure(R.string.update_verify_hash, calculated.take(12), false)
            }
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
            }
            UpdateFileProvider.writeMeta(
                app,
                UpdateFileProvider.Meta(
                    UpdateFileProvider.STATE_FINAL, url, info.sha256,
                    totalFinal, downloaded, etag, lastMod, info.versionCode,
                ),
            )
            val done = downloaded
            post { l.onProgress(done, totalFinal) }
            return finalFile
        } finally {
            conn.disconnect()
        }
    }

    /** Abre la conexión; si el servidor ignora o rompe el Range, reinicia desde cero. */
    private fun connect(
        url: String, offset0: Long, etag: String?, lastMod: String?, part: File,
    ): Pair<HttpURLConnection, Long> {
        var offset = offset0
        var conn = open(url, offset, etag, lastMod)
        var code = conn.responseCode
        fun restart() {
            conn.disconnect()
            part.delete()
            offset = 0L
            conn = open(url, 0L, null, null)
            code = conn.responseCode
        }
        if (code == 416 && offset > 0) {
            restart()
        } else if (code == HttpURLConnection.HTTP_PARTIAL) {
            val r = parseRange(conn.getHeaderField("Content-Range"))
            if (r == null || r.first != offset) restart()
        } else if (code == HttpURLConnection.HTTP_OK && offset > 0) {
            part.delete(); offset = 0L // el servidor ignoró el Range: llega el archivo completo
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw Failure(R.string.update_error, "HTTP $code", code == 429 || code in 500..599)
        }
        return conn to offset
    }

    private fun open(url: String, offset: Long, etag: String?, lastMod: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "SmoothRec-Updater/2.0 (Android)")
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                if (!etag.isNullOrBlank()) setRequestProperty("If-Range", etag)
                else if (!lastMod.isNullOrBlank()) setRequestProperty("If-Range", lastMod)
            }
        }

    private fun parseRange(header: String?): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE).find(header) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[3].toLongOrNull() ?: return null
        return start to total
    }

    private companion object {
        const val TAG = "UpdateDownloader"
        const val MAX_ATTEMPTS = 3
        val RETRY_MS = longArrayOf(2_000L, 5_000L)
        val LOCK = Any()
    }
}
