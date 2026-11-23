package com.smoothrec.app.recording

import android.content.Context
import android.content.SharedPreferences
import com.smoothrec.app.R

/** Opciones de resolución. Las dimensiones se derivan siempre de la relación de aspecto real. */
enum class ResolutionOption(
    val key: String,
    val labelRes: Int, // R.string.*
) {
    SCREEN("screen", R.string.resolution_screen),
    P1080("1080p", R.string.resolution_1080p),
    P720("720p", R.string.resolution_720p),
    ;

    companion object {
        fun fromKey(key: String?): ResolutionOption = when (key) {
            P1080.key -> P1080
            P720.key -> P720
            // "original" era la clave de la versión anterior; se conserva la preferencia guardada.
            SCREEN.key, "original", null -> SCREEN
            else -> SCREEN
        }
    }
}

enum class FpsOption(val value: Int) {
    FPS_60(60),
    FPS_30(30),
    ;

    companion object {
        fun fromValue(value: Int): FpsOption =
            values().firstOrNull { it.value == value } ?: FPS_60
    }
}

data class RecordingSettings(
    val resolution: ResolutionOption = ResolutionOption.SCREEN,
    val fps: FpsOption = FpsOption.FPS_60,
)

/** Persistencia sencilla sin dependencias: SharedPreferences privadas de la app. */
object RecordingPreferences {

    private const val FILE_NAME = "smoothrec_recording_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"

    fun load(context: Context): RecordingSettings {
        val prefs: SharedPreferences = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return RecordingSettings(
            resolution = ResolutionOption.fromKey(prefs.getString(KEY_RESOLUTION, null)),
            fps = FpsOption.fromValue(prefs.getInt(KEY_FPS, FpsOption.FPS_60.value)),
        )
    }

    fun save(context: Context, settings: RecordingSettings) {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESOLUTION, settings.resolution.key)
            .putInt(KEY_FPS, settings.fps.value)
            .apply()
    }
}
