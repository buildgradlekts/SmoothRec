package com.smoothrec.app.updater

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.smoothrec.app.R
import com.smoothrec.app.ui.ThemeHelper
import java.io.File
import kotlin.math.min

/** Ventana inferior de actualización: aviso, descarga con progreso e instalación. */
class UpdatePromptDialog(
    private val activity: Activity,
    private val info: UpdateManager.UpdateInfo,
    private val startReady: Boolean = false,
) {
    private val dialog = Dialog(activity)
    private var downloader: UpdateDownloader? = null
    private var pendingPermission = false

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val bgColor = TypedValue().let {
        activity.theme.resolveAttribute(android.R.attr.colorBackground, it, true); it.data
    }
    private val textPrimary = ThemeHelper.resolveColor(activity, R.attr.srTextPrimary)
    private val textSecondary = ThemeHelper.resolveColor(activity, R.attr.srTextSecondary)
    private val accent = ThemeHelper.resolveColor(activity, R.attr.srAccent)

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var actions: LinearLayout

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildContent())
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.6f)
        }
        dialog.setOnDismissListener { downloader?.cancel() }
        if (startReady) showReady() else showPrompt()
        dialog.show()
    }

    /** Llamar desde Activity.onResume: continúa si el usuario acaba de dar el permiso. */
    fun onHostResumed() {
        if (pendingPermission && canInstall() && dialog.isShowing) {
            pendingPermission = false
            verifyThenInstall() // el APK ya está descargado
        }
    }

    fun isShowing(): Boolean = dialog.isShowing

    fun dismissQuietly() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(bgColor)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(activity).apply {
            setImageDrawable(activity.packageManager.getApplicationIcon(activity.packageName))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
        }
        titleView = TextView(activity).apply {
            textSize = 21f
            setTextColor(textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        subtitleView = TextView(activity).apply { textSize = 14f; setTextColor(textSecondary) }
        texts.addView(titleView); texts.addView(subtitleView)
        header.addView(icon); header.addView(texts)
        root.addView(header)

        val cleaned = cleanNotes(info.notes)
        if (cleaned.isNotEmpty()) {
            val notes = TextView(activity).apply {
                text = cleaned
                textSize = 15f
                setTextColor(textPrimary)
                setLineSpacing(0f, 1.15f)
            }
            val scroll = ScrollView(activity)
            scroll.addView(notes)
            val widthPx = activity.resources.displayMetrics.widthPixels - dp(40)
            notes.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.4f).toInt()
            root.addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, min(notes.measuredHeight, maxH))
                    .apply { topMargin = dp(16) },
            )
        }

        actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            actions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) },
        )
        return root
    }

    // ---------- Estados ----------

    private fun showPrompt() {
        dialog.setCancelable(true)
        titleView.setText(R.string.update_sheet_title)
        val size = if (info.assetSize > 0) " · ${UpdateManager.formatBytes(info.assetSize)}" else ""
        subtitleView.text = "${appName()} ${info.versionName}$size"
        actions.removeAllViews()
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(textButton(R.string.update_sheet_skip) {
            UpdateManager.skipVersion(activity, info.versionName)
            dialog.dismiss()
        })
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(textButton(R.string.update_sheet_later) { dialog.dismiss() })
        row.addView(pill(R.string.update_sheet_update) { onUpdateClicked() })
        actions.addView(row)
    }

    private fun showDownloading() {
        dialog.setCancelable(false)
        titleView.setText(R.string.update_sheet_downloading)
        subtitleView.text = "${appName()} ${info.versionName}"
        actions.removeAllViews()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; isIndeterminate = false
            progressTintList = ColorStateList.valueOf(accent)
            tag = "bar"
        }
        val label = TextView(activity).apply {
            textSize = 14f; setTextColor(textSecondary); tag = "label"; text = "0 B"
        }
        actions.addView(bar)
        actions.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val row = LinearLayout(activity).apply { gravity = Gravity.END }
        row.addView(textButton(R.string.common_cancel) {
            downloader?.cancel()
            dialog.dismiss()
        })
        actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun showStatus(messageRes: Int) {
        (actions.findViewWithTag<TextView>("label"))?.setText(messageRes)
    }

    private fun showError(messageRes: Int, detail: String?) {
        dialog.setCancelable(true)
        actions.removeAllViews()
        val text = runCatching { activity.getString(messageRes, detail.orEmpty()) }
            .getOrElse { activity.getString(messageRes) }
        actions.addView(TextView(activity).apply {
            this.text = text; textSize = 14f; setTextColor(textPrimary)
        })
        val row = LinearLayout(activity).apply { gravity = Gravity.END }
        row.addView(textButton(R.string.update_sheet_later) { dialog.dismiss() })
        row.addView(pill(R.string.update_retry) { onUpdateClicked() })
        actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    // ---------- Acciones ----------

    private fun onUpdateClicked() {
        // Se descarga primero; el permiso se pide al final, como en MeeTube.
        val ready = UpdateFileProvider.readMeta(activity)?.let {
            it.state == UpdateFileProvider.STATE_FINAL &&
                UpdateFileProvider.fileFor(activity).exists()
        } == true
        if (ready) verifyThenInstall() else startDownload()
    }

    private fun showPermissionStep() {
        dialog.setCancelable(true)
        titleView.text = activity.getString(R.string.update_perm_title, appName())
        subtitleView.text = "${appName()} ${info.versionName}"
        actions.removeAllViews()
        actions.addView(TextView(activity).apply {
            setText(R.string.update_perm_body); textSize = 15f; setTextColor(textPrimary)
            setLineSpacing(0f, 1.15f)
        })
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(textButton(R.string.update_perm_not_now) { dialog.dismiss() })
        row.addView(pill(R.string.update_perm_open) { openUnknownSourcesSettings() })
        actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    private fun openUnknownSourcesSettings() {
        pendingPermission = true
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
        } catch (_: Exception) {
            pendingPermission = false
            showError(R.string.update_allow_install, null)
        }
    }

    /** Modo «Actualización lista para instalar» (APK ya descargado). */
    private fun showReady() {
        dialog.setCancelable(true)
        titleView.setText(R.string.update_ready_title)
        subtitleView.text = "${appName()} ${info.versionName}"
        actions.removeAllViews()
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(textButton(R.string.update_sheet_later) { dialog.dismiss() })
        row.addView(pill(R.string.update_install) { verifyThenInstall() })
        actions.addView(row)
    }

    /** Verifica el APK guardado (hash, firma, versión) fuera del hilo principal y lo instala. */
    private fun verifyThenInstall() {
        titleView.setText(R.string.update_sheet_downloading)
        actions.removeAllViews()
        actions.addView(TextView(activity).apply {
            setText(R.string.update_verifying); textSize = 14f; setTextColor(textSecondary)
        })
        Thread {
            val file = UpdateFileProvider.fileFor(activity)
            val sha = UpdateFileProvider.readMeta(activity)?.sha256 ?: info.sha256
            val result = if (file.exists()) {
                UpdateManager.verifyDownloadedApk(activity, file, info.versionCode, sha)
            } else null
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                when (result) {
                    is UpdateManager.ApkValidation.Valid ->
                        if (canInstall()) install() else showPermissionStep()
                    is UpdateManager.ApkValidation.Invalid -> {
                        file.delete(); UpdateFileProvider.deleteMeta(activity)
                        showError(result.messageRes, result.detail)
                    }
                    null -> startDownload() // el archivo ya no existe: volver a descargar
                }
            }
        }.start()
    }

    private fun startDownload() {
        showDownloading()
        val d = UpdateDownloader(activity, info)
        downloader = d
        d.start(object : UpdateDownloader.Listener {
            override fun onProgress(downloaded: Long, total: Long) {
                if (!dialog.isShowing) return
                actions.findViewWithTag<ProgressBar>("bar")?.progress =
                    if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
                actions.findViewWithTag<TextView>("label")?.text = activity.getString(
                    R.string.update_sheet_progress,
                    UpdateManager.formatBytes(downloaded),
                    if (total > 0) UpdateManager.formatBytes(total) else "—",
                )
            }
            override fun onVerifying() { if (dialog.isShowing) showStatus(R.string.update_verifying) }
            override fun onReady(file: File) {
                if (!dialog.isShowing) return
                if (canInstall()) install() else showPermissionStep()
            }
            override fun onError(messageRes: Int, detail: String?) {
                if (dialog.isShowing) showError(messageRes, detail)
            }
        })
    }

    private fun install() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(UpdateFileProvider.uriFor(activity), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
            dialog.dismiss()
        } catch (e: Exception) {
            showError(R.string.update_error, e.message)
        }
    }

    private fun canInstall() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    // ---------- Utilidades de interfaz ----------

    private fun appName() = activity.applicationInfo.loadLabel(activity.packageManager).toString()

    private fun textButton(res: Int, onClick: () -> Unit) = TextView(activity).apply {
        setText(res); textSize = 14f; setTextColor(textPrimary)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        setOnClickListener { onClick() }
    }

    private fun pill(res: Int, onClick: () -> Unit) = TextView(activity).apply {
        setText(res); textSize = 16f; setTextColor(bgColor)
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        background = GradientDrawable().apply { setColor(textPrimary); cornerRadius = dp(28).toFloat() }
        setOnClickListener { onClick() }
    }

    /** Quita markdown y líneas técnicas (hash, firma) de las notas del release. */
    private fun cleanNotes(raw: String): String =
        raw.lines()
            .filterNot { it.contains("sha-256", true) || it.contains("sha256", true) || it.startsWith("Firma", true) }
            .joinToString("\n") {
                it.trim().trimStart('#', ' ').replace("**", "")
                    .replace(Regex("^[-*]\\s+"), "• ")
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
