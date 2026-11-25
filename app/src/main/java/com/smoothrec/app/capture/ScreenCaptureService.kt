package com.smoothrec.app.capture

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.overlay.FloatingBarController
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.ui.LocaleHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano que gestiona la sesión MediaProjection, pausa/reanudación,
 * límite de duración, barra/bolita flotante y telemetría ligera.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.smoothrec.app.action.START_CAPTURE"
        const val ACTION_START_RECORDING = "com.smoothrec.app.action.START_RECORDING"
        const val ACTION_UPDATE_BUBBLE_OPACITY = "com.smoothrec.app.action.UPDATE_BUBBLE_OPACITY"
        const val EXTRA_RESULT_CODE = "com.smoothrec.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.smoothrec.app.extra.RESULT_DATA"
        const val EXTRA_SESSION_ID = "com.smoothrec.app.extra.SESSION_ID"
        const val EXTRA_BUBBLE_OPACITY = "com.smoothrec.app.extra.BUBBLE_OPACITY"
        private const val TAG = "ScreenCaptureService"
    }

    @Volatile private var session: CaptureSession? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var endRequested = false
    @Volatile private var finished = false
    @Volatile private var endAfterRecording = false
    @Volatile private var isPaused = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordingStopPosted = AtomicBoolean(false)

    private val lifecycle: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmoothRecLifecycle")
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: FloatingBarController? = null

    private var segmentStartedElapsed = 0L
    private var accumulatedActiveMs = 0L
    private var maxDurationMs = 0L
    private var timerActive = false
    private var lastConsumedSessionId: String? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerActive) return
            val currentSession = session
            val activeMs = currentActiveElapsedMs()

            if (maxDurationMs > 0L && activeMs >= maxDurationMs && !isPaused) {
                notifyConfigApplied(localizedString(R.string.duration_limit_reached))
                stopRecording()
                return
            }

            if (currentSession != null) {
                val telemetry = currentSession.snapshotTelemetry(activeMs)
                sendBroadcast(telemetry.toIntent().setPackage(packageName))
            }

            try {
                val text = if (isPaused) {
                    localizedString(
                        R.string.notification_paused_time,
                        FloatingBarController.formatTime(activeMs),
                    )
                } else {
                    localizedString(
                        R.string.notification_recording_time,
                        FloatingBarController.formatTime(activeMs),
                    )
                }
                CaptureNotification.update(
                    this@ScreenCaptureService,
                    text,
                    isRecording = currentSession != null,
                    isPaused = isPaused,
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo actualizar la notificación", e)
            }

            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CaptureNotification.ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            CaptureNotification.ACTION_PAUSE -> {
                pauseRecording()
                return START_NOT_STICKY
            }
            CaptureNotification.ACTION_RESUME -> {
                resumeRecording()
                return START_NOT_STICKY
            }
            CaptureNotification.ACTION_END_SESSION -> {
                requestEndSession()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                startRecording()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_BUBBLE_OPACITY -> {
                val opacity = intent.getIntExtra(EXTRA_BUBBLE_OPACITY, 70)
                overlay?.updateOpacity(opacity)
                return START_NOT_STICKY
            }
        }

        if (endRequested || projection != null) {
            promoteToForeground()
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val consentData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)

        if (consentData == null || resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Datos de consentimiento incompletos")
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionId.isNullOrBlank() || sessionId == lastConsumedSessionId) {
            Log.w(TAG, "Intent de grabación duplicado")
            stopSelf()
            return START_NOT_STICKY
        }
        lastConsumedSessionId = sessionId

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureDiagnostics.log("SERVICIO: RECORD_AUDIO DENEGADO")
            stopSelf()
            return START_NOT_STICKY
        }
        CaptureDiagnostics.logPermissions(this)

        CaptureNotification.createChannel(this)
        promoteToForeground()

        val newProjection = try {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, consentData)
        } catch (e: SecurityException) {
            Log.e(TAG, "No se pudo obtener MediaProjection", e)
            stopSelf()
            return START_NOT_STICKY
        }
        projection = newProjection
        acquireWakeLock()
        showOverlay()
        CaptureNotification.update(
            this,
            localizedString(R.string.notification_session_text),
            isRecording = false,
            isPaused = false,
        )
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val activeProjection = projection
        if (activeProjection == null) {
            notifyConfigApplied(localizedString(R.string.session_not_active))
            return
        }
        if (session != null || endRequested) return

        val settings = RecordingPreferences.load(this)
        maxDurationMs = settings.durationLimit.durationMs
        accumulatedActiveMs = 0L
        isPaused = false
        recordingStopPosted.set(false)
        CaptureDiagnostics.log(
            "SERVICIO: iniciar grabación · modo=" +
                localizedString(settings.captureMode.labelRes),
        )

        // El contador arranca al pulsar Grabar: preparar los encoders tarda 1-2 s
        // y antes parecía que la grabación no arrancaba (00:00:00).
        segmentStartedElapsed = SystemClock.elapsedRealtime()
        timerActive = true
        overlay?.onRecordingStarted()
        notifyConfigApplied(localizedString(R.string.overlay_hidden_during_recording))
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)

        // Actualiza tipo FGS si cambió la fuente de audio entre grabaciones.
        promoteToForeground()
        acquireWakeLock(maxDurationMs)

        val newSession = CaptureSession(
            applicationContext,
            activeProjection,
            { requestEndSession() },
            { messageRes -> notifyConfigApplied(localizedString(messageRes)) },
        )
        session = newSession

        lifecycle.execute {
            if (endRequested) {
                newSession.release()
                if (session === newSession) session = null
                mainHandler.post {
                    timerActive = false
                    mainHandler.removeCallbacks(timerRunnable)
                    overlay?.onRecordingStopped()
                }
                return@execute
            }
            try {
                newSession.start()
                if (endRequested) {
                    newSession.release()
                    if (session === newSession) session = null
                    mainHandler.post {
                        timerActive = false
                        mainHandler.removeCallbacks(timerRunnable)
                        overlay?.onRecordingStopped()
                    }
                    return@execute
                }
                newSession.appliedResolution?.let { result ->
                    val config = result.config
                    CaptureNotification.update(
                        this,
                        localizedString(
                            R.string.notification_text_dynamic,
                            config.width,
                            config.height,
                            config.frameRate,
                        ),
                        isRecording = true,
                        isPaused = false,
                    )
                    result.fallbackMessageRes?.let { res ->
                        notifyConfigApplied(localizedString(res, *result.fallbackFormatArgs.toTypedArray()))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al iniciar la captura", e)
                mainHandler.post {
                    if (session === newSession) session = null
                    timerActive = false
                    mainHandler.removeCallbacks(timerRunnable)
                    overlay?.onRecordingStopped()
                    CaptureNotification.update(
                        this,
                        localizedString(R.string.notification_session_text),
                        isRecording = false,
                        isPaused = false,
                    )
                    notifyConfigApplied(localizedString(R.string.recording_error, e.message ?: "codec"))
                    notifyStopped(savedOk = false)
                }
            }
        }
    }

    private fun pauseRecording() {
        val currentSession = session ?: return
        if (isPaused || endRequested) return
        accumulatedActiveMs = currentActiveElapsedMs()
        isPaused = true
        overlay?.onRecordingPaused()
        lifecycle.execute {
            currentSession.pause()
        }
        val telemetry = currentSession.snapshotTelemetry(accumulatedActiveMs)
        sendBroadcast(telemetry.toIntent().setPackage(packageName))
        CaptureNotification.update(
            this,
            localizedString(
                R.string.notification_paused_time,
                FloatingBarController.formatTime(accumulatedActiveMs),
            ),
            isRecording = true,
            isPaused = true,
        )
    }

    private fun resumeRecording() {
        val currentSession = session ?: return
        if (!isPaused || endRequested) return
        isPaused = false
        segmentStartedElapsed = SystemClock.elapsedRealtime()
        overlay?.onRecordingResumed()
        lifecycle.execute {
            currentSession.resume()
        }
        val telemetry = currentSession.snapshotTelemetry(accumulatedActiveMs)
        sendBroadcast(telemetry.toIntent().setPackage(packageName))
        CaptureNotification.update(
            this,
            localizedString(
                R.string.notification_recording_time,
                FloatingBarController.formatTime(accumulatedActiveMs),
            ),
            isRecording = true,
            isPaused = false,
        )
    }

    private fun stopRecording() {
        if (!recordingStopPosted.compareAndSet(false, true)) return
        timerActive = false
        isPaused = false
        mainHandler.removeCallbacks(timerRunnable)
        overlay?.onRecordingStopped()
        lifecycle.execute {
            val current = session
            current?.release()
            val savedOk = current?.savedOk == true
            session = null
            mainHandler.post {
                if (endAfterRecording) {
                    if (savedOk) notifyConfigApplied(localizedString(R.string.recording_saved))
                    finishSession(savedOk)
                } else {
                    if (savedOk) notifyConfigApplied(localizedString(R.string.recording_saved))
                    CaptureNotification.update(
                        this,
                        localizedString(R.string.notification_session_text),
                        isRecording = false,
                        isPaused = false,
                    )
                    notifyStopped(savedOk)
                }
            }
        }
    }

    private fun currentActiveElapsedMs(): Long {
        if (!timerActive) return accumulatedActiveMs
        if (isPaused) return accumulatedActiveMs
        return accumulatedActiveMs + (SystemClock.elapsedRealtime() - segmentStartedElapsed).coerceAtLeast(0L)
    }

    private fun requestEndSession() {
        if (endRequested) return
        endRequested = true
        endAfterRecording = true
        if (session == null) {
            finishSession(savedOk = false)
        } else {
            stopRecording()
        }
    }

    private fun finishSession(savedOk: Boolean = false) {
        if (finished) return
        finished = true
        timerActive = false
        isPaused = false
        mainHandler.removeCallbacks(timerRunnable)
        overlay?.hide()
        overlay = null
        val activeProjection = projection
        projection = null
        if (activeProjection != null) {
            runCatching { activeProjection.stop() }
        }
        releaseWakeLock()
        notifyStopped(savedOk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun showOverlay() {
        if (overlay != null) return
        overlay = FloatingBarController(applicationContext, object : FloatingBarController.Callbacks {
            override fun onStartRecording() = startRecording()
            override fun onPauseRecording() = pauseRecording()
            override fun onResumeRecording() = resumeRecording()
            override fun onStopRecording() = stopRecording()
            override fun onClose() = requestEndSession()
        })
        if (overlay?.show() == true) {
            CaptureDiagnostics.log("SERVICIO: bolita flotante visible (se oculta al grabar)")
        } else {
            CaptureDiagnostics.log("SERVICIO: falta permiso de superposición")
        }
    }

    private fun promoteToForeground() {
        val notification = CaptureNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val usesMic = when (RecordingPreferences.load(this).audioSource) {
                AudioSourceOption.MIC, AudioSourceOption.MIXED -> true
                AudioSourceOption.INTERNAL -> false
            }
            if (usesMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(CaptureNotification.NOTIFICATION_ID, notification, types)
            } catch (e: SecurityException) {
                Log.w(TAG, "Tipo microphone rechazado; usando solo mediaProjection", e)
                types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(CaptureNotification.NOTIFICATION_ID, notification, types)
            }
        } else {
            startForeground(CaptureNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock(durationMs: Long = 0L) {
        try {
            val manager = getSystemService(PowerManager::class.java)
            if (wakeLock == null) {
                wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SmoothRec:capture",
                ).apply {
                    setReferenceCounted(false)
                }
            }
            val timeout = if (durationMs > 0L) durationMs + 5 * 60 * 1000L else 65 * 60 * 1000L
            wakeLock?.acquire(timeout)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        val current = wakeLock ?: return
        wakeLock = null
        runCatching {
            if (current.isHeld) current.release()
        }
    }

    private fun notifyStopped(savedOk: Boolean) {
        sendBroadcast(
            Intent(CaptureNotification.ACTION_CAPTURE_STOPPED)
                .setPackage(packageName)
                .putExtra(CaptureNotification.EXTRA_SESSION_ACTIVE, projection != null)
                .putExtra(CaptureNotification.EXTRA_SAVED_OK, savedOk),
        )
    }

    private fun notifyConfigApplied(message: String) {
        sendBroadcast(
            Intent(CaptureNotification.ACTION_CONFIG_APPLIED)
                .setPackage(packageName)
                .putExtra(CaptureNotification.EXTRA_CONFIG_MESSAGE, message),
        )
    }

    private fun localizedString(resId: Int, vararg args: Any): String {
        val localized = LocaleHelper.wrap(this)
        return if (args.isEmpty()) localized.getString(resId)
        else localized.getString(resId, *args)
    }

    override fun onDestroy() {
        endRequested = true
        timerActive = false
        mainHandler.removeCallbacks(timerRunnable)
        val currentSession = session
        val currentProjection = projection
        session = null
        projection = null
        overlay?.hide()
        overlay = null
        releaseWakeLock()
        lifecycle.execute {
            currentSession?.release()
            if (currentProjection != null) {
                runCatching { currentProjection.stop() }
            }
            lifecycle.shutdown()
        }
        if (!finished) notifyStopped(savedOk = false)
        super.onDestroy()
    }
}
