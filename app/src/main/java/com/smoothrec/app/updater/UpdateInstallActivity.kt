package com.smoothrec.app.updater

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.smoothrec.app.R
import com.smoothrec.app.ui.LocaleHelper
import com.smoothrec.app.ui.ThemeHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga el APK autorizado por el usuario en almacenamiento privado, verifica
 * SHA-256, firma/versión y abre el instalador del sistema.
 *
 * Soporta reanudación por Range, reintentos recuperables y caché del APK ya
 * validado para no descargar dos veces el mismo archivo.
 */
class UpdateInstallActivity : Activity() {

    private var apkUrl: String? = null
    private var versionName: String? = null
    private var expectedCode: Int = -1
    private var expectedSize: Long = -1L
    private var expectedSha: String? = null

    @Volatile private var downloadThread: Thread? = null
    @Volatile private var cancelled = false
    private var waitingForPermission = false
    private var installed = false
    private var progressShown = false
    private var attempts = 0
    private var lastPercent = -1

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sizeText: TextView
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyToActivity(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.applySystemBars(this)
        UpdateManager.createChannel(this)

        apkUrl = savedInstanceState?.getString(KEY_URL)
            ?: intent.getStringExtra(UpdateManager.EXTRA_APK_URL)
        versionName = savedInstanceState?.getString(KEY_VERSION)
            ?: intent.getStringExtra(UpdateManager.EXTRA_VERSION)
        expectedCode = savedInstanceState?.getInt(KEY_CODE, -1)
            ?: intent.getIntExtra(UpdateManager.EXTRA_CODE, -1)
        expectedSize = savedInstanceState?.getLong(KEY_SIZE, -1L)
            ?: intent.getLongExtra(UpdateManager.EXTRA_SIZE, -1L)
        expectedSha = savedInstanceState?.getString(KEY_SHA)
            ?: intent.getStringExtra(UpdateManager.EXTRA_SHA256)

        if (apkUrl.isNullOrBlank()) {
            Toast.makeText(this, R.string.update_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()
        notificationManager.cancel(UpdateManager.NOTIFICATION_ID)
    }

    private fun buildUi() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val textColor = ThemeHelper.resolveColor(this, R.attr.srTextPrimary)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        statusText = TextView(this).apply {
            text = versionName?.let { getString(R.string.update_downloading_version, it) }
                ?: getString(R.string.update_downloading)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(textColor)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding }
        }
        sizeText = TextView(this).apply {
            text = getString(R.string.update_download_size, "0 B", "—")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ThemeHelper.resolveColor(this@UpdateInstallActivity, R.attr.srTextSecondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding / 2 }
        }
        retryButton = Button(this).apply {
            text = getString(R.string.update_retry)
            visibility = ViewGroup.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding }
            setOnClickListener {
                if (!canInstall()) {
                    openUnknownSourcesSettings()
                    return@setOnClickListener
                }
                visibility = ViewGroup.GONE
                setStatus(R.string.update_downloading)
                attempts = 0
                lastPercent = -1
                startDownload()
            }
        }
        cancelButton = Button(this).apply {
            text = getString(R.string.common_cancel)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding / 2 }
            setOnClickListener { cancelDownload() }
        }
        container.apply {
            addView(statusText)
            addView(progressBar)
            addView(sizeText)
            addView(retryButton)
            addView(cancelButton)
        }
        setContentView(container)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, apkUrl)
        outState.putString(KEY_VERSION, versionName)
        outState.putInt(KEY_CODE, expectedCode)
        outState.putLong(KEY_SIZE, expectedSize)
        outState.putString(KEY_SHA, expectedSha)
    }

    override fun onResume() {
        super.onResume()
        if (!canInstall()) {
            if (!waitingForPermission) {
                waitingForPermission = true
                setStatus(R.string.update_allow_install)
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                openUnknownSourcesSettings()
            } else {
                setStatus(R.string.update_allow_install)
                retryButton.text = getString(R.string.update_open_settings)
                retryButton.visibility = ViewGroup.VISIBLE
            }
            return
        }
        waitingForPermission = false
        retryButton.text = getString(R.string.update_retry)
        if (downloadThread?.isAlive != true && !installed) startDownload()
    }

    override fun onDestroy() {
        if (!installed) {
            cancelled = true
            downloadThread?.interrupt()
        }
        hideProgressNotification()
        super.onDestroy()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir ajustes de instalación", e)
            setStatus(R.string.update_allow_install)
        }
    }

    private fun cancelDownload() {
        cancelled = true
        downloadThread?.interrupt()
        UpdateFileProvider.partialFileFor(this).delete()
        UpdateFileProvider.deleteMeta(this)
        hideProgressNotification()
        Toast.makeText(this, R.string.update_cancelled, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startDownload() {
        val url = apkUrl ?: return
        if (downloadThread?.isAlive == true || installed) return
        cancelled = false
        lastPercent = -1
        downloadThread = Thread({ downloadWithRetry(url) }, "SmoothRecUpdateDownload").apply { start() }
    }

    private fun downloadWithRetry(url: String) {
        try {
            while (!cancelled && attempts < MAX_ATTEMPTS) {
                attempts++
                try {
                    performDownload(url)
                    return
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) return
                    if (!isRecoverable(e) || attempts >= MAX_ATTEMPTS) {
                        publishFailure(e)
                        return
                    }
                    setStatus(R.string.update_retry_in, attempts + 1, MAX_ATTEMPTS)
                    Thread.sleep(RETRY_DELAYS[attempts - 1])
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            downloadThread = null
        }
    }

    private fun isRecoverable(error: Exception): Boolean {
        if (error is Fatal) return false
        if (error is HttpFailure) return error.code == 429 || error.code in 500..599
        return error is IOException
    }

    private fun performDownload(url: String) {
        if (!UpdateManager.isOnline(this)) {
            throw IOException(getString(R.string.update_no_connection))
        }

        val finalFile = UpdateFileProvider.fileFor(this)
        val partial = UpdateFileProvider.partialFileFor(this)
        val meta = UpdateFileProvider.readMeta(this)
        finalFile.parentFile?.mkdirs()

        val cachedFinal = UpdateFileProvider.sameTarget(meta, url, expectedSha) &&
            meta?.state == UpdateFileProvider.STATE_FINAL &&
            finalFile.exists() && meta.size == finalFile.length()
        if (cachedFinal) {
            publishProgress(100, finalFile.length(), finalFile.length())
            validateAndInstall(finalFile)
            return
        }

        var offset = 0L
        var etag: String? = null
        var lastModified: String? = null
        var declaredTotal = -1L
        val resumable = UpdateFileProvider.sameTarget(meta, url, expectedSha) &&
            meta?.state == UpdateFileProvider.STATE_PARTIAL &&
            partial.exists()
        if (resumable && meta != null) {
            offset = partial.length()
            etag = meta.etag
            lastModified = meta.lastModified
            declaredTotal = meta.total
        } else {
            partial.delete()
            finalFile.delete()
            UpdateFileProvider.deleteMeta(this)
        }

        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(url, offset, etag, lastModified)
            var code = connection.responseCode
            if (code == 416 && offset > 0) {
                connection.disconnect()
                partial.delete()
                offset = 0L
                connection = openConnection(url, 0L, null, null)
                code = connection.responseCode
            }
            if (code !in 200..299) throw HttpFailure(code)

            var total = declaredTotal
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val range = parseContentRange(connection.getHeaderField("Content-Range"))
                if (range == null || range.first != offset) {
                    // El servidor ignoró o cambió el Range: se descarta y se empieza de cero.
                    connection.disconnect()
                    partial.delete()
                    offset = 0L
                    etag = null
                    lastModified = null
                    connection = openConnection(url, 0L, null, null)
                    code = connection.responseCode
                    if (code !in 200..299) throw HttpFailure(code)
                    total = connection.contentLength.toLong().takeIf { it > 0 } ?: -1L
                } else {
                    total = range.second
                }
            } else {
                if (offset > 0) {
                    partial.delete()
                    offset = 0L
                }
                val length = connection.contentLength.toLong()
                if (length > 0) total = length
            }
            if (total <= 0 && expectedSize > 0) total = expectedSize

            etag = connection.getHeaderField("ETag") ?: etag
            lastModified = connection.getHeaderField("Last-Modified") ?: lastModified
            val appending = offset > 0

            UpdateFileProvider.writeMeta(
                this,
                UpdateFileProvider.Meta(
                    state = UpdateFileProvider.STATE_PARTIAL,
                    url = url,
                    sha256 = expectedSha,
                    total = total,
                    size = offset,
                    etag = etag,
                    lastModified = lastModified,
                    versionCode = expectedCode,
                ),
            )

            var written = 0L
            var lastUiBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, appending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (cancelled) throw InterruptedException()
                        output.write(buffer, 0, read)
                        written += read.toLong()
                        val downloaded = offset + written
                        if (downloaded - lastUiBytes >= 512L * 1024L) {
                            lastUiBytes = downloaded
                            publishProgress(percent(downloaded, total), downloaded, total)
                        }
                    }
                    output.flush()
                }
            }

            val downloaded = offset + written
            if (total > 0 && downloaded != total) {
                throw IOException(getString(R.string.update_download_incomplete))
            }
            if (downloaded < 100_000L) {
                throw Fatal(R.string.update_verify_invalid, "$downloaded bytes")
            }
            // Se hashes el archivo COMPLETO (no un prefijo) para que la verificación
            // sea idéntica tanto en una descarga nueva como en una reanudada.
            val calculated = UpdateManager.sha256(partial)
            if (calculated == null) {
                throw IOException(getString(R.string.update_verify_hash_error))
            }
            if (!expectedSha.isNullOrBlank() && !calculated.equals(expectedSha, ignoreCase = true)) {
                partial.delete()
                UpdateFileProvider.deleteMeta(this)
                throw Fatal(R.string.update_verify_hash, calculated.take(12))
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("No se pudo reemplazar la descarga anterior")
            }
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
            UpdateFileProvider.writeMeta(
                this,
                UpdateFileProvider.Meta(
                    state = UpdateFileProvider.STATE_FINAL,
                    url = url,
                    sha256 = expectedSha,
                    total = total,
                    size = downloaded,
                    etag = etag,
                    lastModified = lastModified,
                    versionCode = expectedCode,
                ),
            )
            publishProgress(100, downloaded, total)
            validateAndInstall(finalFile)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        offset: Long,
        etag: String?,
        lastModified: String?,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "SmoothRec-Updater/2.0 (Android)")
        setRequestProperty("Accept", "application/vnd.android.package-archive")
        if (offset > 0) {
            setRequestProperty("Range", "bytes=$offset-")
            if (!etag.isNullOrBlank()) setRequestProperty("If-Range", etag)
            else if (!lastModified.isNullOrBlank()) setRequestProperty("If-Range", lastModified)
        }
    }

    private fun parseContentRange(header: String?): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
            .find(header) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return start to total
    }

    private fun percent(downloaded: Long, total: Long): Int =
        if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else -1

    private fun validateAndInstall(file: File) {
        setStatus(R.string.update_verifying)
        when (val validation = UpdateManager.verifyDownloadedApk(
            this,
            file,
            expectedCode,
            expectedSha,
        )) {
            is UpdateManager.ApkValidation.Valid -> {
                installed = true
                hideProgressNotification()
                install(file)
            }
            is UpdateManager.ApkValidation.Invalid -> {
                UpdateFileProvider.fileFor(this).delete()
                UpdateFileProvider.partialFileFor(this).delete()
                UpdateFileProvider.deleteMeta(this)
                throw Fatal(validation.messageRes, validation.detail)
            }
        }
    }

    private fun install(file: File) {
        if (!canInstall()) {
            installed = false
            waitingForPermission = false
            onResume()
            return
        }
        hideProgressNotification()
        val uri = UpdateFileProvider.uriFor(this)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            Toast.makeText(this, R.string.update_installing, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el instalador", e)
            installed = false
            publishFailure(e)
        }
    }

    private fun setStatus(messageRes: Int, vararg args: Any) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            statusText.text = if (args.isEmpty()) getString(messageRes) else getString(messageRes, *args)
        }
    }

    private fun publishProgress(percent: Int, downloaded: Long, total: Long) {
        mainHandler.post {
            if (isFinishing || isDestroyed || installed) return@post
            progressBar.isIndeterminate = percent < 0
            progressBar.progress = percent.coerceAtLeast(0)
            statusText.text = if (percent >= 0) {
                getString(R.string.update_download_progress, percent)
            } else {
                getString(R.string.update_downloading)
            }
            sizeText.text = if (total > 0) {
                getString(
                    R.string.update_download_size,
                    UpdateManager.formatBytes(downloaded),
                    UpdateManager.formatBytes(total),
                )
            } else {
                getString(R.string.update_download_size_unknown, UpdateManager.formatBytes(downloaded))
            }

            if (percent == lastPercent && percent >= 0) return@post
            lastPercent = percent
            val detail = if (total > 0) {
                getString(
                    R.string.update_download_size,
                    UpdateManager.formatBytes(downloaded),
                    UpdateManager.formatBytes(total),
                )
            } else {
                getString(R.string.update_download_size_unknown, UpdateManager.formatBytes(downloaded))
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, UpdateManager.CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle(getString(R.string.update_downloading))
                .setContentText(detail)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, notification)
            progressShown = true
        }
    }

    private fun publishFailure(error: Exception) {
        hideProgressNotification()
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            val message = when (error) {
                is Fatal -> {
                    val base = getString(error.messageRes)
                    if (error.detail.isNullOrBlank()) base else "$base (${error.detail})"
                }
                is HttpFailure -> getString(R.string.update_download_http, error.code)
                else -> getString(R.string.update_download_failed, error.message ?: error.javaClass.simpleName)
            }
            statusText.text = message
            progressBar.isIndeterminate = false
            retryButton.visibility = ViewGroup.VISIBLE
            Toast.makeText(this, R.string.update_download_failed_short, Toast.LENGTH_LONG).show()
        }
    }

    private fun hideProgressNotification() {
        if (progressShown) notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
        progressShown = false
    }

    private class HttpFailure(val code: Int) : IOException("HTTP $code")

    private class Fatal(
        val messageRes: Int,
        val detail: String? = null,
    ) : Exception(detail)

    private companion object {
        const val TAG = "UpdateInstallActivity"
        const val KEY_URL = "apk_url_saved"
        const val KEY_VERSION = "version_saved"
        const val KEY_CODE = "code_saved"
        const val KEY_SIZE = "size_saved"
        const val KEY_SHA = "sha_saved"
        const val PROGRESS_NOTIFICATION_ID = 8003
        const val MAX_ATTEMPTS = 4
        val RETRY_DELAYS = longArrayOf(1_000L, 3_000L, 8_000L)
    }
}
