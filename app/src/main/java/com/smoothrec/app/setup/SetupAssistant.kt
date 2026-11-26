package com.smoothrec.app.setup

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.smoothrec.app.MainActivity
import com.smoothrec.app.R
import com.smoothrec.app.SettingsActivity
import com.smoothrec.app.ui.ThemeHelper

/** Host opcional: permite que el asistente abra el consentimiento de MediaProjection. */
interface SetupHost {
    fun requestScreenCaptureAuthorization()
}

/**
 * Asistente visual de primer uso. Usa solo APIs públicas: solicita permisos al
 * usuario, abre ajustes del sistema bajo consentimiento y nunca concede nada
 * automáticamente ni bloquea el uso de la app.
 */
class SetupAssistant private constructor(private val activity: Activity) {

    private enum class Step(
        val titleRes: Int,
        val descriptionRes: Int,
        val iconRes: Int,
    ) {
        MICROPHONE(
            R.string.setup_mic_title,
            R.string.setup_mic_description,
            R.drawable.ic_setup_mic,
        ),
        INTERNAL_AUDIO(
            R.string.setup_internal_title,
            R.string.setup_internal_description,
            R.drawable.ic_setup_audio,
        ),
        SCREEN_CAPTURE(
            R.string.setup_screen_title,
            R.string.setup_screen_description,
            R.drawable.ic_setup_screen,
        ),
        BATTERY(
            R.string.setup_battery_title,
            R.string.setup_battery_description,
            R.drawable.ic_setup_battery,
        ),
        NOTIFICATIONS(
            R.string.setup_notifications_title,
            R.string.setup_notifications_description,
            R.drawable.ic_setup_notification,
        ),
    }

    private enum class Status(val labelRes: Int) {
        ENABLED(R.string.setup_status_enabled),
        PENDING(R.string.setup_status_pending),
        UNAVAILABLE(R.string.setup_status_unavailable),
    }

    private data class StepState(val step: Step, val status: Status)

    private lateinit var dialog: Dialog
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var stepsContainer: LinearLayout

    private fun buildDialog() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_setup_assistant, null)
        progressBar = view.findViewById(R.id.setupProgressBar)
        progressLabel = view.findViewById(R.id.setupProgressLabel)
        stepsContainer = view.findViewById(R.id.setupStepsContainer)

        view.findViewById<Button>(R.id.setupLaterButton).setOnClickListener {
            dismissInternal(acknowledge = true)
        }
        view.findViewById<Button>(R.id.setupNowButton).setOnClickListener {
            val states = currentStates()
            val firstPending = states.firstOrNull { it.status == Status.PENDING }
            if (firstPending == null) {
                Toast.makeText(activity, R.string.setup_all_ready, Toast.LENGTH_SHORT).show()
                dismissInternal(acknowledge = true)
            } else {
                configure(firstPending.step, firstPending.status)
            }
        }

        dialog = Dialog(activity).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setContentView(view)
            // Cualquier salida explícita del recuadro cuenta como "visto".
            setOnCancelListener {
                markAcknowledged(activity)
                clearActive(this@SetupAssistant)
            }
            setOnDismissListener { clearActive(this@SetupAssistant) }
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        render()
    }

    private fun currentStates(): List<StepState> = Step.values().map { it to evaluate(it) }
        .map { (step, status) -> StepState(step, status) }

    private fun evaluate(step: Step): Status = when (step) {
        Step.MICROPHONE -> when {
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ->
                Status.UNAVAILABLE
            granted(Manifest.permission.RECORD_AUDIO) -> Status.ENABLED
            else -> Status.PENDING
        }
        Step.INTERNAL_AUDIO -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> Status.UNAVAILABLE
            !granted(Manifest.permission.RECORD_AUDIO) -> Status.PENDING
            !playbackCaptureBufferSupported() -> Status.UNAVAILABLE
            else -> Status.ENABLED
        }
        Step.SCREEN_CAPTURE ->
            if (SetupPrefs.screenCaptureAuthorized(activity)) Status.ENABLED else Status.PENDING
        Step.BATTERY -> {
            val manager = activity.getSystemService(PowerManager::class.java)
            if (manager != null && manager.isIgnoringBatteryOptimizations(activity.packageName)) {
                Status.ENABLED
            } else {
                Status.PENDING
            }
        }
        Step.NOTIFICATIONS -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> Status.ENABLED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS) -> Status.PENDING
            !activity.getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled() -> Status.PENDING
            else -> Status.ENABLED
        }
    }

    private fun granted(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Prueba pública sin permisos: máscara de SALIDA, como AOSP. */
    private fun playbackCaptureBufferSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return listOf(48_000, 44_100).any { rate ->
            AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) > 0
        }
    }

    private fun render() {
        if (!::dialog.isInitialized) return
        val states = currentStates()
        val done = states.count { it.status != Status.PENDING }
        progressBar.max = states.size
        progressBar.progress = done
        progressLabel.text = activity.getString(R.string.setup_progress, done, states.size)
        stepsContainer.removeAllViews()

        val accent = ThemeHelper.resolveColor(activity, R.attr.srAccent)
        val muted = ThemeHelper.resolveColor(activity, R.attr.srIconMuted)
        val pending = ThemeHelper.resolveColor(activity, R.attr.srPausedAccent)
        val secondary = ThemeHelper.resolveColor(activity, R.attr.srTextSecondary)

        states.forEach { state ->
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_setup_step, stepsContainer, false)
            val icon = row.findViewById<ImageView>(R.id.setupStepIcon)
            icon.setImageResource(state.step.iconRes)
            icon.setColorFilter(if (state.status == Status.PENDING) pending else accent)

            row.findViewById<TextView>(R.id.setupStepTitle).apply {
                text = activity.getString(state.step.titleRes)
            }
            row.findViewById<TextView>(R.id.setupStepDescription).apply {
                text = activity.getString(state.step.descriptionRes)
                setTextColor(secondary)
            }
            row.findViewById<TextView>(R.id.setupStepStatus).apply {
                text = activity.getString(state.status.labelRes)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    when (state.status) {
                        Status.ENABLED -> accent
                        Status.PENDING -> pending
                        Status.UNAVAILABLE -> muted
                    },
                )
            }
            row.findViewById<Button>(R.id.setupStepConfigure).setOnClickListener {
                configure(state.step, state.status)
            }
            stepsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun configure(step: Step, status: Status) {
        if (status == Status.UNAVAILABLE) {
            Toast.makeText(activity, R.string.setup_status_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        // El usuario ha aceptado el recuadro y entra en configuración: se guarda
        // ahora para que no vuelva a aparecer ni al volver ni tras una OTA.
        markAcknowledged(activity)
        when (step) {
            Step.MICROPHONE -> {
                if (status == Status.PENDING) requestPermission(Manifest.permission.RECORD_AUDIO)
                else {
                    Toast.makeText(activity, R.string.setup_status_enabled, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            Step.INTERNAL_AUDIO -> {
                if (status == Status.PENDING) {
                    requestPermission(Manifest.permission.RECORD_AUDIO)
                } else {
                    dismissForConfiguration()
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                }
            }
            Step.SCREEN_CAPTURE -> {
                dismissForConfiguration()
                if (activity is SetupHost) {
                    activity.requestScreenCaptureAuthorization()
                } else {
                    Toast.makeText(activity, R.string.setup_screen_hint, Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(activity, MainActivity::class.java))
                }
            }
            Step.BATTERY -> {
                dismissForConfiguration()
                val uri = Uri.parse("package:${activity.packageName}")
                val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
                try {
                    activity.startActivity(request)
                } catch (_: Exception) {
                    try {
                        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(activity, R.string.setup_permission_denied, Toast.LENGTH_LONG).show()
                    }
                }
            }
            Step.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !granted(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    requestPermission(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    dismissForConfiguration()
                    openNotificationSettings()
                }
            }
        }
    }

    private fun requestPermission(permission: String) {
        dismissForConfiguration()
        activity.requestPermissions(arrayOf(permission), REQUEST_SETUP_PERMISSIONS)
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}"),
            )
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.setup_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissForConfiguration() {
        if (::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
        clearActive(this)
    }

    private fun dismissInternal(acknowledge: Boolean) {
        if (acknowledge) markAcknowledged(activity)
        if (::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
        clearActive(this)
    }

    private fun showInternal() {
        if (activity.isFinishing || activity.isDestroyed) return
        buildDialog()
        dialog.show()
    }

    companion object {
        const val REQUEST_SETUP_PERMISSIONS = 1003

        private var active: SetupAssistant? = null

        private fun clearActive(instance: SetupAssistant) {
            if (active === instance) active = null
        }

        /** Único punto de entrada automático: una sola instancia y un solo flag. */
        fun maybeShow(activity: Activity, allowed: Boolean = true) {
            if (!allowed || activity.isFinishing || activity.isDestroyed) return
            active?.let { current ->
                if (!current.isHostValid()) clearActive(current)
            }
            // No se vuelve a mostrar aunque cambie una versión, un permiso o una pantalla.
            if (active != null || !shouldShow(activity)) return
            active = SetupAssistant(activity).also { it.showInternal() }
        }

        /** Apertura explícita (p. ej., desde Ajustes): respeta la instancia única. */
        fun show(activity: Activity) {
            if (activity.isFinishing || activity.isDestroyed) return
            active?.let { previous -> previous.dismissInternal(acknowledge = false) }
            active = SetupAssistant(activity).also { it.showInternal() }
        }

        fun refreshIfShowing() {
            active?.render()
        }

        /** Evita conservar un diálogo ligado a una Activity ya destruida. */
        fun onActivityDestroyed(activity: Activity) {
            active?.let { current ->
                if (current.activity === activity || !current.isHostValid()) {
                    current.dismissIfShowing()
                    clearActive(current)
                }
            }
        }

        fun onPermissionsResult(activity: Activity, requestCode: Int) {
            if (requestCode != REQUEST_SETUP_PERMISSIONS) return
            // La explicación ya fue aceptada: se conserva para siempre, sin
            // reabrir el recuadro después de conceder el permiso.
            markAcknowledged(activity)
            refreshIfShowing()
        }

        fun markScreenCaptureAuthorized(context: Context) {
            SetupPrefs.setScreenCaptureAuthorized(context, true)
        }

        fun markAcknowledged(context: Context) {
            SetupPrefs.acknowledge(context)
        }

        fun shouldShow(context: Context): Boolean = !SetupPrefs.isAcknowledged(context)
    }

    private fun isHostValid(): Boolean = !activity.isFinishing && !activity.isDestroyed

    private fun dismissIfShowing() {
        if (::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
    }
}

private object SetupPrefs {
    private const val KEY_ACKNOWLEDGED = "setup_acknowledged"
    private const val LEGACY_KEY_SIGNATURE = "ack_signature"
    private const val KEY_SCREEN_CAPTURE = "screen_capture_authorized"
    private const val FILE_NAME = "smoothrec_setup"

    fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Estado permanente e independiente de la versión. Si el usuario ya aceptó
     * el aviso en una instalación anterior, la migración conserva esa decisión
     * y evita volver a preguntar después de una actualización.
     */
    fun isAcknowledged(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.contains(KEY_ACKNOWLEDGED)) {
            return prefs.getBoolean(KEY_ACKNOWLEDGED, false)
        }
        if (prefs.contains(LEGACY_KEY_SIGNATURE)) {
            prefs.edit().putBoolean(KEY_ACKNOWLEDGED, true).apply()
            return true
        }
        return false
    }

    fun acknowledge(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACKNOWLEDGED, true).apply()
    }

    fun screenCaptureAuthorized(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCREEN_CAPTURE, false)

    fun setScreenCaptureAuthorized(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_CAPTURE, value).apply()
    }
}
