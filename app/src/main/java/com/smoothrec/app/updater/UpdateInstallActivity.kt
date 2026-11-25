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
 * Descarga el APK autorizado por el usuario directamente en el almacenamiento
 * privado de la app y abre el instalador del sistema.
 *
 * Se abandonó DownloadManager porque en algunos dispositivos la descarga del
 * asset de GitHub nunca llegaba a completarse (o el archivo de destino ya
 * existía) y el fallo era indistinguible de "no descarga".
 */
class UpdateInstallActivity : Activity() {

    private var apkUrl: String? = null
    private var downloadThread: Thread? = null
    private var cancelled = false
    private var waitingForPermission = false
    private var progressShown = false

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button

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
            text = getString(R.string.update_downloading)
            textSize = 16f
            gravity = Gravity.CENTER
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
        retryButton = Button(this).apply {
            text = getString(R.string.update_retry)
            visibility = ViewGroup.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding }
            setOnClickListener {
                visibility = ViewGroup.GONE
                statusText.text = getString(R.string.update_downloading)
                progressBar.progress = 0
                downloadThread = null
                startDownload()
            }
        }
        container.addView(statusText)
        container.addView(progressBar)
        container.addView(retryButton)
        setContentView(container)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, apkUrl)
    }

    override fun onResume() {
        super.onResume()
        if (!canInstall()) {
            if (!waitingForPermission) {
                waitingForPermission = true
                statusText.text = getString(R.string.update_allow_install)
                progressBar.visibility = ViewGroup.GONE
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                )
            }
            return
        }
        waitingForPermission = false
        if (downloadThread == null) startDownload()
    }

    override fun onDestroy() {
        cancelled = true
        downloadThread?.interrupt()
        notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun startDownload() {
        val url = apkUrl ?: return
        cancelled = false
        downloadThread = Thread({
            try {
                download(url)
            } catch (_: InterruptedException) {
                // La actividad se cerró; no hay nada que mostrar.
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al descargar la actualización", e)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    hideProgressNotification()
                    statusText.text = getString(R.string.update_download_failed, e.message ?: e.javaClass.simpleName)
                    progressBar.isIndeterminate = false
                    retryButton.visibility = ViewGroup.VISIBLE
                    Toast.makeText(this, R.string.update_download_failed_short, Toast.LENGTH_LONG).show()
                }
            }
        }, "SmoothRecUpdateDownload").apply { start() }
    }

    private fun download(urlString: String) {
        val output = UpdateFileProvider.fileFor(this)
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw IOException("No se pudo reemplazar la descarga anterior")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SmoothRec-Updater/1.1 (Android)")
                setRequestProperty("Accept", "application/vnd.android.package-archive")
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val total = connection.contentLength
            connection.inputStream.use { input ->
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (cancelled) throw InterruptedException()
                        out.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) {
                            (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastPercent && (percent < 0 || percent % 5 == 0 || percent == 100)) {
                            lastPercent = percent
                            publishProgress(if (percent < 0) 50 else percent)
                        }
                    }
                }
            }
            if (output.length() < 100_000L) {
                throw IOException("El archivo descargado es demasiado pequeño")
            }
            publishProgress(100)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                install(output)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun publishProgress(percent: Int) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            progressBar.progress = percent
            statusText.text = getString(R.string.update_download_progress, percent)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, UpdateManager.CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle(getString(R.string.update_downloading))
                .setContentText(getString(R.string.update_download_progress, percent))
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, notification)
            progressShown = true
        }
    }

    private fun hideProgressNotification() {
        if (progressShown) notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
        progressShown = false
    }

    private fun install(file: File) {
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
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el instalador", e)
            Toast.makeText(this, getString(R.string.update_download_failed, e.message ?: "install"), Toast.LENGTH_LONG).show()
            retryButton.visibility = ViewGroup.VISIBLE
            return
        }
        finish()
    }

    private companion object {
        const val TAG = "UpdateInstallActivity"
        const val KEY_URL = "apk_url_saved"
        const val PROGRESS_NOTIFICATION_ID = 8003
    }
}
