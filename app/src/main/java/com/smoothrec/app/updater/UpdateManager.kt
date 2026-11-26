package com.smoothrec.app.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.ui.LocaleHelper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Comprobador e instalador de actualizaciones configurable y bajo consentimiento
 * explícito. Nunca descarga ni instala un APK sin confirmación del usuario.
 *
 * El servidor se configura en Ajustes y debe publicar un JSON de GitHub Releases
 * con un asset .apk. Para verificar la integridad se acepta:
 *  - el campo "digest" del asset (sha256:...),
 *  - un asset hermano ".apk.sha256" o "sha256sum.txt",
 *  - o una línea "SHA-256: <hex>" en las notas del release.
 */
object UpdateManager {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val sha256: String? = null,
        val assetSize: Long = -1L,
        val hasExplicitCode: Boolean = false,
    )

    sealed class CheckResult {
        data object NoReleases : CheckResult()
        data class UpToDate(val currentVersionName: String, val currentVersionCode: Int) : CheckResult()
        data class Available(val info: UpdateInfo) : CheckResult()
        data class Error(val messageRes: Int = R.string.update_error) : CheckResult()
    }

    sealed class ApkValidation {
        data class Valid(
            val versionName: String,
            val versionCode: Int,
            val packageName: String,
        ) : ApkValidation()

        data class Invalid(val messageRes: Int, val detail: String? = null) : ApkValidation()
    }

    private const val TAG = "UpdateManager"
    private const val DEFAULT_RELEASES_URL =
        "https://api.github.com/repos/buildgradlekts/SmoothRec/releases/latest"
    private const val PREFS = "smoothrec_updates"
    private const val PREF_RELEASES_URL = "releases_url"
    private const val PREF_LAST_CHECK = "last_update_check_ms"
    private const val PREF_SKIPPED = "skipped_version"
    internal const val CHANNEL_ID = "smoothrec_updates"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val RETRY_AFTER_ERROR_MS = 15 * 60 * 1000L
    @Volatile private var promptShown = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SmoothRecUpdate").apply { isDaemon = true }
    }

    fun installedVersion(context: Context): Pair<String, Int> {
        val pkgInfo = packageInfo(context, context.packageName) ?: return "1.0" to 0
        val name = pkgInfo.versionName ?: "1.0"
        return name to versionCodeOf(pkgInfo)
    }

    internal fun isSecureUrl(url: String): Boolean = try {
        URL(url).protocol.equals("https", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    fun getReleasesUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_RELEASES_URL, DEFAULT_RELEASES_URL) ?: DEFAULT_RELEASES_URL
        return if (isSecureUrl(stored)) stored else DEFAULT_RELEASES_URL
    }

    /** @return false si la URL no es HTTPS (no se guarda). */
    fun setReleasesUrl(context: Context, url: String): Boolean {
        val clean = url.trim().ifEmpty { DEFAULT_RELEASES_URL }
        if (!isSecureUrl(clean)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RELEASES_URL, clean)
            .apply()
        return true
    }

    /** Guarda la versión que el usuario eligió omitir en el aviso de actualización. */
    fun skipVersion(context: Context, versionName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_SKIPPED, versionName).apply()
    }

    /** Consulta al abrir la app (sin el límite de 6 h) y avisa si hay que mostrar la ventana. */
    fun checkForPrompt(context: Context, onAvailable: (UpdateInfo) -> Unit) {
        if (promptShown) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        checkDetailed(context) { result ->
            if (result is CheckResult.Available && !promptShown &&
                prefs.getString(PREF_SKIPPED, null) != result.info.versionName
            ) {
                promptShown = true
                onAvailable(result.info)
            }
        }
    }

    /** APK ya descargado y más nuevo que el instalado (para la fila de Inicio). */
    fun readyUpdate(context: Context): UpdateInfo? {
        val meta = UpdateFileProvider.readMeta(context) ?: return null
        if (meta.state != UpdateFileProvider.STATE_FINAL) return null
        val file = UpdateFileProvider.fileFor(context)
        if (!file.exists() || meta.size != file.length()) return null
        val pkg = packageInfo(context, file.absolutePath) ?: return null
        if (pkg.packageName != context.packageName) return null
        val code = versionCodeOf(pkg)
        if (code <= installedVersion(context).second) {
            // Ya instalada: limpia el caché.
            file.delete(); UpdateFileProvider.deleteMeta(context)
            return null
        }
        return UpdateInfo(
            versionCode = code,
            versionName = pkg.versionName ?: "-",
            apkUrl = meta.url,
            notes = "",
            sha256 = meta.sha256,
            assetSize = meta.size,
            hasExplicitCode = true,
        )
    }

    /**
     * Comprueba el APK cacheado fuera del hilo principal. getPackageArchiveInfo()
     * parsea el APK y puede tardar decenas de ms; hacerlo en onResume() bloquea la UI.
     * El resultado se entrega en el hilo principal y se mide en el diagnóstico.
     */
    fun readyUpdateAsync(context: Context, onResult: (UpdateInfo?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val startedNs = System.nanoTime()
            val ready = try {
                readyUpdate(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Error al comprobar la actualización lista", e)
                null
            }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            CaptureDiagnostics.log(
                "UI: fila de actualización comprobada en ${elapsedMs}ms " +
                    "(hilo=${Thread.currentThread().name})",
            )
            Handler(appContext.mainLooper).post { onResult(ready) }
        }
    }

    fun checkIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        // Reserva de 15 min: evita consultas duplicadas si la pantalla se recrea.
        prefs.edit()
            .putLong(PREF_LAST_CHECK, now - CHECK_INTERVAL_MS + RETRY_AFTER_ERROR_MS)
            .apply()
        checkDetailed(context) { result ->
            if (result !is CheckResult.Error) {
                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            }
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
                if (!isOnline(appContext)) {
                    CheckResult.Error(R.string.update_no_connection)
                } else {
                    val (currentName, currentCode) = installedVersion(appContext)
                    when (val fetched = fetchLatest(releasesUrl)) {
                        is FetchOutcome.NotFound -> CheckResult.NoReleases
                        is FetchOutcome.Ok -> {
                            if (isNewer(fetched.info, currentName, currentCode)) {
                                CheckResult.Available(fetched.info)
                            } else {
                                CheckResult.UpToDate(currentName, currentCode)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error al comprobar actualizaciones", e)
                CheckResult.Error(R.string.update_error)
            }
            Handler(appContext.mainLooper).post { onResult(result) }
        }
    }

    fun launchAuthorizedInstall(context: Context, info: UpdateInfo) {
        val intent = Intent(context, UpdateInstallActivity::class.java).apply {
            putExtra(EXTRA_APK_URL, info.apkUrl)
            putExtra(EXTRA_VERSION, info.versionName)
            putExtra(EXTRA_CODE, info.versionCode)
            putExtra(EXTRA_SIZE, info.assetSize)
            putExtra(EXTRA_SHA256, info.sha256)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private sealed class FetchOutcome {
        data object NotFound : FetchOutcome()
        data class Ok(val info: UpdateInfo) : FetchOutcome()
    }

    private fun fetchLatest(releasesUrl: String): FetchOutcome {
        val body = get(releasesUrl, "application/vnd.github+json") ?: return FetchOutcome.NotFound
        val release = JSONObject(body)
        // /releases/latest ya excluye borradores y preliminares; se comprueba
        // de forma explícita por si se configurara otro endpoint de releases.
        if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
            return FetchOutcome.NotFound
        }
        val tag = release.optString("tag_name", "")
        val notes = release.optString("body", "").ifBlank { "-" }
        val assets = release.optJSONArray("assets") ?: return FetchOutcome.NotFound
        val apkAssets = mutableListOf<JSONObject>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val state = asset.optString("state", "uploaded")
            if (!state.equals("uploaded", ignoreCase = true)) continue
            if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                apkAssets.add(asset)
            }
        }
        val apk = pickApkAsset(apkAssets) ?: return FetchOutcome.NotFound
        val apkName = apk.optString("name", "")
        val apkUrl = apk.optString("browser_download_url", "")
        if (apkUrl.isBlank()) return FetchOutcome.NotFound
        if (!isSecureUrl(apkUrl)) return FetchOutcome.NotFound

        val explicit = UpdateVersion.explicitCode(tag, apkName, notes)
        val digest = UpdateVersion.extractSha256(
            apk.optString("digest", ""),
            release.optString("digest", ""),
            notes,
        ) ?: resolveChecksumAsset(assets, apkName)

        return FetchOutcome.Ok(
            UpdateInfo(
                versionCode = explicit ?: -1,
                versionName = tag,
                apkUrl = apkUrl,
                notes = notes,
                sha256 = digest,
                assetSize = apk.optLong("size", -1L),
                hasExplicitCode = explicit != null,
            ),
        )
    }

    /** Prefiere el APK universal y, si no lo hay, el de la ABI principal del equipo. */
    private fun pickApkAsset(assets: List<JSONObject>): JSONObject? {
        if (assets.isEmpty()) return null
        val universal = assets.firstOrNull { it.optString("name", "").contains("universal", true) }
        if (universal != null) return universal
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi.isNotBlank()) {
            val exact = assets.firstOrNull { it.optString("name", "").contains(abi, true) }
            if (exact != null) return exact
        }
        return assets.firstOrNull()
    }

    private fun resolveChecksumAsset(assets: org.json.JSONArray, apkName: String): String? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            val isChecksum = name.equals("$apkName.sha256", true) ||
                name.equals("sha256sum.txt", true) ||
                name.equals("checksums.txt", true) ||
                (name.contains("sha256", true) && !name.endsWith(".apk", true))
            if (!isChecksum) continue
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) continue
            val text = runCatching { get(url, "text/plain") }.getOrNull() ?: continue
            val hash = UpdateVersion.extractSha256(text)
            if (hash != null) return hash
        }
        return null
    }

    private fun get(urlString: String, accept: String): String? {
        require(isSecureUrl(urlString)) { "Solo se permiten URLs HTTPS" }
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SmoothRec-Updater/2.0 (Android)")
        }
        try {
            val code = connection.responseCode
            // 404 = "sin releases" (mensaje informativo). Cualquier otro error
            // HTTP debe llegar como error de conexión, no como "no hay update".
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code al consultar $urlString")
                throw IOException("HTTP $code")
            }
            return connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(info: UpdateInfo, currentName: String, currentCode: Int): Boolean {
        if (info.hasExplicitCode) return info.versionCode > currentCode
        return UpdateVersion.isNewer(info.versionName, currentName)
    }

    /** Valida el APK antes de abrir el instalador del sistema. */
    fun verifyDownloadedApk(
        context: Context,
        file: File,
        expectedVersionCode: Int,
        expectedSha256: String?,
    ): ApkValidation {
        if (expectedSha256.isNullOrBlank()) {
            return ApkValidation.Invalid(R.string.update_verify_hash_missing)
        }
        val actualHash = sha256(file) ?: return ApkValidation.Invalid(R.string.update_verify_hash_error)
        if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
            return ApkValidation.Invalid(R.string.update_verify_hash, actualHash.take(12))
        }
        if (file.length() < 100_000L) {
            return ApkValidation.Invalid(R.string.update_verify_invalid, "${file.length()} bytes")
        }

        val downloaded = packageInfo(context, file.absolutePath)
            ?: return ApkValidation.Invalid(R.string.update_verify_invalid)
        val installed = packageInfo(context, context.packageName)
            ?: return ApkValidation.Invalid(R.string.update_verify_invalid)

        if (downloaded.packageName != context.packageName) {
            return ApkValidation.Invalid(R.string.update_verify_package, downloaded.packageName)
        }
        val downloadedCode = versionCodeOf(downloaded)
        val installedCode = versionCodeOf(installed)
        if (downloadedCode <= installedCode) {
            return ApkValidation.Invalid(R.string.update_verify_downgrade, "$downloadedCode <= $installedCode")
        }
        if (expectedVersionCode > 0 && downloadedCode != expectedVersionCode) {
            return ApkValidation.Invalid(
                R.string.update_verify_version_mismatch,
                "$downloadedCode != $expectedVersionCode",
            )
        }
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION") downloaded.applicationInfo?.minSdkVersion ?: 0
        } else {
            0
        }
        if (minSdk > Build.VERSION.SDK_INT) {
            return ApkValidation.Invalid(R.string.update_verify_compat, "minSdk=$minSdk")
        }
        val installedSignatures = signaturesOf(installed)
        val downloadedSignatures = signaturesOf(downloaded)
        if (installedSignatures.isEmpty() || downloadedSignatures.isEmpty() ||
            installedSignatures.intersect(downloadedSignatures).isEmpty()
        ) {
            return ApkValidation.Invalid(R.string.update_verify_signature)
        }

        return ApkValidation.Valid(
            versionName = downloaded.versionName ?: "-",
            versionCode = downloadedCode,
            packageName = downloaded.packageName.orEmpty(),
        )
    }

    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo calcular SHA-256", e)
        null
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun packageInfo(context: Context, packageNameOrPath: String): PackageInfo? {
        var flags = PackageManager.GET_META_DATA
        flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            flags or PackageManager.GET_SIGNATURES
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (packageNameOrPath.contains(File.separatorChar) || packageNameOrPath.endsWith(".apk")) {
                    context.packageManager.getPackageArchiveInfo(
                        packageNameOrPath,
                        PackageManager.PackageInfoFlags.of(flags.toLong()),
                    )
                } else {
                    context.packageManager.getPackageInfo(
                        packageNameOrPath,
                        PackageManager.PackageInfoFlags.of(flags.toLong()),
                    )
                }
            } else if (packageNameOrPath.contains(File.separatorChar) || packageNameOrPath.endsWith(".apk")) {
                context.packageManager.getPackageArchiveInfo(packageNameOrPath, flags)
            } else {
                context.packageManager.getPackageInfo(packageNameOrPath, flags)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el paquete $packageNameOrPath", e)
            null
        }
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun signaturesOf(info: PackageInfo): Set<String> {
        val arrays: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } else {
            info.signatures?.map { it.toByteArray() } ?: emptyList()
        }
        return arrays.map { array ->
            MessageDigest.getInstance("SHA-256").digest(array).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun versionCodeOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode

    private fun notifyAvailable(context: Context, info: UpdateInfo) {
        val localized = LocaleHelper.wrap(context)
        createChannel(localized)
        val intent = Intent(context, UpdateInstallActivity::class.java).apply {
            putExtra(EXTRA_APK_URL, info.apkUrl)
            putExtra(EXTRA_VERSION, info.versionName)
            putExtra(EXTRA_CODE, info.versionCode)
            putExtra(EXTRA_SIZE, info.assetSize)
            putExtra(EXTRA_SHA256, info.sha256)
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
    internal const val EXTRA_CODE = "version_code"
    internal const val EXTRA_SIZE = "asset_size"
    internal const val EXTRA_SHA256 = "sha256"
    internal const val NOTIFICATION_ID = 8002
}
