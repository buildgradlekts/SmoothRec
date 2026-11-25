package com.smoothrec.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.smoothrec.app.capture.ScreenCaptureService
import com.smoothrec.app.encoder.CaptureSizeResolver
import com.smoothrec.app.recording.AppLanguageOption
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.CaptureModeOption
import com.smoothrec.app.recording.ChannelOption
import com.smoothrec.app.recording.DurationLimitOption
import com.smoothrec.app.recording.FpsOption
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.recording.RecordingSettings
import com.smoothrec.app.recording.ResolutionOption
import com.smoothrec.app.recording.ThemeModeOption
import com.smoothrec.app.recording.VideoQualityOption
import com.smoothrec.app.ui.LocaleHelper
import com.smoothrec.app.ui.ThemeHelper
import com.smoothrec.app.updater.UpdateManager

/**
 * Ajustes de SmoothRec reorganizados en el orden exacto:
 * 1. Calidad de vídeo · 2. Resolución · 3. FPS · 4. Audio ·
 * 5. Marca de agua · (Apariencia, idioma y bolita) ·
 * 6. Buscar actualizaciones · 7. Créditos.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: RecordingSettings
    private var pendingUpdate: UpdateManager.UpdateInfo? = null

    private val qualityButtons: Map<VideoQualityOption, Button> by lazy {
        mapOf(
            VideoQualityOption.AUTO to findViewById(R.id.qualityAutoButton),
            VideoQualityOption.LOW to findViewById(R.id.qualityLowButton),
            VideoQualityOption.MEDIUM to findViewById(R.id.qualityMediumButton),
            VideoQualityOption.HIGH to findViewById(R.id.qualityHighButton),
            VideoQualityOption.ULTRA to findViewById(R.id.qualityUltraButton),
            VideoQualityOption.MAX to findViewById(R.id.qualityMaxButton),
        )
    }

    private val durationButtons: Map<DurationLimitOption, Button> by lazy {
        mapOf(
            DurationLimitOption.UNLIMITED to findViewById(R.id.durationUnlimitedButton),
            DurationLimitOption.MIN_5 to findViewById(R.id.duration5mButton),
            DurationLimitOption.MIN_15 to findViewById(R.id.duration15mButton),
            DurationLimitOption.MIN_30 to findViewById(R.id.duration30mButton),
            DurationLimitOption.MIN_60 to findViewById(R.id.duration60mButton),
        )
    }

    private val resolutionButtons: Map<ResolutionOption, Button> by lazy {
        mapOf(
            ResolutionOption.SCREEN to findViewById(R.id.resolutionScreenButton),
            ResolutionOption.P1080 to findViewById(R.id.resolution1080Button),
            ResolutionOption.P720 to findViewById(R.id.resolution720Button),
        )
    }

    private val fpsButtons: Map<FpsOption, Button> by lazy {
        mapOf(
            FpsOption.FPS_30 to findViewById(R.id.fps30Button),
            FpsOption.FPS_60 to findViewById(R.id.fps60Button),
            FpsOption.FPS_90 to findViewById(R.id.fps90Button),
            FpsOption.FPS_120 to findViewById(R.id.fps120Button),
        )
    }

    private val audioButtons: Map<AudioSourceOption, Button> by lazy {
        mapOf(
            AudioSourceOption.INTERNAL to findViewById(R.id.audioInternalButton),
            AudioSourceOption.MIC to findViewById(R.id.audioMicButton),
            AudioSourceOption.MIXED to findViewById(R.id.audioMixedButton),
        )
    }

    private val captureModeButtons: Map<CaptureModeOption, Button> by lazy {
        mapOf(
            CaptureModeOption.SINGLE_APP to findViewById(R.id.captureSingleAppButton),
            CaptureModeOption.FULL_SCREEN to findViewById(R.id.captureFullScreenButton),
        )
    }

    private val channelButtons: Map<ChannelOption, Button> by lazy {
        mapOf(
            ChannelOption.MONO to findViewById(R.id.channelMonoButton),
            ChannelOption.STEREO to findViewById(R.id.channelStereoButton),
        )
    }

    private val themeButtons: Map<ThemeModeOption, Button> by lazy {
        mapOf(
            ThemeModeOption.AUTO to findViewById(R.id.themeAutoButton),
            ThemeModeOption.DARK to findViewById(R.id.themeDarkButton),
            ThemeModeOption.LIGHT to findViewById(R.id.themeLightButton),
        )
    }

    private val languageButtons: Map<AppLanguageOption, Button> by lazy {
        mapOf(
            AppLanguageOption.AUTO to findViewById(R.id.langAutoButton),
            AppLanguageOption.SPANISH to findViewById(R.id.langEsButton),
            AppLanguageOption.ENGLISH to findViewById(R.id.langEnButton),
            AppLanguageOption.CHINESE to findViewById(R.id.langZhButton),
            AppLanguageOption.HINDI to findViewById(R.id.langHiButton),
            AppLanguageOption.ARABIC to findViewById(R.id.langArButton),
        )
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyToActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeHelper.applySystemBars(this)

        settings = RecordingPreferences.load(this)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        bindVideoSettings()
        bindDeviceCapabilities()
        bindCaptureMode()
        bindAudioSettings()
        bindWatermarkSetting()
        bindAppearanceAndBubble()
        bindUpdatesSection()
        bindCreditsSection()

        refreshSelection()
    }

    private fun bindVideoSettings() {
        qualityButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(videoQuality = option)
                persistAndRefresh()
            }
        }
        durationButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(durationLimit = option)
                persistAndRefresh()
            }
        }
        resolutionButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(resolution = option)
                persistAndRefresh()
            }
        }
        fpsButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(fps = option)
                persistAndRefresh()
            }
        }
    }

    private fun bindDeviceCapabilities() {
        val supportedFps = CaptureSizeResolver.supportedFpsOptions(this)
        fpsButtons[FpsOption.FPS_90]?.visibility =
            if (FpsOption.FPS_90 in supportedFps) View.VISIBLE else View.GONE
        fpsButtons[FpsOption.FPS_120]?.visibility =
            if (FpsOption.FPS_120 in supportedFps) View.VISIBLE else View.GONE
        if (settings.fps !in supportedFps) {
            settings = settings.copy(fps = supportedFps.lastOrNull() ?: FpsOption.FPS_30)
            RecordingPreferences.save(this, settings)
        }

        val supportedRes = CaptureSizeResolver.supportedResolutions(this)
        resolutionButtons.forEach { (option, button) ->
            val available = option in supportedRes
            button.isEnabled = available
            button.alpha = if (available) 1f else 0.4f
        }
    }

    private fun bindCaptureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            settings = settings.copy(captureMode = CaptureModeOption.FULL_SCREEN)
            RecordingPreferences.save(this, settings)
            captureModeButtons[CaptureModeOption.SINGLE_APP]?.isEnabled = false
            findViewById<TextView>(R.id.captureModeHint).setText(R.string.capture_mode_not_supported)
        }
        captureModeButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(captureMode = option)
                persistAndRefresh()
            }
        }
    }

    private fun bindAudioSettings() {
        audioButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(audioSource = option)
                persistAndRefresh()
            }
        }
        channelButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(channels = option)
                persistAndRefresh()
            }
        }
    }

    private fun bindWatermarkSetting() {
        val watermarkSwitch = findViewById<Switch>(R.id.watermarkSwitch)
        watermarkSwitch.isChecked = settings.watermarkEnabled
        watermarkSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(watermarkEnabled = isChecked)
            RecordingPreferences.save(this, settings)
        }
    }

    private fun bindAppearanceAndBubble() {
        themeButtons.forEach { (option, button) ->
            button.setOnClickListener {
                if (settings.themeMode == option) return@setOnClickListener
                settings = settings.copy(themeMode = option)
                RecordingPreferences.save(this, settings)
                recreate()
            }
        }
        languageButtons.forEach { (option, button) ->
            button.setOnClickListener {
                if (settings.language == option) return@setOnClickListener
                settings = settings.copy(language = option)
                RecordingPreferences.save(this, settings)
                recreate()
            }
        }

        val opacityLabel = findViewById<TextView>(R.id.bubbleOpacityLabel)
        val opacitySeek = findViewById<SeekBar>(R.id.bubbleOpacitySeek)
        val initialPercent = settings.bubbleOpacityPercent.coerceIn(25, 100)
        opacityLabel.text = getString(R.string.settings_bubble_opacity, initialPercent)
        opacitySeek.progress = initialPercent - 25
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = (progress + 25).coerceIn(25, 100)
                opacityLabel.text = getString(R.string.settings_bubble_opacity, percent)
                if (fromUser) {
                    settings = settings.copy(bubbleOpacityPercent = percent)
                    RecordingPreferences.save(this@SettingsActivity, settings)
                    notifyOverlayOpacity(percent)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_permission_granted, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    private fun notifyOverlayOpacity(percent: Int) {
        runCatching {
            startService(
                Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_UPDATE_BUBBLE_OPACITY
                    putExtra(ScreenCaptureService.EXTRA_BUBBLE_OPACITY, percent)
                },
            )
        }
    }

    private fun bindUpdatesSection() {
        val (verName, verCode) = UpdateManager.installedVersion(this)
        findViewById<TextView>(R.id.installedVersionText).text =
            getString(R.string.update_installed_version, verName, verCode)

        val statusView = findViewById<TextView>(R.id.updateStatus)
        val checkBtn = findViewById<Button>(R.id.updateCheckButton)
        val installBtn = findViewById<Button>(R.id.updateInstallButton)

        checkBtn.setOnClickListener {
            pendingUpdate = null
            installBtn.visibility = View.GONE
            statusView.visibility = View.VISIBLE
            statusView.setText(R.string.update_checking)
            checkBtn.isEnabled = false

            UpdateManager.checkDetailed(this) { result ->
                checkBtn.isEnabled = true
                when (result) {
                    is UpdateManager.CheckResult.NoReleases -> {
                        statusView.setText(R.string.update_none)
                    }
                    is UpdateManager.CheckResult.UpToDate -> {
                        statusView.setText(R.string.update_current)
                    }
                    is UpdateManager.CheckResult.Error -> {
                        statusView.setText(R.string.update_error)
                    }
                    is UpdateManager.CheckResult.Available -> {
                        pendingUpdate = result.info
                        statusView.text = getString(R.string.update_found, result.info.versionName)
                        installBtn.visibility = View.VISIBLE
                    }
                }
            }
        }

        installBtn.setOnClickListener {
            val info = pendingUpdate ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_download_confirm_title, info.versionName))
                .setMessage(info.notes)
                .setPositiveButton(R.string.update_download_confirm_action) { _, _ ->
                    UpdateManager.launchAuthorizedInstall(this, info)
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun bindCreditsSection() {
        findViewById<View>(R.id.tiktokButton).setOnClickListener {
            openExternalUrl("https://www.tiktok.com/@keinerxzv")
        }
        findViewById<View>(R.id.instagramButton).setOnClickListener {
            openInstagramProfile("https://www.instagram.com/keinerxzv_/")
        }
    }

    private fun openInstagramProfile(url: String) {
        val uri = Uri.parse(url)
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.instagram.android")
        }
        try {
            startActivity(appIntent)
        } catch (_: ActivityNotFoundException) {
            openExternalUrl(url)
        }
    }

    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistAndRefresh() {
        RecordingPreferences.save(this, settings)
        refreshSelection()
    }

    private fun refreshSelection() {
        val selectedText = ThemeHelper.resolveColor(this, R.attr.srChipSelectedText)
        val normalText = ThemeHelper.resolveColor(this, R.attr.srTextPrimary)

        fun <T> updateChips(map: Map<T, Button>, active: T) {
            map.forEach { (option, button) ->
                val selected = option == active
                button.setBackgroundResource(
                    if (selected) R.drawable.chip_background_selected
                    else R.drawable.chip_background,
                )
                button.setTextColor(if (selected) selectedText else normalText)
            }
        }

        updateChips(qualityButtons, settings.videoQuality)
        updateChips(durationButtons, settings.durationLimit)
        updateChips(resolutionButtons, settings.resolution)
        updateChips(fpsButtons, settings.fps)
        updateChips(captureModeButtons, settings.captureMode)
        updateChips(audioButtons, settings.audioSource)
        updateChips(channelButtons, settings.channels)
        updateChips(themeButtons, settings.themeMode)
        updateChips(languageButtons, settings.language)
    }
}
