package com.smoothrec.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smoothrec.app.MainActivity
import com.smoothrec.app.R
import com.smoothrec.app.ui.LocaleHelper

/** Notificación persistente y acciones del servicio de captura en segundo plano. */
object CaptureNotification {

    const val CHANNEL_ID = "smoothrec_capture"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP = "com.smoothrec.app.action.STOP_CAPTURE"
    const val ACTION_PAUSE = "com.smoothrec.app.action.PAUSE_CAPTURE"
    const val ACTION_RESUME = "com.smoothrec.app.action.RESUME_CAPTURE"
    const val ACTION_END_SESSION = "com.smoothrec.app.action.END_SESSION"
    const val ACTION_CAPTURE_STOPPED = "com.smoothrec.app.action.CAPTURE_STOPPED"
    const val ACTION_CONFIG_APPLIED = "com.smoothrec.app.action.CONFIG_APPLIED"
    const val EXTRA_CONFIG_MESSAGE = "com.smoothrec.app.extra.CONFIG_MESSAGE"
    const val EXTRA_SESSION_ACTIVE = "com.smoothrec.app.extra.SESSION_ACTIVE"
    const val EXTRA_SAVED_OK = "com.smoothrec.app.extra.SAVED_OK"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localized = LocaleHelper.wrap(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun update(
        context: Context,
        text: CharSequence,
        isRecording: Boolean = false,
        isPaused: Boolean = false,
    ) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, text, isRecording, isPaused))
    }

    @Suppress("DEPRECATION")
    fun build(
        context: Context,
        customText: CharSequence? = null,
        isRecording: Boolean = false,
        isPaused: Boolean = false,
    ): Notification {
        val localized = LocaleHelper.wrap(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags,
        )
        val pauseResumeIntent = PendingIntent.getService(
            context,
            3,
            Intent(context, ScreenCaptureService::class.java).setAction(
                if (isPaused) ACTION_RESUME else ACTION_PAUSE,
            ),
            flags,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val closeIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_END_SESSION),
            flags,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }

        builder
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(localized.getString(R.string.notification_title))
            .setContentText(customText ?: localized.getString(R.string.notification_session_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (isRecording) {
            builder.addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                localized.getString(
                    if (isPaused) R.string.notification_resume_action
                    else R.string.notification_pause_action,
                ),
                pauseResumeIntent,
            )
            builder.addAction(
                android.R.drawable.ic_menu_save,
                localized.getString(R.string.notification_stop_action),
                stopIntent,
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localized.getString(R.string.notification_close_action),
                closeIntent,
            )
        }

        return builder.build()
    }
}
