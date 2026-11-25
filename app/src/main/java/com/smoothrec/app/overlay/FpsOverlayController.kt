package com.smoothrec.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.smoothrec.app.R
import kotlin.math.roundToInt

/**
 * Indicador flotante e independiente de los FPS efectivos del encoder.
 * No captura eventos fuera de su propia vista y no interfiere con la grabación.
 */
class FpsOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: View? = null
    private var text: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    @SuppressLint("InflateParams")
    fun show(): Boolean {
        if (root != null) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)
        ) return false
        val manager = windowManager ?: return false

        val view = LayoutInflater.from(context).inflate(R.layout.fps_overlay, null)
        root = view
        text = view.findViewById(R.id.fpsOverlayText)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getMetrics(displayMetrics)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels * 0.5f).roundToInt()
            y = (displayMetrics.heightPixels * 0.12f).roundToInt()
        }
        params = lp
        view.setOnTouchListener { v, event -> handleTouch(event, v, lp, manager) }

        return try {
            manager.addView(view, lp)
            true
        } catch (e: Exception) {
            root = null
            text = null
            params = null
            false
        }
    }

    fun update(fps: Int, paused: Boolean) {
        mainHandler.post {
            val label = if (paused) {
                context.getString(R.string.fps_overlay_paused)
            } else if (fps > 0) {
                context.getString(R.string.fps_overlay_value, fps)
            } else {
                context.getString(R.string.fps_overlay_waiting)
            }
            text?.text = label
        }
    }

    fun hide() {
        mainHandler.post {
            val view = root ?: return@post
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
            root = null
            text = null
            params = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(
        event: MotionEvent,
        view: View,
        lp: WindowManager.LayoutParams,
        manager: WindowManager,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = lp.x
                initialY = lp.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                manager.defaultDisplay.getMetrics(metrics)
                val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
                val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
                lp.x = (initialX + (event.rawX - initialTouchX).roundToInt()).coerceIn(0, maxX)
                lp.y = (initialY + (event.rawY - initialTouchY).roundToInt()).coerceIn(0, maxY)
                try {
                    manager.updateViewLayout(view, lp)
                } catch (_: Exception) {
                }
            }
        }
        return true
    }
}
