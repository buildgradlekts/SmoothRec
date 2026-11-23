package com.smoothrec.app.encoder

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.smoothrec.app.R
import com.smoothrec.app.recording.FpsOption
import com.smoothrec.app.recording.RecordingSettings
import com.smoothrec.app.recording.ResolutionOption
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Calcula las dimensiones de captura a partir de los píxeles reales de la pantalla,
 * conservando la relación de aspecto y respetando lo que el encoder H.264 hardware
 * realmente declara que puede hacer.
 */
object CaptureSizeResolver {

    data class ScreenInfo(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val rotation: Int,
        val source: String,
    )

    data class ResolutionResult(
        val config: EncoderConfig,
        val requested: EncoderConfig,
        val codecName: String?,
        /** Recurso de texto a mostrar cuando hubo que corregir la combinación elegida. */
        val fallbackMessageRes: Int?,
        val fallbackFormatArgs: List<Any>,
    ) {
        val usedFallback: Boolean get() = fallbackMessageRes != null
    }

    /**
     * Obtiene el área real de contenido. MediaProjection captura el contenido de la
     * Window (no el framebuffer físico con barras del sistema), por lo que en API 30+
     * getCurrentWindowMetrics().bounds es la fuente más fiel. En versiones anteriores
     * se usa el tamaño real de la Display. La densidad se lee de DisplayMetrics porque
     * es la que se pasa al VirtualDisplay.
     */
    fun readScreen(context: Context): ScreenInfo {
        val densityDpi = context.resources.displayMetrics.densityDpi
        val rotation = currentRotation(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = context.getSystemService(WindowManager::class.java)
                .currentWindowMetrics
            val bounds = metrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return ScreenInfo(
                    bounds.width(),
                    bounds.height(),
                    densityDpi,
                    rotation,
                    "WindowManager.currentWindowMetrics",
                )
            }
        }

        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            display?.getRealSize(size)
        } else {
            display?.getSize(size)
        }
        if (size.x > 0 && size.y > 0) {
            return ScreenInfo(size.x, size.y, densityDpi, rotation, "Display.getRealSize")
        }

        val metrics = context.resources.displayMetrics
        return ScreenInfo(
            metrics.widthPixels,
            metrics.heightPixels,
            densityDpi,
            rotation,
            "DisplayMetrics",
        )
    }

    /**
     * Resuelve la configuración efectiva. Nunca estira, recorta ni gira la imagen:
     * todas las opciones parten de la relación de aspecto real de la pantalla.
     */
    fun resolve(screen: ScreenInfo, settings: RecordingSettings): ResolutionResult {
        val reference = EncoderConfig(frameRate = settings.fps.value)
        val codecInfo = EncoderFactory.primaryCodecInfo(reference)
        val codecName = codecInfo?.name
        val limits = codecLimits(codecInfo)

        val requested = configFor(screen, settings.resolution, settings.fps, limits)
        val candidates = candidates(settings, limits)
            .map { configFor(screen, it.first, it.second, limits) }
            .distinctBy { "${it.width}x${it.height}@${it.frameRate}" }

        val supported = candidates.firstOrNull { isSupported(it, codecInfo) }
        val chosen = supported ?: safestFallback(screen, limits)

        val message: Pair<Int, List<Any>>? = when {
            chosen.width != requested.width || chosen.height != requested.height ->
                R.string.fallback_resolution to listOf(
                    chosen.width,
                    chosen.height,
                    chosen.frameRate,
                )
            chosen.frameRate != requested.frameRate ->
                R.string.fallback_framerate to listOf(chosen.frameRate)
            else -> null
        }

        Log.i(
            TAG,
            "Pantalla ${screen.width}x${screen.height} (${screen.source}), densidad ${screen.densityDpi}, " +
                "rotación ${screen.rotation}, códec $codecName -> solicitado " +
                "${requested.width}x${requested.height}@${requested.frameRate}, aplicado " +
                "${chosen.width}x${chosen.height}@${chosen.frameRate}",
        )

        return ResolutionResult(
            config = chosen,
            requested = requested,
            codecName = codecName,
            fallbackMessageRes = message?.first,
            fallbackFormatArgs = message?.second ?: emptyList(),
        )
    }

    fun forContext(context: Context, settings: RecordingSettings): ResolutionResult =
        resolve(readScreen(context), settings)

    /** Orden de intentos: se priorizan los FPS, bajando resolución antes que FPS. */
    private fun candidates(
        settings: RecordingSettings,
        limits: Limits,
    ): List<Pair<ResolutionOption, FpsOption>> {
        val fpsOrder = if (settings.fps == FpsOption.FPS_60) {
            listOf(FpsOption.FPS_60, FpsOption.FPS_30)
        } else {
            listOf(FpsOption.FPS_30)
        }
        val resolutionOrder = when (settings.resolution) {
            ResolutionOption.P1080 -> listOf(
                ResolutionOption.P1080,
                ResolutionOption.P720,
                ResolutionOption.SCREEN,
            )
            ResolutionOption.P720 -> listOf(
                ResolutionOption.P720,
                ResolutionOption.SCREEN,
                ResolutionOption.P1080,
            )
            ResolutionOption.SCREEN -> listOf(
                ResolutionOption.SCREEN,
                ResolutionOption.P1080,
                ResolutionOption.P720,
            )
        }

        val result = mutableListOf<Pair<ResolutionOption, FpsOption>>()
        for (fps in fpsOrder) {
            for (resolution in resolutionOrder) {
                result.add(resolution to fps)
            }
        }
        return result
    }

    /**
     * Todas las opciones conservan la relación de aspecto real (lado largo / lado corto).
     * - SCREEN intenta los píxeles detectados y los limita a lo que el encoder admite.
     * - 1080p encaja dentro del sobre 1920x1080.
     * - 720p fija el lado corto y deriva el largo de la relación real.
     */
    private fun configFor(
        screen: ScreenInfo,
        option: ResolutionOption,
        fps: FpsOption,
        limits: Limits,
    ): EncoderConfig {
        val sourceWidth = max(screen.width, 1)
        val sourceHeight = max(screen.height, 1)
        val landscape = sourceWidth >= sourceHeight
        val sourceLong = max(sourceWidth, sourceHeight).toFloat()
        val sourceShort = min(sourceWidth, sourceHeight).toFloat()

        var long: Float
        var short: Float
        when (option) {
            ResolutionOption.SCREEN -> {
                long = sourceLong
                short = sourceShort
            }
            ResolutionOption.P1080 -> {
                val scale = min(
                    MAX_LONG_SIDE.toFloat() / sourceLong,
                    MAX_SHORT_SIDE.toFloat() / sourceShort,
                )
                long = sourceLong * scale
                short = sourceShort * scale
            }
            ResolutionOption.P720 -> {
                short = TARGET_720.toFloat()
                long = short * sourceLong / sourceShort
            }
        }

        val maxLong = if (landscape) limits.maxWidth else limits.maxHeight
        val maxShort = if (landscape) limits.maxHeight else limits.maxWidth
        val capScale = min(maxLong.toFloat() / long, maxShort.toFloat() / short)
        if (capScale < 1f) {
            long *= capScale
            short *= capScale
        }

        var alignedLong = alignUp(long.roundToIntClamped(), limits.alignment)
        var alignedShort = alignUp(short.roundToIntClamped(), limits.alignment)
        alignedLong = alignedLong.coerceIn(limits.minLong, maxLong)
        alignedShort = alignedShort.coerceIn(limits.minShort, maxShort)
        alignedLong = alignDown(alignedLong, limits.alignment).coerceAtLeast(limits.minLong)
        alignedShort = alignDown(alignedShort, limits.alignment).coerceAtLeast(limits.minShort)

        val width = if (landscape) alignedLong else alignedShort
        val height = if (landscape) alignedShort else alignedLong
        return EncoderConfig(width = width, height = height, frameRate = fps.value)
    }

    /** Comprobación con VideoCapabilities: tamaño, alineación y tasa real declarada. */
    private fun isSupported(config: EncoderConfig, codecInfo: MediaCodecInfo?): Boolean {
        if (codecInfo == null) return true // Sin datos del sistema no bloqueamos la grabación.
        return try {
            val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                ?: return true
            val vc = caps.videoCapabilities ?: return true
            val widthOk = config.width >= vc.supportedWidths.lower &&
                config.width <= vc.supportedWidths.upper &&
                config.width % vc.widthAlignment.coerceAtLeast(1) == 0
            val heightOk = config.height >= vc.supportedHeights.lower &&
                config.height <= vc.supportedHeights.upper &&
                config.height % vc.heightAlignment.coerceAtLeast(1) == 0
            widthOk && heightOk &&
                vc.areSizeAndRateSupported(config.width, config.height, config.frameRate.toDouble())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo validar ${config.width}x${config.height}@${config.frameRate}", e)
            true
        }
    }

    private fun safestFallback(screen: ScreenInfo, limits: Limits): EncoderConfig =
        configFor(screen, ResolutionOption.P720, FpsOption.FPS_30, limits)

    private data class Limits(
        val minWidth: Int,
        val maxWidth: Int,
        val minHeight: Int,
        val maxHeight: Int,
        val alignment: Int,
    ) {
        val minLong: Int get() = max(minWidth, minHeight)
        val minShort: Int get() = min(minWidth, minHeight)
        val maxLong: Int get() = max(maxWidth, maxHeight)
        val maxShort: Int get() = min(maxWidth, maxHeight)
    }

    private fun codecLimits(info: MediaCodecInfo?): Limits {
        if (info == null) {
            return Limits(128, MAX_LONG_SIDE, 128, MAX_LONG_SIDE, 2)
        }
        return try {
            val vc = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                ?.videoCapabilities
                ?: return Limits(128, MAX_LONG_SIDE, 128, MAX_LONG_SIDE, 2)
            Limits(
                minWidth = vc.supportedWidths.lower,
                maxWidth = vc.supportedWidths.upper,
                minHeight = vc.supportedHeights.lower,
                maxHeight = vc.supportedHeights.upper,
                alignment = max(
                    vc.widthAlignment.coerceAtLeast(1),
                    vc.heightAlignment.coerceAtLeast(1),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer los límites del encoder; usando 1920x1920", e)
            Limits(128, MAX_LONG_SIDE, 128, MAX_LONG_SIDE, 2)
        }
    }

    private fun Float.roundToIntClamped(): Int =
        max(1, round(this).toInt())

    private fun alignUp(value: Int, alignment: Int): Int =
        if (alignment <= 1) value else round(value.toFloat() / alignment).toInt() * alignment

    private fun alignDown(value: Int, alignment: Int): Int =
        if (alignment <= 1) value else (value / alignment) * alignment

    private fun currentRotation(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(WindowManager::class.java)
                    .defaultDisplay.rotation
            }
        } catch (e: Exception) {
            -1
        }
    }

    private const val TAG = "CaptureSizeResolver"
    private const val MAX_LONG_SIDE = 1920
    private const val MAX_SHORT_SIDE = 1080
    private const val TARGET_720 = 720
}
