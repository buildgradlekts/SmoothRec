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

/** Servicio en primer plano que mantiene viva la grabación de pantalla. */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "com.smoothrec.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.smoothrec.app.extra.RESULT_DATA"
        private const val TAG = "ScreenCaptureService"
    }

    private var session: CaptureSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CaptureNotification.ACTION_STOP) {
            Log.d(TAG, "Detención solicitada desde la notificación")
            stopSelf()
            return START_NOT_STICKY
        }

        if (session != null) {
            promoteToForeground()
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val consentData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        if (consentData == null || resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Datos de consentimiento incompletos")
            notifyStopped()
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
            notifyStopped()
            stopSelf()
            return START_NOT_STICKY
        }

        val newSession = CaptureSession(
            applicationContext,
            projection,
            {
                Log.d(TAG, "Sesión detenida desde MediaProjection.Callback")
                stopSelf()
            },
            { messageRes -> notifyConfigApplied(getString(messageRes)) },
        )
        session = newSession

        // La creación del codificador AAC puede bloquear hasta varios segundos.
        // Hacerla en el hilo principal provoca ANR y grabaciones que nunca arrancan.
        Thread({
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
                newSession.release()
                mainHandler.post {
                    notifyStopped()
                    stopSelf()
                }
            }
        }, "SmoothRecStartup").start()

        return START_NOT_STICKY
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
        val current = session
        session = null
        // El drenado de los encoders puede tardar segundos; hacerlo en el hilo
        // principal provoca ANR al detener y grabaciones que parecen no arrancar.
        if (current != null) {
            Thread({ current.release() }, "SmoothRecShutdown").start()
        }
        notifyStopped()
        super.onDestroy()
    }
}
