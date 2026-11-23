package com.smoothrec.app.capture

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Servicio en primer plano que mantiene viva la grabación de pantalla. */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "com.smoothrec.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.smoothrec.app.extra.RESULT_DATA"
        private const val TAG = "ScreenCaptureService"
    }

    @Volatile
    private var session: CaptureSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Arranque y parada se ejecutan en orden, fuera del hilo principal. */
    private val lifecycle: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmoothRecLifecycle")
    }
    @Volatile
    private var stopRequested = false
    private val stopPosted = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CaptureNotification.ACTION_STOP) {
            Log.d(TAG, "Detención solicitada desde la notificación")
            requestStop()
            return START_NOT_STICKY
        }

        if (stopRequested || session != null) {
            promoteToForeground()
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val consentData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        if (consentData == null || resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Datos de consentimiento incompletos")
            stopSelf()
            return START_NOT_STICKY
        }

        CaptureNotification.createChannel(this)
        promoteToForeground()

        val projection = try {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, consentData)
        } catch (e: SecurityException) {
            Log.e(TAG, "No se pudo obtener MediaProjection", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val newSession = CaptureSession(
            applicationContext,
            projection,
            {
                Log.d(TAG, "Sesión detenida desde MediaProjection.Callback")
                requestStop()
            },
            { messageRes -> notifyConfigApplied(getString(messageRes)) },
        )
        session = newSession

        // La creación del codificador AAC puede bloquear varios segundos. Hacerla
        // en el hilo principal provoca ANR y grabaciones que nunca arrancan.
        lifecycle.execute {
            // Si ya se pidió detener, no se inicia nada después de la parada.
            if (stopRequested) {
                newSession.release()
                return@execute
            }
            try {
                newSession.start()
                newSession.appliedResolution?.let { result ->
                    val config = result.config
                    CaptureNotification.update(
                        this,
                        getString(
                            com.smoothrec.app.R.string.notification_text_dynamic,
                            config.width,
                            config.height,
                            config.frameRate,
                        ),
                    )
                    result.fallbackMessageRes?.let { res ->
                        notifyConfigApplied(getString(res, *result.fallbackFormatArgs.toTypedArray()))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al iniciar la captura", e)
                requestStop()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Publica la parada solo después de que el hilo de ciclo de vida haya
     * drenado los encoders y cerrado el MP4. Así el proceso no se destruye a
     * mitad de la finalización del archivo.
     */
    private fun requestStop() {
        if (!stopPosted.compareAndSet(false, true)) return
        stopRequested = true
        lifecycle.execute {
            session?.release()
            session = null
            mainHandler.post { stopSelf() }
        }
    }

    private fun promoteToForeground() {
        val notification = CaptureNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CaptureNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(CaptureNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun notifyStopped() {
        sendBroadcast(Intent(CaptureNotification.ACTION_CAPTURE_STOPPED).setPackage(packageName))
    }

    private fun notifyConfigApplied(message: String) {
        sendBroadcast(
            Intent(CaptureNotification.ACTION_CONFIG_APPLIED)
                .setPackage(packageName)
                .putExtra(CaptureNotification.EXTRA_CONFIG_MESSAGE, message),
        )
    }

    override fun onDestroy() {
        // Red de seguridad si el sistema destruye el servicio sin pasar por
        // requestStop(): se encola la liberación antes de cerrar el ejecutor.
        stopPosted.set(true)
        stopRequested = true
        val current = session
        session = null
        lifecycle.execute {
            current?.release()
            lifecycle.shutdown()
        }
        notifyStopped()
        super.onDestroy()
    }
}
