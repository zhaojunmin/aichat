package com.octos.aichat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.ui.MainActivity

/**
 * Keeps the process alive while the floating bubble is up: a foreground service with
 * a persistent notification, nothing else. It does no work of its own — the bubble
 * (`BubbleController`) lives in [ChatAssistantApp], which starts this service with
 * the bubble and stops it with the bubble. Process-level, like the Application, so it
 * sits in the root package.
 */
class KeepLiveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // In onCreate, not only onStartCommand: a stop that races the start must still
    // find startForeground called, or the system kills the app.
    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
    }

    // Not sticky: after a kill the Application starts it again once the bubble is back.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.svc_bubble_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_notif_title))
            .setContentText(getString(R.string.svc_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        runCatching {
            // A service type is required from API 34 (where specialUse was added).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure {
            // e.g. ForegroundServiceStartNotAllowedException: the bubble still shows,
            // only the keep-alive is lost.
            DiagLog.record(ErrorCode.BUB_501, cause = it)
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepLiveService::class.java))
            }.onFailure { DiagLog.record(ErrorCode.BUB_501, cause = it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepLiveService::class.java))
        }
    }
}
