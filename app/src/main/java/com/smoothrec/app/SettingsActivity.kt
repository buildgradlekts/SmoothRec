package com.smoothrec.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import com.smoothrec.app.recording.FpsOption
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.recording.RecordingSettings
import com.smoothrec.app.recording.ResolutionOption

/** Ajustes de grabación: resolución y FPS, guardados para sesiones futuras. */
class SettingsActivity : Activity() {

    private lateinit var settings: RecordingSettings

    private val resolutionButtons: Map<ResolutionOption, Button> by lazy {
        mapOf(
            ResolutionOption.P1080 to findViewById(R.id.resolution1080Button),
            ResolutionOption.ORIGINAL to findViewById(R.id.resolutionOriginalButton),
            ResolutionOption.P864 to findViewById(R.id.resolution864Button),
            ResolutionOption.P720 to findViewById(R.id.resolution720Button),
        )
    }

    private val fpsButtons: Map<FpsOption, Button> by lazy {
        mapOf(
            FpsOption.FPS_60 to findViewById(R.id.fps60Button),
            FpsOption.FPS_30 to findViewById(R.id.fps30Button),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = RecordingPreferences.load(this)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

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

        refreshSelection()
    }

    private fun persistAndRefresh() {
        RecordingPreferences.save(this, settings)
        refreshSelection()
    }

    private fun refreshSelection() {
        resolutionButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.resolution) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        fpsButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.fps) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        findViewById<TextView>(R.id.appliedSummary).text = getString(
            R.string.settings_applied_summary,
        )
    }
}
