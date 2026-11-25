package com.smoothrec.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.smoothrec.app.recording.AppLanguageOption
import com.smoothrec.app.recording.RecordingPreferences
import java.util.Locale

/** Gestiona los 5 idiomas (es, en, zh, hi, ar + RTL) y detección automática del sistema. */
object LocaleHelper {

    fun wrap(base: Context): Context {
        val language = RecordingPreferences.load(base).language
        if (language == AppLanguageOption.AUTO) {
            val systemLocale = systemLocale()
            val config = Configuration(base.resources.configuration)
            config.setLocale(systemLocale)
            config.setLayoutDirection(systemLocale)
            return base.createConfigurationContext(config)
        }
        val locale = Locale(language.localeTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun isRtl(context: Context): Boolean =
        context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

    private fun systemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = LocaleList.getDefault()
            if (list.isEmpty) Locale.getDefault() else list[0]
        } else {
            @Suppress("DEPRECATION")
            Locale.getDefault()
        }
    }
}
