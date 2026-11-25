package com.smoothrec.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.smoothrec.app.capture.CaptureNotification
import com.smoothrec.app.capture.RecordingStore
import com.smoothrec.app.capture.RecordingTelemetry
import com.smoothrec.app.capture.ScreenCaptureService
import com.smoothrec.app.overlay.FloatingBarController
import com.smoothrec.app.recording.AppLanguageOption
import com.smoothrec.app.recording.CaptureModeOption
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.recording.ThemeModeOption
import com.smoothrec.app.ui.LocaleHelper
import com.smoothrec.app.ui.ThemeHelper
import com.smoothrec.app.updater.UpdateManager
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Pantalla principal minimalista con navegación inferior en dos secciones:
 * 1. Inicio (controles de grabación y monitor ligero del motor).
 * 2. Grabaciones (solo archivos MP4 propios de SmoothRec en Movies/SmoothRec).
 */
class MainActivity : Activity() {

    private enum class UiState { READY, REQUESTING, SESSION, RECORDING, PAUSED, STOPPED }
    private enum class Tab { HOME, RECORDINGS }

    private lateinit var homeView: View
    private lateinit var recordingsView: View
    private lateinit var navHomeIcon: ImageView
    private lateinit var navHomeLabel: TextView
    private lateinit var navRecordingsIcon: ImageView
    private lateinit var navRecordingsLabel: TextView

    private lateinit var statusDot: View
    private lateinit var stateLabel: TextView
    private lateinit var homeTimer: TextView
    private lateinit var stateHint: TextView
    private lateinit var recordButton: ImageButton
    private lateinit var activeControlsRow: View
    private lateinit var pauseResumeButton: View
    private lateinit var pauseResumeIcon: ImageView
    private lateinit var pauseResumeLabel: TextView
    private lateinit var endSessionButton: View
    private lateinit var currentSettingsValue: TextView

    private lateinit var monitorCard: View
    private lateinit var monitorResFps: TextView
    private lateinit var monitorBitrate: TextView
    private lateinit var monitorAudio: TextView
    private lateinit var monitorEncoder: TextView
    private lateinit var monitorError: TextView

    private lateinit var recordingsList: ListView
    private lateinit var recordingsEmpty: View
    private lateinit var recordingsCount: TextView

    private var uiState: UiState = UiState.READY
    private var activeTab: Tab = Tab.HOME
    private var currentSessionId: String? = null
    private var appliedThemeMode: ThemeModeOption = ThemeModeOption.AUTO
    private var appliedLanguage: AppLanguageOption = AppLanguageOption.AUTO
    private var appliedNightMask: Int = 0

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val thumbCache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
    private lateinit var recordingsAdapter: RecordingsAdapter

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(CaptureNotification.EXTRA_SESSION_ACTIVE, false)
            val savedOk = intent.getBooleanExtra(CaptureNotification.EXTRA_SAVED_OK, false)
            applyState(if (active) UiState.SESSION else UiState.STOPPED)
            if (savedOk) {
                loadRecordings()
            }
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(CaptureNotification.EXTRA_CONFIG_MESSAGE)
            if (!message.isNullOrEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val telemetry = RecordingTelemetry.fromIntent(intent)
            applyTelemetry(telemetry)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyToActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ThemeHelper.applySystemBars(this)

        val settings = RecordingPreferences.load(this)
        appliedThemeMode = settings.themeMode
        appliedLanguage = settings.language
        appliedNightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        bindViews()
        savedInstanceState?.getString(KEY_UI_STATE)?.let { saved ->
            runCatching { UiState.valueOf(saved) }.getOrNull()
        }?.let { uiState = it }
        savedInstanceState?.getString(KEY_ACTIVE_TAB)?.let { saved ->
            runCatching { Tab.valueOf(saved) }.getOrNull()
        }?.let { activeTab = it }

        UpdateManager.checkIfNeeded(this)
        applyState(uiState)
        selectTab(activeTab, animate = false)
    }

    private fun bindViews() {
        homeView = findViewById(R.id.homeView)
        recordingsView = findViewById(R.id.recordingsView)
        navHomeIcon = findViewById(R.id.navHomeIcon)
        navHomeLabel = findViewById(R.id.navHomeLabel)
        navRecordingsIcon = findViewById(R.id.navRecordingsIcon)
        navRecordingsLabel = findViewById(R.id.navRecordingsLabel)

        statusDot = findViewById(R.id.statusDot)
        stateLabel = findViewById(R.id.stateLabel)
        homeTimer = findViewById(R.id.homeTimer)
        stateHint = findViewById(R.id.stateHint)
        recordButton = findViewById(R.id.recordButton)
        activeControlsRow = findViewById(R.id.activeControlsRow)
        pauseResumeButton = findViewById(R.id.pauseResumeButton)
        pauseResumeIcon = findViewById(R.id.pauseResumeIcon)
        pauseResumeLabel = findViewById(R.id.pauseResumeLabel)
        endSessionButton = findViewById(R.id.endSessionButton)
        currentSettingsValue = findViewById(R.id.currentSettingsValue)

        monitorCard = findViewById(R.id.monitorCard)
        monitorResFps = findViewById(R.id.monitorResFps)
        monitorBitrate = findViewById(R.id.monitorBitrate)
        monitorAudio = findViewById(R.id.monitorAudio)
        monitorEncoder = findViewById(R.id.monitorEncoder)
        monitorError = findViewById(R.id.monitorError)

        recordingsList = findViewById(R.id.recordingsList)
        recordingsEmpty = findViewById(R.id.recordingsEmpty)
        recordingsCount = findViewById(R.id.recordingsCount)

        recordingsAdapter = RecordingsAdapter()
        recordingsList.adapter = recordingsAdapter

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.profileSummaryCard).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        recordButton.setOnClickListener { onRecordButtonClicked() }
        pauseResumeButton.setOnClickListener { onPauseResumeClicked() }
        endSessionButton.setOnClickListener { endSession() }

        findViewById<View>(R.id.navHomeTab).setOnClickListener { selectTab(Tab.HOME, animate = true) }
        findViewById<View>(R.id.navRecordingsTab).setOnClickListener {
            selectTab(Tab.RECORDINGS, animate = true)
            loadRecordings()
        }
    }

    override fun onStart() {
        super.onStart()
        val stoppedFilter = IntentFilter(CaptureNotification.ACTION_CAPTURE_STOPPED)
        val configFilter = IntentFilter(CaptureNotification.ACTION_CONFIG_APPLIED)
        val telemetryFilter = IntentFilter(RecordingTelemetry.ACTION_RECORDING_STATS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, stoppedFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(configReceiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(telemetryReceiver, telemetryFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stoppedReceiver, stoppedFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(configReceiver, configFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(telemetryReceiver, telemetryFilter)
        }
        refreshSettingsLabel()
    }

    override fun onResume() {
        super.onResume()
        val current = RecordingPreferences.load(this)
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (current.themeMode != appliedThemeMode ||
            current.language != appliedLanguage ||
            (current.themeMode == ThemeModeOption.AUTO && nightMask != appliedNightMask)
        ) {
            recreate()
            return
        }
        if (activeTab == Tab.RECORDINGS) {
            loadRecordings()
        }
    }

    override fun onStop() {
        unregisterReceiver(stoppedReceiver)
        unregisterReceiver(configReceiver)
        unregisterReceiver(telemetryReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        thumbExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_UI_STATE, uiState.name)
        outState.putString(KEY_ACTIVE_TAB, activeTab.name)
        super.onSaveInstanceState(outState)
    }

    private fun refreshSettingsLabel() {
        val settings = RecordingPreferences.load(this)
        currentSettingsValue.text = getString(
            R.string.current_settings_format,
            getString(settings.resolution.labelRes),
            settings.fps.value,
            getString(settings.videoQuality.labelRes),
        )
    }

    private fun selectTab(tab: Tab, animate: Boolean) {
        activeTab = tab
        val accent = ThemeHelper.resolveColor(this, R.attr.srAccent)
        val muted = ThemeHelper.resolveColor(this, R.attr.srIconMuted)

        if (tab == Tab.HOME) {
            navHomeIcon.setColorFilter(accent)
            navHomeLabel.setTextColor(accent)
            navRecordingsIcon.setColorFilter(muted)
            navRecordingsLabel.setTextColor(muted)
            switchViews(show = homeView, hide = recordingsView, animate = animate)
        } else {
            navHomeIcon.setColorFilter(muted)
            navHomeLabel.setTextColor(muted)
            navRecordingsIcon.setColorFilter(accent)
            navRecordingsLabel.setTextColor(accent)
            switchViews(show = recordingsView, hide = homeView, animate = animate)
        }
    }

    private fun switchViews(show: View, hide: View, animate: Boolean) {
        hide.visibility = View.GONE
        show.visibility = View.VISIBLE
        if (animate) {
            show.alpha = 0f
            show.animate().alpha(1f).setDuration(160L).start()
        } else {
            show.alpha = 1f
        }
    }

    private fun onRecordButtonClicked() {
        when (uiState) {
            UiState.READY, UiState.STOPPED -> requestPermissionsWithRationale()
            UiState.REQUESTING -> Unit
            UiState.SESSION -> {
                startService(
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_START_RECORDING
                    },
                )
                applyState(UiState.RECORDING)
            }
            UiState.RECORDING, UiState.PAUSED -> {
                startService(
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = CaptureNotification.ACTION_STOP
                    },
                )
                applyState(UiState.SESSION)
            }
        }
    }

    private fun onPauseResumeClicked() {
        when (uiState) {
            UiState.RECORDING -> {
                startService(
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = CaptureNotification.ACTION_PAUSE
                    },
                )
                applyState(UiState.PAUSED)
            }
            UiState.PAUSED -> {
                startService(
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = CaptureNotification.ACTION_RESUME
                    },
                )
                applyState(UiState.RECORDING)
            }
            else -> Unit
        }
    }

    private fun endSession() {
        startService(
            Intent(this, ScreenCaptureService::class.java).apply {
                action = CaptureNotification.ACTION_END_SESSION
            },
        )
        applyState(UiState.STOPPED)
    }

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return needed
    }

    private fun requestPermissionsWithRationale() {
        val needed = missingPermissions()
        if (needed.isEmpty()) {
            if (ensureOverlayPermission()) launchProjectionConsent()
            return
        }
        val shouldExplain = needed.any { shouldShowRequestPermissionRationale(it) }
        if (shouldExplain) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_body)
                .setPositiveButton(R.string.permission_rationale_continue) { _, _ ->
                    requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        } else {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        Toast.makeText(this, R.string.overlay_permission_hint, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            Toast.makeText(this, R.string.audio_permission_denied, Toast.LENGTH_LONG).show()
            applyState(UiState.STOPPED)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
        }
        if (ensureOverlayPermission()) launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        applyState(UiState.REQUESTING)
        currentSessionId = UUID.randomUUID().toString()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val singleApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            RecordingPreferences.load(this).captureMode == CaptureModeOption.SINGLE_APP
        val consent = if (singleApp) {
            // Android 14+: elegir una sola app limita la captura a esa app.
            // La barra se desmonta al grabar, así que no aparece en ningún modo.
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice(),
            )
        } else {
            @Suppress("DEPRECATION")
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(consent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            applyState(UiState.STOPPED)
            return
        }

        applyState(UiState.SESSION)
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SESSION_ID, currentSessionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun applyTelemetry(telemetry: RecordingTelemetry) {
        if (telemetry.paused && uiState == UiState.RECORDING) {
            applyState(UiState.PAUSED)
        } else if (!telemetry.paused && uiState != UiState.RECORDING &&
            telemetry.encoderState == RecordingTelemetry.STATE_RUNNING
        ) {
            applyState(UiState.RECORDING)
        }

        homeTimer.text = FloatingBarController.formatTime(telemetry.elapsedActiveMs)
        monitorCard.visibility = View.VISIBLE
        monitorResFps.text = getString(
            R.string.monitor_res_fps,
            "${telemetry.width}×${telemetry.height}",
            telemetry.effectiveFps,
            telemetry.configuredFps,
        )
        val mbps = String.format(Locale.US, "%.1f", telemetry.bitrateBps / 1_000_000.0)
        monitorBitrate.text = getString(R.string.monitor_bitrate, mbps)

        monitorAudio.text = when {
            telemetry.audioSilent -> getString(R.string.monitor_audio_silent)
            telemetry.audioActive -> getString(
                R.string.monitor_audio_active,
                getString(telemetry.audioSource.labelRes),
            )
            else -> getString(R.string.monitor_audio_off)
        }
        monitorEncoder.text = when (telemetry.encoderState) {
            RecordingTelemetry.STATE_RUNNING -> getString(R.string.monitor_encoder_running)
            RecordingTelemetry.STATE_PAUSED -> getString(R.string.monitor_encoder_paused)
            RecordingTelemetry.STATE_ERROR -> getString(R.string.monitor_encoder_error)
            else -> getString(R.string.monitor_encoder_ready)
        }
        if (!telemetry.lastError.isNullOrBlank()) {
            monitorError.visibility = View.VISIBLE
            monitorError.text = telemetry.lastError
        } else {
            monitorError.visibility = View.GONE
        }
    }

    private fun applyState(state: UiState) {
        uiState = state
        val textPrimary = ThemeHelper.resolveColor(this, R.attr.srTextPrimary)
        val recordingAccent = ThemeHelper.resolveColor(this, R.attr.srRecordingAccent)
        val pausedAccent = ThemeHelper.resolveColor(this, R.attr.srPausedAccent)

        when (state) {
            UiState.READY -> {
                stateLabel.setText(R.string.state_ready)
                stateLabel.setTextColor(textPrimary)
                stateHint.setText(R.string.hint_ready)
                statusDot.setBackgroundResource(R.drawable.fab_background)
                recordButton.setImageResource(R.drawable.ic_record)
                recordButton.setBackgroundResource(R.drawable.fab_background)
                recordButton.contentDescription = getString(R.string.record_start_description)
                homeTimer.visibility = View.GONE
                activeControlsRow.visibility = View.GONE
                monitorCard.visibility = View.GONE
            }
            UiState.REQUESTING -> {
                stateLabel.setText(R.string.state_requesting)
                stateLabel.setTextColor(textPrimary)
                stateHint.setText(R.string.hint_requesting)
                statusDot.setBackgroundResource(R.drawable.fab_background)
                recordButton.setImageResource(R.drawable.ic_record)
                recordButton.setBackgroundResource(R.drawable.fab_background)
                homeTimer.visibility = View.GONE
                activeControlsRow.visibility = View.GONE
                monitorCard.visibility = View.GONE
            }
            UiState.SESSION -> {
                stateLabel.setText(R.string.state_session)
                stateLabel.setTextColor(textPrimary)
                stateHint.setText(R.string.hint_session)
                statusDot.setBackgroundResource(R.drawable.fab_background)
                recordButton.setImageResource(R.drawable.ic_record)
                recordButton.setBackgroundResource(R.drawable.fab_background)
                recordButton.contentDescription = getString(R.string.action_start_recording)
                homeTimer.visibility = View.GONE
                activeControlsRow.visibility = View.VISIBLE
                pauseResumeButton.visibility = View.GONE
                endSessionButton.visibility = View.VISIBLE
                monitorCard.visibility = View.GONE
            }
            UiState.RECORDING -> {
                stateLabel.setText(R.string.state_recording)
                stateLabel.setTextColor(recordingAccent)
                stateHint.setText(R.string.hint_recording)
                statusDot.setBackgroundResource(R.drawable.fab_background_stop)
                recordButton.setImageResource(R.drawable.ic_stop)
                recordButton.setBackgroundResource(R.drawable.fab_background_stop)
                recordButton.contentDescription = getString(R.string.record_stop_description)
                homeTimer.visibility = View.VISIBLE
                activeControlsRow.visibility = View.VISIBLE
                pauseResumeButton.visibility = View.VISIBLE
                pauseResumeIcon.setImageResource(R.drawable.ic_pause)
                pauseResumeLabel.setText(R.string.action_pause)
                endSessionButton.visibility = View.VISIBLE
            }
            UiState.PAUSED -> {
                stateLabel.setText(R.string.state_paused)
                stateLabel.setTextColor(pausedAccent)
                stateHint.setText(R.string.hint_paused)
                statusDot.setBackgroundResource(R.drawable.fab_background)
                recordButton.setImageResource(R.drawable.ic_stop)
                recordButton.setBackgroundResource(R.drawable.fab_background_stop)
                recordButton.contentDescription = getString(R.string.record_stop_description)
                homeTimer.visibility = View.VISIBLE
                activeControlsRow.visibility = View.VISIBLE
                pauseResumeButton.visibility = View.VISIBLE
                pauseResumeIcon.setImageResource(R.drawable.ic_play)
                pauseResumeLabel.setText(R.string.action_resume)
                endSessionButton.visibility = View.VISIBLE
            }
            UiState.STOPPED -> {
                stateLabel.setText(R.string.state_stopped)
                stateLabel.setTextColor(textPrimary)
                stateHint.setText(R.string.hint_stopped)
                statusDot.setBackgroundResource(R.drawable.fab_background)
                recordButton.setImageResource(R.drawable.ic_record)
                recordButton.setBackgroundResource(R.drawable.fab_background)
                recordButton.contentDescription = getString(R.string.record_start_description)
                homeTimer.visibility = View.GONE
                activeControlsRow.visibility = View.GONE
                monitorCard.visibility = View.GONE
            }
        }

        recordButton.isEnabled = state != UiState.REQUESTING
        recordButton.alpha = if (state == UiState.REQUESTING) 0.6f else 1f
        recordButton.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
    }

    private fun loadRecordings() {
        ioExecutor.execute {
            val items = RecordingStore.listRecordings(applicationContext)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                recordingsAdapter.submit(items)
                recordingsCount.text = getString(R.string.recordings_count, items.size)
                val empty = items.isEmpty()
                recordingsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                recordingsList.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    private fun playRecording(item: RecordingStore.RecordingItem) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.recording_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecording(item: RecordingStore.RecordingItem) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.recording_share_chooser)))
    }

    private fun confirmDeleteRecording(item: RecordingStore.RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.recording_delete_title)
            .setMessage(getString(R.string.recording_delete_message, item.displayName))
            .setPositiveButton(R.string.recording_delete) { _, _ ->
                ioExecutor.execute {
                    val ok = RecordingStore.deleteRecording(applicationContext, item)
                    if (ok) thumbCache.remove(item.id)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) R.string.recording_deleted else R.string.recording_delete_error,
                            Toast.LENGTH_SHORT,
                        ).show()
                        loadRecordings()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private inner class RecordingsAdapter : BaseAdapter() {
        private val items = ArrayList<RecordingStore.RecordingItem>()

        fun submit(newItems: List<RecordingStore.RecordingItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RecordingStore.RecordingItem = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: Holder
            if (convertView == null) {
                view = LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_recording, parent, false)
                holder = Holder(
                    thumb = view.findViewById(R.id.recordingThumb),
                    durationBadge = view.findViewById(R.id.recordingDurationBadge),
                    name = view.findViewById(R.id.recordingName),
                    meta = view.findViewById(R.id.recordingMeta),
                    date = view.findViewById(R.id.recordingDate),
                    playBtn = view.findViewById(R.id.recordingPlayBtn),
                    shareBtn = view.findViewById(R.id.recordingShareBtn),
                    deleteBtn = view.findViewById(R.id.recordingDeleteBtn),
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as Holder
            }

            val item = getItem(position)
            holder.boundId = item.id
            holder.name.text = item.displayName
            val durationStr = formatDuration(item.durationMs)
            holder.durationBadge.text = durationStr

            val resStr = if (item.width > 0 && item.height > 0) "${item.width}×${item.height}" else "MP4"
            val sizeMb = String.format(Locale.US, "%.1f MB", item.sizeBytes / (1024.0 * 1024.0))
            holder.meta.text = getString(R.string.recording_meta_format, resStr, durationStr, sizeMb)

            val dateStr = if (item.dateAddedSec > 0L) {
                val date = Date(item.dateAddedSec * 1000L)
                "${DateFormat.getMediumDateFormat(this@MainActivity).format(date)} · " +
                    DateFormat.getTimeFormat(this@MainActivity).format(date)
            } else {
                "-"
            }
            holder.date.text = dateStr

            val cached = thumbCache.get(item.id)
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageDrawable(null)
                thumbExecutor.execute {
                    val bmp = RecordingStore.loadThumbnail(applicationContext, item)
                    if (bmp != null) {
                        thumbCache.put(item.id, bmp)
                        holder.thumb.post {
                            if (holder.boundId == item.id) {
                                holder.thumb.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }

            view.setOnClickListener { playRecording(item) }
            holder.playBtn.setOnClickListener { playRecording(item) }
            holder.shareBtn.setOnClickListener { shareRecording(item) }
            holder.deleteBtn.setOnClickListener { confirmDeleteRecording(item) }

            return view
        }

        private fun formatDuration(ms: Long): String {
            val total = (ms / 1000L).toInt().coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private class Holder(
        val thumb: ImageView,
        val durationBadge: TextView,
        val name: TextView,
        val meta: TextView,
        val date: TextView,
        val playBtn: ImageButton,
        val shareBtn: ImageButton,
        val deleteBtn: ImageButton,
        var boundId: Long = -1L,
    )

    private companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_PERMISSIONS = 1002
        const val KEY_UI_STATE = "ui_state"
        const val KEY_ACTIVE_TAB = "active_tab"
    }
}
