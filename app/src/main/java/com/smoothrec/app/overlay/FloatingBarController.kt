package com.smoothrec.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.smoothrec.app.R
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.ui.LocaleHelper
import com.smoothrec.app.ui.ThemeHelper
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bolita flotante minimalista y controles expandibles sobre otras apps.
 *
 * - Se minimiza a una pequeña bolita de 44 dp que se ancla suavemente en cualquiera
 *   de las 4 esquinas de la pantalla al soltarla.
 * - Respeta transparencia configurable para no estorbar en videojuegos.
 * - Usa FLAG_NOT_FOCUSABLE y FLAG_NOT_TOUCH_MODAL para no robar foco ni toques del juego.
 * - Durante la grabación la ventana se desmonta por completo: así MediaProjection
 *   no captura la barra y no aparece el rectángulo negro de FLAG_SECURE. La
 *   notificación del servicio mantiene los controles de pausa/parada.
 */
class FloatingBarController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onStartRecording()
        fun onPauseRecording()
        fun onResumeRecording()
        fun onStopRecording()
        fun onClose()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleDot: View? = null
    private var expandedView: View? = null
    private var recordStopBtn: ImageButton? = null
    private var pauseResumeBtn: ImageButton? = null
    private var timerText: TextView? = null

    private var isExpanded = false
    private var isRecording = false
    private var isPaused = false
    private var windowAttached = false
    private var hiddenForRecording = false
    private var timerRunning = false
    private var timerStartedElapsed = 0L
    private var accumulatedActiveMs = 0L
    private var bubbleOpacity = 0.70f

    private var snapAnimator: ValueAnimator? = null

    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            timerText?.text = formatTime(currentActiveElapsedMs())
            handler.postDelayed(this, 250L)
        }
    }

    private val autoMinimizeRunnable = Runnable {
        if (isRecording && isExpanded) {
            setExpanded(false)
        }
    }

    fun isShowing(): Boolean = windowAttached

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (view != null) return true

        val localized = LocaleHelper.wrap(context)
        val themed = ContextThemeWrapper(localized, ThemeHelper.resolveThemeRes(localized))
        val inflated = LayoutInflater.from(themed).inflate(R.layout.floating_bar, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleOpacity = RecordingPreferences.load(context).bubbleOpacityPercent / 100f

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Sin FLAG_SECURE: al desmontar la ventana mientras se graba, el
            // contenido del juego queda intacto y no aparece el parche negro.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        bubbleView = inflated.findViewById(R.id.overlayBubble)
        bubbleDot = inflated.findViewById(R.id.overlayBubbleDot)
        expandedView = inflated.findViewById(R.id.overlayExpanded)
        recordStopBtn = inflated.findViewById(R.id.overlayRecordStop)
        pauseResumeBtn = inflated.findViewById(R.id.overlayPauseResume)
        timerText = inflated.findViewById(R.id.overlayTimer)

        bindActions(inflated)
        bindBubbleTouch(bubbleView!!, layout)
        bindDrag(timerText!!, layout)

        windowManager.addView(inflated, layout)
        windowAttached = true
        hiddenForRecording = false
        view = inflated
        params = layout

        // Empezamos en la esquina guardada como bolita minimalista (o expandida brevemente al abrir sesión).
        isExpanded = true
        updateUiState()
        inflated.post {
            snapToSavedCorner()
        }
        return true
    }

    fun updateOpacity(percent: Int) {
        handler.post {
            bubbleOpacity = percent.coerceIn(25, 100) / 100f
            if (!isExpanded) {
                bubbleView?.alpha = bubbleOpacity
            }
        }
    }

    /**
     * Desmonta (o vuelve a montar) la ventana de la barra sin perder el estado
     * del temporizador. Se usa mientras graba para que MediaProjection no la
     * captura y no deje el rectángulo negro de FLAG_SECURE.
     */
    fun setHiddenForRecording(hidden: Boolean) {
        handler.post {
            if (hiddenForRecording == hidden) return@post
            hiddenForRecording = hidden
            val current = view ?: return@post
            val layout = params ?: return@post
            if (hidden) {
                if (windowAttached) {
                    runCatching { windowManager.removeViewImmediate(current) }
                    windowAttached = false
                }
            } else if (!windowAttached && Settings.canDrawOverlays(context)) {
                runCatching {
                    windowManager.addView(current, layout)
                    windowAttached = true
                }
                current.post { clampInsideScreen() }
            }
        }
    }

    fun hide() {
        timerRunning = false
        handler.removeCallbacks(timerTick)
        handler.removeCallbacks(autoMinimizeRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        val current = view
        if (windowAttached && current != null) {
            runCatching { windowManager.removeViewImmediate(current) }
        }
        windowAttached = false
        hiddenForRecording = false
        view = null
        params = null
        bubbleView = null
        bubbleDot = null
        expandedView = null
        recordStopBtn = null
        pauseResumeBtn = null
        timerText = null
    }

    fun onRecordingStarted() {
        handler.post {
            // La barra desaparece de la pantalla (y del vídeo) mientras graba.
            setHiddenForRecording(true)
            isRecording = true
            isPaused = false
            accumulatedActiveMs = 0L
            timerStartedElapsed = SystemClock.elapsedRealtime()
            timerRunning = true
            timerText?.text = formatTime(0L)
            updateUiState()
            handler.removeCallbacks(timerTick)
            handler.post(timerTick)
            handler.removeCallbacks(autoMinimizeRunnable)
            handler.postDelayed(autoMinimizeRunnable, 1200L)
        }
    }

    fun onRecordingPaused() {
        handler.post {
            if (!isRecording || isPaused) return@post
            accumulatedActiveMs = currentActiveElapsedMs()
            isPaused = true
            timerRunning = false
            handler.removeCallbacks(timerTick)
            timerText?.text = formatTime(accumulatedActiveMs)
            updateUiState()
        }
    }

    fun onRecordingResumed() {
        handler.post {
            if (!isRecording || !isPaused) return@post
            isPaused = false
            timerStartedElapsed = SystemClock.elapsedRealtime()
            timerRunning = true
            updateUiState()
            handler.removeCallbacks(timerTick)
            handler.post(timerTick)
            handler.removeCallbacks(autoMinimizeRunnable)
            handler.postDelayed(autoMinimizeRunnable, 900L)
        }
    }

    fun onRecordingStopped() {
        handler.post {
            // Al terminar, la barra vuelve a estar disponible en pantalla.
            setHiddenForRecording(false)
            isRecording = false
            isPaused = false
            timerRunning = false
            accumulatedActiveMs = 0L
            handler.removeCallbacks(timerTick)
            handler.removeCallbacks(autoMinimizeRunnable)
            timerText?.text = formatTime(0L)
            updateUiState()
        }
    }

    private fun currentActiveElapsedMs(): Long {
        if (!timerRunning) return accumulatedActiveMs
        return accumulatedActiveMs + (SystemClock.elapsedRealtime() - timerStartedElapsed).coerceAtLeast(0L)
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        updateUiState()
        view?.post { clampInsideScreen() }
    }

    private fun updateUiState() {
        val bubble = bubbleView ?: return
        val expanded = expandedView ?: return

        if (isExpanded) {
            bubble.visibility = View.GONE
            expanded.visibility = View.VISIBLE
            expanded.alpha = 1f
        } else {
            expanded.visibility = View.GONE
            bubble.visibility = View.VISIBLE
            bubble.alpha = bubbleOpacity
        }

        val dot = bubbleDot
        val bubbleIcon = bubble.findViewById<ImageView>(R.id.overlayBubbleIcon)
        if (isRecording) {
            dot?.visibility = View.VISIBLE
            dot?.setBackgroundResource(
                if (isPaused) R.drawable.fab_background else R.drawable.fab_background_stop,
            )
            bubbleIcon?.alpha = if (isPaused) 0.7f else 1f
        } else {
            dot?.visibility = View.GONE
            bubbleIcon?.alpha = 0.9f
        }

        val recStop = recordStopBtn
        val pauseResume = pauseResumeBtn
        if (isRecording) {
            recStop?.setImageResource(R.drawable.ic_stop)
            recStop?.setColorFilter(context.getColor(R.color.dark_recording_accent))
            pauseResume?.visibility = View.VISIBLE
            pauseResume?.setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        } else {
            recStop?.setImageResource(R.drawable.ic_record)
            recStop?.setColorFilter(context.getColor(R.color.dark_recording_accent))
            pauseResume?.visibility = View.GONE
        }
    }

    private fun bindActions(root: View) {
        recordStopBtn?.setOnClickListener {
            if (isRecording) {
                callbacks.onStopRecording()
            } else {
                callbacks.onStartRecording()
            }
        }
        pauseResumeBtn?.setOnClickListener {
            if (!isRecording) return@setOnClickListener
            if (isPaused) callbacks.onResumeRecording() else callbacks.onPauseRecording()
        }
        root.findViewById<View>(R.id.overlayMinimize).setOnClickListener {
            handler.removeCallbacks(autoMinimizeRunnable)
            setExpanded(false)
            view?.post { snapToNearestCorner(animate = true) }
        }
        root.findViewById<View>(R.id.overlayClose).setOnClickListener {
            callbacks.onClose()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindBubbleTouch(bubble: View, layout: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        bubble.setOnTouchListener { _, event ->
            val current = view ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        layout.x = startX + dx.roundToInt()
                        layout.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(current, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToNearestCorner(animate = true)
                    } else {
                        setExpanded(true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDrag(handle: View, layout: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            val current = view ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - touchX).roundToInt()
                    layout.y = startY + (event.rawY - touchY).roundToInt()
                    runCatching { windowManager.updateViewLayout(current, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampInsideScreen()
                    true
                }
                else -> false
            }
        }
    }

    private fun screenBounds(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun cornerPosition(corner: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val (screenW, screenH) = screenBounds()
        val marginX = dp(12)
        val topMargin = dp(40)
        val bottomMargin = dp(56)
        val maxX = (screenW - viewW - marginX).coerceAtLeast(marginX)
        val maxY = (screenH - viewH - bottomMargin).coerceAtLeast(topMargin)
        return when (corner) {
            CORNER_TOP_LEFT -> marginX to topMargin
            CORNER_TOP_RIGHT -> maxX to topMargin
            CORNER_BOTTOM_LEFT -> marginX to maxY
            else -> maxX to maxY
        }
    }

    private fun snapToSavedCorner() {
        val current = view ?: return
        val layout = params ?: return
        val corner = prefs.getInt(PREF_CORNER, CORNER_TOP_RIGHT)
        val (targetX, targetY) = cornerPosition(corner, current.width, current.height)
        layout.x = targetX
        layout.y = targetY
        runCatching { windowManager.updateViewLayout(current, layout) }
    }

    private fun snapToNearestCorner(animate: Boolean) {
        val current = view ?: return
        val layout = params ?: return
        val (screenW, screenH) = screenBounds()
        val centerX = layout.x + current.width / 2
        val centerY = layout.y + current.height / 2
        val rightSide = centerX >= screenW / 2
        val bottomSide = centerY >= screenH / 2
        val corner = when {
            !rightSide && !bottomSide -> CORNER_TOP_LEFT
            rightSide && !bottomSide -> CORNER_TOP_RIGHT
            !rightSide && bottomSide -> CORNER_BOTTOM_LEFT
            else -> CORNER_BOTTOM_RIGHT
        }
        prefs.edit().putInt(PREF_CORNER, corner).apply()
        val (targetX, targetY) = cornerPosition(corner, current.width, current.height)
        if (!animate) {
            layout.x = targetX
            layout.y = targetY
            runCatching { windowManager.updateViewLayout(current, layout) }
            return
        }
        val fromX = layout.x
        val fromY = layout.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = view ?: return@addUpdateListener
                val t = anim.animatedValue as Float
                layout.x = (fromX + (targetX - fromX) * t).roundToInt()
                layout.y = (fromY + (targetY - fromY) * t).roundToInt()
                runCatching { windowManager.updateViewLayout(v, layout) }
            }
            start()
        }
    }

    private fun clampInsideScreen() {
        val current = view ?: return
        val layout = params ?: return
        val (screenW, screenH) = screenBounds()
        val margin = dp(10)
        val maxX = (screenW - current.width - margin).coerceAtLeast(margin)
        val maxY = (screenH - current.height - dp(24)).coerceAtLeast(dp(24))
        layout.x = layout.x.coerceIn(margin, maxX)
        layout.y = layout.y.coerceIn(dp(24), maxY)
        runCatching { windowManager.updateViewLayout(current, layout) }
    }

    companion object {
        private const val PREFS = "smoothrec_overlay"
        private const val PREF_CORNER = "corner"
        private const val CORNER_TOP_LEFT = 0
        private const val CORNER_TOP_RIGHT = 1
        private const val CORNER_BOTTOM_LEFT = 2
        private const val CORNER_BOTTOM_RIGHT = 3

        fun formatTime(elapsedMs: Long): String {
            val total = (elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
