package com.smoothrec.app.compat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.encoder.CaptureSizeResolver

/**
 * Perfil de dispositivo calculado solo con APIs públicas de Android.
 *
 * Galaxy A17 4G y 5G pueden llegar con Android/One UI distintos según región y
 * operador. En lugar de fijar una lista de modeles frágil, se detecta la familia
 * y se priorizan las capacidades reales informadas por MediaCodec/AudioRecord.
 * Nada de aquí cambia la resolución, FPS o bitrate elegidos por el usuario: solo
 * ordena las pruebas de audio y deja constancia en el diagnóstico.
 */
object DeviceCompat {

    data class Profile(
        val manufacturer: String,
        val model: String,
        val device: String,
        val androidRelease: String,
        val sdkInt: Int,
        val oneUi: String?,
        val isSamsung: Boolean,
        val isGalaxyA17: Boolean,
        val hasMicrophone: Boolean,
        val internalAudioPossible: Boolean,
        val maxRefreshHz: Float,
        val codecName: String?,
        val supportedFps: List<Int>,
    ) {
        /** El A17 suele trabajar a 48 kHz; probar primero esa tasa evita remuestreos. */
        val preferredInternalRates: IntArray
            get() = if (isGalaxyA17) intArrayOf(48_000, 44_100)
            else intArrayOf(44_100, 48_000)

        val summary: String
            get() = "$manufacturer $model ($device) · Android $androidRelease (API $sdkInt)" +
                (oneUi?.let { " · $it" } ?: "") +
                " · códec=${codecName ?: "desconocido"}" +
                " · pantalla=${"%.0f".format(maxRefreshHz)}Hz"
    }

    fun profile(context: Context): Profile {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val isSamsung = manufacturer.equals("samsung", ignoreCase = true)
        val isGalaxyA17 = isSamsung && model.contains("A17", ignoreCase = true)
        val pm = context.packageManager
        val hasMic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        val display = try {
            val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        } catch (_: Exception) {
            null
        }
        val refresh = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: display?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                display?.refreshRate ?: 60f
            }
        } catch (_: Exception) {
            60f
        }

        val codec = try {
            CaptureSizeResolver.supportedFpsOptions(context)
            // primaryCodecInfo se obtiene indirectamente resolviendo una config real.
            CaptureSizeResolver.forContext(context, com.smoothrec.app.recording.RecordingPreferences.load(context))
                .codecName
        } catch (_: Exception) {
            null
        }
        val fps = try {
            CaptureSizeResolver.supportedFpsOptions(context).map { it.value }
        } catch (_: Exception) {
            listOf(30, 60)
        }

        return Profile(
            manufacturer = manufacturer,
            model = model,
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            oneUi = detectOneUi(),
            isSamsung = isSamsung,
            isGalaxyA17 = isGalaxyA17,
            hasMicrophone = hasMic,
            internalAudioPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            maxRefreshHz = refresh,
            codecName = codec,
            supportedFps = fps,
        )
    }

    /** Los campos Build son públicos; si el OEM no expone la versión, se omite. */
    private fun detectOneUi(): String? {
        val sources = listOf(Build.DISPLAY.orEmpty(), Build.VERSION.INCREMENTAL.orEmpty())
        for (raw in sources) {
            val match = Regex("(ONE\\s*UI\\s*[0-9]+(\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                .find(raw)
            if (match != null) return match.groupValues[1].replace(Regex("\\s+"), " ")
        }
        return null
    }

    fun log(context: Context) {
        val p = profile(context)
        CaptureDiagnostics.log(
            "DISPOSITIVO: ${p.summary} | mic=${p.hasMicrophone} " +
                "audioInterno=${p.internalAudioPossible} fpsSoportados=${p.supportedFps}",
        )
        if (p.isGalaxyA17) {
            CaptureDiagnostics.log("PERFIL Samsung Galaxy A17 detectado: audio 48 kHz prioritario")
        }
    }
}
