package com.smoothrec.app.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.ui.LocaleHelper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Comprobador de actualizaciones configurable y bajo consentimiento explícito.
 * Nunca descarga ni instala un APK sin confirmación del usuario.
 */
object UpdateManager {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    sealed class CheckResult {
        data object NoReleases : CheckResult()
        data class UpToDate(val currentVersionName: String, val currentVersionCode: Int) : CheckResult()
        data class Available(val info: UpdateInfo) : CheckResult()
        data object Error : CheckResult()
    }

    private const val TAG = "UpdateManager"
    private const val DEFAULT_RELEASES_URL =
        "https://api.github.com/repos/buildgradlekts/SmoothRec-Grabador-De-Pantalla/releases/latest"
    private const val PREFS = "smoothrec_updates"
    private const val PREF_RELEASES_URL = "releases_url"
    private const val PREF_LAST_CHECK = "last_update_check_ms"
    internal const val CHANNEL_ID = "smoothrec_updates"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SmoothRecUpdate").apply { isDaemon = true }
    }

    fun installedVersion(context: Context): Pair<String, Int> {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val name = pkgInfo.versionName ?: "1.0"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode
        }
        return name to code
    }

    fun getReleasesUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(PREF_RELEASES_URL, DEFAULT_RELEASES_URL) ?: DEFAULT_RELEASES_URL
    }

    fun setReleasesUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RELEASES_URL, url.trim().ifEmpty { DEFAULT_RELEASES_URL })
            .apply()
    }

    fun checkIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
        checkDetailed(context) { result ->
            if (result is CheckResult.Available) {
                notifyAvailable(context, result.info)
            }
        }
    }

    fun checkDetailed(context: Context, onResult: (CheckResult) -> Unit) {
        val appContext = context.applicationContext
        val releasesUrl = getReleasesUrl(appContext)
        executor.execute {
            val result = try {
                val (currentName, currentCode) = installedVersion(appContext)
                when (val fetched = fetchLatest(releasesUrl)) {
                    is FetchOutcome.NotFound -> CheckResult.NoReleases
                    is FetchOutcome.Ok -> {
                        val info = fetched.info
                        if (info.versionCode <= currentCode) {
                            CheckResult.UpToDate(currentName, currentCode)
                        } else {
                            CheckResult.Available(info)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error al comprobar actualizaciones", e)
                CheckResult.Error
            }
            Handler(appContext.mainLooper).post { onResult(result) }
        }
    }

    fun launchAuthorizedInstall(context: Context, info: UpdateInfo) {
        val intent = Intent(context, UpdateInstallActivity::class.java).apply {
            putExtra(EXTRA_APK_URL, info.apkUrl)
            putExtra(EXTRA_VERSION, info.versionName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private sealed class FetchOutcome {
        data object NotFound : FetchOutcome()
        data class Ok(val info: UpdateInfo) : FetchOutcome()
    }

    private fun fetchLatest(releasesUrl: String): FetchOutcome {
        val connection = (URL(releasesUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SmoothRec-Updater")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return FetchOutcome.NotFound
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val body = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }
            val release = JSONObject(body)
            val tag = release.optString("tag_name", "")
            val verCode = parseVersionCode(tag, release) ?: return FetchOutcome.NotFound
            val assets = release.optJSONArray("assets") ?: return FetchOutcome.NotFound
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                    return FetchOutcome.Ok(
                        UpdateInfo(
                            versionCode = verCode,
                            versionName = tag,
                            apkUrl = asset.optString("browser_download_url"),
                            notes = release.optString("body", "").ifBlank { "-" },
                        ),
                    )
                }
            }
            return FetchOutcome.NotFound
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVersionCode(tag: String, release: JSONObject): Int? {
        val clean = tag.removePrefix("v").substringBefore("-")
        clean.toIntOrNull()?.let { return it }
        // Permite tags semver tipo v0.3.0 -> 3 o v1.2.3 -> 10203 si no hay entero directo.
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.isNotEmpty()) {
            return when (parts.size) {
                1 -> parts[0]
                2 -> parts[0] * 100 + parts[1]
                else -> parts[0] * 10_000 + parts[1] * 100 + parts[2]
            }
        }
        return release.optInt("id", -1).takeIf { it > 0 }
    }

    private fun notifyAvailable(context: Context, info: UpdateInfo) {
        val localized = LocaleHelper.wrap(context)
        createChannel(localized)
        val intent = Intent(context, UpdateInstallActivity::class.java).apply {
            putExtra(EXTRA_APK_URL, info.apkUrl)
            putExtra(EXTRA_VERSION, info.versionName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, 1, intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(localized.getString(R.string.update_title, info.versionName))
            .setContentText(localized.getString(R.string.update_tap))
            .setStyle(Notification.BigTextStyle().bigText(info.notes))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localized = LocaleHelper.wrap(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    internal const val EXTRA_APK_URL = "apk_url"
    internal const val EXTRA_VERSION = "version"
    internal const val NOTIFICATION_ID = 8002
}
