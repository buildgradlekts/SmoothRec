package com.smoothrec.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController
import com.smoothrec.app.R
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.recording.ThemeModeOption

/** Resuelve y aplica el tema (Oscuro, Claro o Automático del sistema) sin dependencias externas. */
object ThemeHelper {

    fun isDarkActive(context: Context): Boolean {
        return when (RecordingPreferences.load(context).themeMode) {
            ThemeModeOption.DARK -> true
            ThemeModeOption.LIGHT -> false
            ThemeModeOption.AUTO -> isSystemDark(context)
        }
    }

    fun resolveThemeRes(context: Context): Int =
        if (isDarkActive(context)) R.style.Theme_SmoothRec_Dark else R.style.Theme_SmoothRec_Light

    fun applyToActivity(activity: Activity) {
        val dark = isDarkActive(activity)
        activity.setTheme(if (dark) R.style.Theme_SmoothRec_Dark else R.style.Theme_SmoothRec_Light)
    }

    fun applySystemBars(activity: Activity) {
        val dark = isDarkActive(activity)
        val window = activity.window ?: return
        val bg = resolveColor(activity, R.attr.srBackground)
        val nav = resolveColor(activity, R.attr.srNavBarBackground)
        window.statusBarColor = bg
        window.navigationBarColor = nav
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val flags = if (dark) 0 else (
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            window.insetsController?.setSystemBarsAppearance(
                flags,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            var vis = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            vis = if (dark) {
                vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vis = if (dark) {
                    vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = vis
        }
    }

    fun resolveColor(context: Context, attrRes: Int): Int {
        val typed = TypedValue()
        context.theme.resolveAttribute(attrRes, typed, true)
        return typed.data
    }

    private fun isSystemDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}
