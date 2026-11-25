package com.smoothrec.app.recording

import android.content.Context
import android.content.SharedPreferences
import com.smoothrec.app.R

/** Perfiles de calidad / bitrate de vídeo. AUTO calcula según resolución y FPS. */
enum class VideoQualityOption(
    val key: String,
    val labelRes: Int,
    val bitRateBps: Int,
) {
    AUTO("auto", R.string.quality_auto, 0),
    LOW("low", R.string.quality_low, 4_000_000),
    MEDIUM("medium", R.string.quality_medium, 8_000_000),
    HIGH("high", R.string.quality_high, 12_000_000),
    ULTRA("ultra", R.string.quality_ultra, 16_000_000),
    MAX("max", R.string.quality_max, 24_000_000),
    ;

    fun resolveBitrate(width: Int, height: Int, fps: Int): Int {
        if (bitRateBps > 0) return bitRateBps
        val pixels = width.toLong() * height.toLong()
        val base = when {
            pixels >= 1920L * 1080L -> 10_000_000
            pixels >= 1280L * 720L -> 6_500_000
            else -> 4_000_000
        }
        val fpsScale = when {
            fps >= 120 -> 1.6f
            fps >= 90 -> 1.4f
            fps >= 60 -> 1.25f
            else -> 1.0f
        }
        return (base * fpsScale).toInt().coerceIn(3_000_000, 24_000_000)
    }

    companion object {
        fun fromKey(key: String?): VideoQualityOption =
            values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** Opciones de resolución. Las dimensiones se derivan siempre de la relación de aspecto real. */
enum class ResolutionOption(
    val key: String,
    val labelRes: Int,
) {
    SCREEN("screen", R.string.resolution_screen),
    P1080("1080p", R.string.resolution_1080p),
    P720("720p", R.string.resolution_720p),
    ;

    companion object {
        fun fromKey(key: String?): ResolutionOption = when (key) {
            P1080.key -> P1080
            P720.key -> P720
            SCREEN.key, "original", null -> SCREEN
            else -> SCREEN
        }
    }
}

enum class FpsOption(val value: Int) {
    FPS_30(30),
    FPS_60(60),
    FPS_90(90),
    FPS_120(120),
    ;

    companion object {
        fun fromValue(value: Int): FpsOption =
            values().firstOrNull { it.value == value } ?: FPS_60
    }
}

enum class DurationLimitOption(
    val key: String,
    val labelRes: Int,
    val durationMs: Long,
) {
    UNLIMITED("unlimited", R.string.duration_unlimited, 0L),
    MIN_5("5m", R.string.duration_5m, 5 * 60_000L),
    MIN_15("15m", R.string.duration_15m, 15 * 60_000L),
    MIN_30("30m", R.string.duration_30m, 30 * 60_000L),
    MIN_60("60m", R.string.duration_60m, 60 * 60_000L),
    ;

    companion object {
        fun fromKey(key: String?): DurationLimitOption =
            values().firstOrNull { it.key == key } ?: UNLIMITED
    }
}

/** Modalidad de captura de audio. Siempre se comprueba la compatibilidad real del dispositivo. */
enum class AudioSourceOption(
    val key: String,
    val labelRes: Int,
) {
    INTERNAL("internal", R.string.audio_internal),
    MIC("mic", R.string.audio_mic),
    MIXED("mixed", R.string.audio_mixed),
    ;

    companion object {
        fun fromKey(key: String?): AudioSourceOption =
            values().firstOrNull { it.key == key } ?: INTERNAL
    }
}

/** Canales que se solicitan a AudioRecord, al codificador AAC y al muxer. */
enum class ChannelOption(
    val key: String,
    val labelRes: Int,
    val channelCount: Int,
) {
    MONO("mono", R.string.channel_mono, 1),
    STEREO("stereo", R.string.channel_stereo, 2),
    ;

    companion object {
        fun fromKey(key: String?): ChannelOption =
            values().firstOrNull { it.key == key } ?: STEREO
    }
}

/** Una sola app evita que la superposición de SmoothRec deje un rectángulo negro en el vídeo. */
enum class CaptureModeOption(
    val key: String,
    val labelRes: Int,
) {
    SINGLE_APP("single_app", R.string.capture_mode_single_app),
    FULL_SCREEN("full_screen", R.string.capture_mode_full_screen),
    ;

    companion object {
        fun fromKey(key: String?): CaptureModeOption =
            values().firstOrNull { it.key == key } ?: SINGLE_APP
    }
}

enum class ThemeModeOption(
    val key: String,
    val labelRes: Int,
) {
    AUTO("auto", R.string.theme_auto),
    DARK("dark", R.string.theme_dark),
    LIGHT("light", R.string.theme_light),
    ;

    companion object {
        fun fromKey(key: String?): ThemeModeOption =
            values().firstOrNull { it.key == key } ?: AUTO
    }
}

enum class AppLanguageOption(
    val key: String,
    val labelRes: Int,
    val localeTag: String,
) {
    AUTO("auto", R.string.language_auto, ""),
    SPANISH("es", R.string.language_es, "es"),
    ENGLISH("en", R.string.language_en, "en"),
    CHINESE("zh", R.string.language_zh, "zh"),
    HINDI("hi", R.string.language_hi, "hi"),
    ARABIC("ar", R.string.language_ar, "ar"),
    ;

    companion object {
        fun fromKey(key: String?): AppLanguageOption =
            values().firstOrNull { it.key == key } ?: AUTO
    }
}

data class RecordingSettings(
    val videoQuality: VideoQualityOption = VideoQualityOption.AUTO,
    val resolution: ResolutionOption = ResolutionOption.SCREEN,
    val fps: FpsOption = FpsOption.FPS_60,
    val durationLimit: DurationLimitOption = DurationLimitOption.UNLIMITED,
    val audioSource: AudioSourceOption = AudioSourceOption.INTERNAL,
    val channels: ChannelOption = ChannelOption.STEREO,
    val watermarkEnabled: Boolean = true,
    val captureMode: CaptureModeOption = CaptureModeOption.SINGLE_APP,
    val themeMode: ThemeModeOption = ThemeModeOption.AUTO,
    val language: AppLanguageOption = AppLanguageOption.AUTO,
    val bubbleOpacityPercent: Int = 70,
)

/** Persistencia sencilla sin dependencias: SharedPreferences privadas de la app. */
object RecordingPreferences {

    private const val FILE_NAME = "smoothrec_recording_settings"
    private const val KEY_VIDEO_QUALITY = "video_quality"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"
    private const val KEY_DURATION_LIMIT = "duration_limit"
    private const val KEY_AUDIO_SOURCE = "audio_source"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_WATERMARK = "watermark_enabled"
    private const val KEY_CAPTURE_MODE = "capture_mode"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_BUBBLE_OPACITY = "bubble_opacity_percent"

    fun load(context: Context): RecordingSettings {
        val prefs: SharedPreferences = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return RecordingSettings(
            videoQuality = VideoQualityOption.fromKey(prefs.getString(KEY_VIDEO_QUALITY, null)),
            resolution = ResolutionOption.fromKey(prefs.getString(KEY_RESOLUTION, null)),
            fps = FpsOption.fromValue(prefs.getInt(KEY_FPS, FpsOption.FPS_60.value)),
            durationLimit = DurationLimitOption.fromKey(prefs.getString(KEY_DURATION_LIMIT, null)),
            audioSource = AudioSourceOption.fromKey(prefs.getString(KEY_AUDIO_SOURCE, null)),
            channels = ChannelOption.fromKey(prefs.getString(KEY_CHANNELS, null)),
            watermarkEnabled = prefs.getBoolean(KEY_WATERMARK, true),
            captureMode = CaptureModeOption.fromKey(prefs.getString(KEY_CAPTURE_MODE, null)),
            themeMode = ThemeModeOption.fromKey(prefs.getString(KEY_THEME_MODE, null)),
            language = AppLanguageOption.fromKey(prefs.getString(KEY_LANGUAGE, null)),
            bubbleOpacityPercent = prefs.getInt(KEY_BUBBLE_OPACITY, 70).coerceIn(25, 100),
        )
    }

    fun save(context: Context, settings: RecordingSettings) {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIDEO_QUALITY, settings.videoQuality.key)
            .putString(KEY_RESOLUTION, settings.resolution.key)
            .putInt(KEY_FPS, settings.fps.value)
            .putString(KEY_DURATION_LIMIT, settings.durationLimit.key)
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.key)
            .putString(KEY_CHANNELS, settings.channels.key)
            .putBoolean(KEY_WATERMARK, settings.watermarkEnabled)
            .putString(KEY_CAPTURE_MODE, settings.captureMode.key)
            .putString(KEY_THEME_MODE, settings.themeMode.key)
            .putString(KEY_LANGUAGE, settings.language.key)
            .putInt(KEY_BUBBLE_OPACITY, settings.bubbleOpacityPercent.coerceIn(25, 100))
            .apply()
    }
}
